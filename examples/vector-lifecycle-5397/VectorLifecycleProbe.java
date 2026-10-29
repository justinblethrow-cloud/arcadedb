/*
 * SPDX-FileCopyrightText: 2026 justinblethrow-cloud
 * SPDX-License-Identifier: Apache-2.0
 */
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.Document;
import com.arcadedb.database.RID;
import com.arcadedb.engine.WALFile;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.vector.LSMVectorIndex;
import com.arcadedb.schema.Type;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.utility.Pair;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Standalone, disposable lifecycle fixture. No engine implementation is patched. */
public final class VectorLifecycleProbe {
  static final int DIMENSIONS = 768;
  static final int K = 10;
  static final int EF = 256;
  static final int UNCOMMITTED_ID = 900_000_000;
  static Path output;
  static String currentMode;
  static final JSONArray phases = new JSONArray();
  static int initial, chunk, rounds;

  static void require(boolean condition, String text) {
    if (!condition) throw new IllegalStateException(text);
  }

  // No engine vector/scoring helper participates in this generator or the oracle.
  static float[] vector(int id, int version) {
    SplittableRandom random = new SplittableRandom(0x539700000000L ^ ((long) id << 20) ^ version);
    float[] result = new float[DIMENSIONS];
    double norm = 0;
    int cluster = Math.floorMod(id + version * 17, 64);
    for (int d = 0; d < DIMENSIONS; d++) {
      result[d] = (float) ((random.nextDouble() - 0.5) * 0.08 + (d == cluster ? 1.0 : 0.0));
      norm += (double) result[d] * result[d];
    }
    norm = Math.sqrt(norm);
    for (int d = 0; d < DIMENSIONS; d++) result[d] /= norm;
    return result;
  }

  static double distance(float[] a, float[] b) {
    double dot = 0, aa = 0, bb = 0;
    for (int d = 0; d < DIMENSIONS; d++) {
      dot += (double) a[d] * b[d];
      aa += (double) a[d] * a[d];
      bb += (double) b[d] * b[d];
    }
    return 1.0 - dot / Math.sqrt(aa * bb);
  }

  static Map<Integer, Integer> expected(int completedRounds) {
    Map<Integer, Integer> model = new LinkedHashMap<>();
    for (int id = 0; id < initial; id++) model.put(id, 0);
    for (int r = 1; r <= completedRounds; r++) {
      int offset = (r - 1) * chunk * 2;
      require(offset + chunk * 2 <= initial, "initial corpus too small for disjoint churn rounds");
      for (int j = 0; j < chunk; j++) model.put(offset + j, 1);
      for (int j = 0; j < chunk; j++) model.remove(offset + chunk + j);
      for (int j = 0; j < chunk * 2; j++) model.put(initial + (r - 1) * chunk * 2 + j, 0);
    }
    return model;
  }

  static String modelHash(int completedRounds) throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    ByteBuffer bytes = ByteBuffer.allocate(8 + DIMENSIONS * 4);
    for (var entry : expected(completedRounds).entrySet()) {
      bytes.clear().putInt(entry.getKey()).putInt(entry.getValue());
      for (float value : vector(entry.getKey(), entry.getValue())) bytes.putInt(Float.floatToIntBits(value));
      hash.update(bytes.array());
    }
    return HexFormat.of().formatHex(hash.digest());
  }

  static JSONObject stats(LSMVectorIndex index) {
    return new JSONObject(index.getStats());
  }

  static void durableWrite(Path path, String value, boolean append) throws Exception {
    try (FileChannel file = FileChannel.open(path, StandardOpenOption.WRITE,
        append ? StandardOpenOption.CREATE : StandardOpenOption.CREATE_NEW,
        append ? StandardOpenOption.APPEND : StandardOpenOption.WRITE)) {
      ByteBuffer bytes = ByteBuffer.wrap(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      while (bytes.hasRemaining()) file.write(bytes);
      file.force(true);
    }
    try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
      directory.force(true);
    }
  }

  static void phase(JSONObject value) throws Exception {
    value.put("utc", java.time.Instant.now().toString());
    value.put("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    value.put("sumHeapPoolPeakUsedBytes", ManagementFactory.getMemoryPoolMXBeans().stream()
        .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
        .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum());
    phases.put(value);
    durableWrite(output.resolve("phases.jsonl"), value.toString() + "\n", true);
    System.out.println("FIXTURE_PHASE " + value);
  }

  static LSMVectorIndex index(Database db) {
    var typeIndex = db.getSchema().getIndexByName("Item[embedding]");
    require(typeIndex instanceof TypeIndex, "wrong or absent Item[embedding] index");
    var indexes = ((TypeIndex) typeIndex).getIndexesOnBuckets();
    require(indexes.length == 1 && indexes[0] instanceof LSMVectorIndex, "exactly one vector bucket required");
    LSMVectorIndex result = (LSMVectorIndex) indexes[0];
    require(result.getStats().get("dimensions") == DIMENSIONS, "vector dimension mismatch");
    require(result.getMetadata().quantizationType.name().equals("NONE"), "unexpected vector quantization");
    require(result.getMetadata().similarityFunction.name().equals("COSINE"), "unexpected similarity");
    return result;
  }

  static Document get(Database db, int id) {
    try (var cursor = db.lookupByKey("Item", "id", id)) {
      require(cursor.hasNext(), "missing ID " + id);
      Document doc = cursor.next().asDocument();
      require(!cursor.hasNext(), "duplicate indexed ID " + id);
      return doc;
    }
  }

  static void create(Database db) {
    var type = db.getSchema().createDocumentType("Item", 1);
    type.createProperty("id", Type.INTEGER);
    type.createProperty("version", Type.INTEGER);
    type.createProperty("embedding", Type.ARRAY_OF_FLOATS);
    for (int start = 0; start < initial; start += 256) {
      int from = start, to = Math.min(start + 256, initial);
      db.transaction(() -> {
        for (int id = from; id < to; id++)
          db.newDocument("Item").set("id", id, "version", 0, "embedding", vector(id, 0)).save();
      });
    }
    db.command("sql", "CREATE INDEX ON Item (id) UNIQUE").close();
    db.command("sql", "CREATE INDEX ON Item (embedding) LSM_VECTOR METADATA "
        + "{dimensions:768, similarity:'COSINE', quantization:'NONE', maxConnections:16, beamWidth:100}").close();
  }

  static void mutate(Database db, int round) {
    int offset = (round - 1) * chunk * 2;
    db.transaction(() -> {
      for (int j = 0; j < chunk; j++) {
        int id = offset + j;
        get(db, id).modify().set("version", 1, "embedding", vector(id, 1)).save();
      }
      for (int j = 0; j < chunk; j++) get(db, offset + chunk + j).delete();
      for (int j = 0; j < chunk * 2; j++) {
        int id = initial + (round - 1) * chunk * 2 + j;
        db.newDocument("Item").set("id", id, "version", 0, "embedding", vector(id, 0)).save();
      }
    });
  }

  static Map<Integer, float[]> verifyRecords(Database db, int completedRounds) {
    Map<Integer, Integer> model = expected(completedRounds);
    Map<Integer, float[]> oracle = new LinkedHashMap<>();
    HashSet<Integer> seen = new HashSet<>();
    Map<String, String> identities = new LinkedHashMap<>();
    var iterator = db.iterateType("Item", false);
    while (iterator.hasNext()) {
      Document doc = iterator.next().asDocument();
      int id = doc.getInteger("id"), version = doc.getInteger("version");
      require(model.containsKey(id), "unexpected source record " + id);
      require(seen.add(id), "duplicate source record " + id);
      require(model.get(id) == version, "wrong version for " + id);
      float[] wanted = vector(id, version);
      Object stored = doc.get("embedding");
      float[] actual;
      if (stored instanceof float[] floats) actual = floats;
      else if (stored instanceof List<?> values) {
        actual = new float[values.size()];
        for (int i = 0; i < actual.length; i++) actual[i] = ((Number) values.get(i)).floatValue();
      } else throw new IllegalStateException("unexpected vector representation " + stored.getClass());
      require(Arrays.equals(wanted, actual), "wrong vector bits for " + id);
      require(get(db, id).getIdentity().equals(doc.getIdentity()), "primary index RID mismatch " + id);
      identities.put(Integer.toString(id), doc.getIdentity().toString());
      oracle.put(id, wanted);
    }
    require(seen.equals(model.keySet()), "source key-set mismatch");
    require(index(db).countEntries() == model.size(), "vector entry count mismatch");
    try {
      Path identityFile = output.getParent().resolve("IDENTITIES_" + completedRounds + ".json");
      if (Files.exists(identityFile)) {
        JSONObject prior = new JSONObject(Files.readString(identityFile));
        require(prior.length() == identities.size(), "identity manifest size changed");
        for (var entry : identities.entrySet())
          require(entry.getValue().equals(prior.getString(entry.getKey())), "RID changed across reopen for ID " + entry.getKey());
      } else {
        require(currentMode.equals("seed") || currentMode.equals("crash_writer"), "missing committed identity manifest");
        if (completedRounds > 0) {
          JSONObject previous = new JSONObject(Files.readString(output.getParent().resolve("IDENTITIES_" + (completedRounds - 1) + ".json")));
          for (var entry : identities.entrySet())
            if (previous.has(entry.getKey()))
              require(entry.getValue().equals(previous.getString(entry.getKey())), "mutation changed surviving RID " + entry.getKey());
        }
        durableWrite(identityFile, new JSONObject(identities).toString() + "\n", false);
      }
    } catch (Exception error) {
      throw new IllegalStateException("identity validation failed", error);
    }
    return oracle;
  }

  static List<Integer> search(Database db, LSMVectorIndex index, float[] query, Map<Integer, float[]> oracle) {
    List<Pair<RID, Float>> rows = index.findNeighborsFromVector(query, K, EF);
    require(rows.size() == Math.min(K, oracle.size()), "short search result");
    List<Integer> ids = new ArrayList<>();
    double previous = -Double.MAX_VALUE;
    for (var row : rows) {
      Document doc = db.lookupByRID(row.getFirst(), true).asDocument();
      int id = doc.getInteger("id");
      require(oracle.containsKey(id), "search returned deleted/uncommitted/unknown ID " + id);
      require(!ids.contains(id), "duplicate search ID " + id);
      double exact = distance(query, oracle.get(id));
      require(Math.abs(exact - row.getSecond()) <= 0.00002,
          "stale or incorrect vector distance for " + id + ": " + row.getSecond() + " != " + exact);
      require(row.getSecond() + 0.000001 >= previous, "search distances out of order");
      previous = row.getSecond();
      ids.add(id);
    }
    return ids;
  }

  static JSONObject verifySearches(Database db, int completedRounds, Map<Integer, float[]> oracle) {
    LSMVectorIndex index = index(db);
    JSONArray recalls = new JSONArray();
    long candidateNanos = 0, oracleNanos = 0;
    for (int q = 0; q < 24; q++) {
      float[] query = vector(4_000_000 + q * 7, q % 3);
      long started = System.nanoTime();
      List<Integer> found = search(db, index, query, oracle);
      candidateNanos += System.nanoTime() - started;
      started = System.nanoTime();
      var nearest = oracle.entrySet().stream()
          .map(entry -> new Scored(entry.getKey(), distance(query, entry.getValue())))
          .sorted(Comparator.comparingDouble(Scored::distance).thenComparingInt(Scored::id))
          .limit(K).map(Scored::id).toList();
      oracleNanos += System.nanoTime() - started;
      long intersection = found.stream().filter(nearest::contains).count();
      recalls.put(new JSONObject().put("query", q).put("recallAt10", intersection / (double) K)
          .put("expected", new JSONArray(nearest)).put("actual", new JSONArray(found)));
    }
    int selfProbes = 0, selfHits = 0, deleteProbes = 0;
    // Sample evenly across the last mutation batch; all records were checked exactly above.
    List<Integer> selfIds = new ArrayList<>();
    if (completedRounds == 0) {
      for (int s = 0; s < 32; s++) selfIds.add(s * (initial - 1) / 31);
    } else {
      int offset = (completedRounds - 1) * chunk * 2;
      for (int s = 0; s < Math.min(32, chunk); s++) {
        int j = s * (chunk - 1) / Math.max(1, Math.min(32, chunk) - 1);
        selfIds.add(offset + j);
        selfIds.add(initial + (completedRounds - 1) * chunk * 2 + 2 * j);
        search(db, index, vector(offset + chunk + j, 0), oracle);
        deleteProbes++;
      }
    }
    for (int id : selfIds) {
      var found = search(db, index, oracle.get(id), oracle);
      selfProbes++;
      if (found.getFirst() == id) selfHits++;
    }
    double sum = 0, min = 1;
    for (int i = 0; i < recalls.length(); i++) {
      double value = recalls.getJSONObject(i).getDouble("recallAt10");
      sum += value;
      min = Math.min(min, value);
    }
    return new JSONObject().put("recallQueries", recalls).put("meanRecallAt10", sum / 24)
        .put("minimumRecallAt10", min).put("selfProbes", selfProbes).put("selfTop1Hits", selfHits)
        .put("deletedVectorQueries", deleteProbes).put("candidateRecallQuerySeconds", candidateNanos / 1e9)
        .put("oracleSeconds", oracleNanos / 1e9).put("returnedValuesAndMembershipExact", true)
        .put("recallIsMeasuredNotAPassThreshold", true);
  }

  record Scored(int id, double distance) {}

  static void settle(Database db) throws Exception {
    LSMVectorIndex index = index(db);
    long deadline = System.nanoTime() + 120_000_000_000L;
    int quiet = 0;
    long lastBuilds = -1;
    while (System.nanoTime() < deadline) {
      var snapshot = index.getStats();
      long builds = snapshot.get("graphRebuildCount");
      if (snapshot.get("asyncRebuildInProgress") == 0 && builds == lastBuilds) quiet++;
      else quiet = 0;
      if (quiet >= 5) return;
      lastBuilds = builds;
      Thread.sleep(100);
    }
    throw new IllegalStateException("background rebuild did not settle within120s");
  }

  static void verify(Database db, int completedRounds, String name) throws Exception {
    JSONObject beforeValidation = stats(index(db));
    long started = System.nanoTime();
    Map<Integer, float[]> oracle = verifyRecords(db, completedRounds);
    double recordSeconds = (System.nanoTime() - started) / 1e9;
    JSONObject beforeQueries = stats(index(db));
    started = System.nanoTime();
    JSONObject queries = verifySearches(db, completedRounds, oracle);
    double querySeconds = (System.nanoTime() - started) / 1e9;
    settle(db);
    phase(new JSONObject().put("phase", name).put("completedRounds", completedRounds)
        .put("recordsVerified", oracle.size()).put("recordVerificationSeconds", recordSeconds)
        .put("searchVerificationSeconds", querySeconds).put("searches", queries)
        .put("statsBeforeValidation", beforeValidation).put("statsBeforeQueries", beforeQueries)
        .put("statsAfterQueries", stats(index(db)))
        .put("expectedModelSha256", modelHash(completedRounds)));
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 7, "mode dbPath outputPath initial chunk rounds nonce");
    ProcessHandle owner = ProcessHandle.current().parent().orElseThrow();
    var ownerStarted = owner.info().startInstant();
    long nativeDeadline = System.nanoTime() + 930_000_000_000L;
    Thread watchdog = new Thread(() -> {
      try {
        while (true) {
          Thread.sleep(200);
          if (System.nanoTime() > nativeDeadline || !owner.isAlive()
              || !owner.info().startInstant().equals(ownerStarted)) Runtime.getRuntime().halt(72);
        }
      } catch (InterruptedException interrupted) {
        Runtime.getRuntime().halt(73);
      }
    }, "fixture-parent-watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
    String mode = args[0];
    currentMode = mode;
    Path dbPath = Path.of(args[1]).toAbsolutePath();
    output = Path.of(args[2]).toAbsolutePath();
    initial = Integer.parseInt(args[3]); chunk = Integer.parseInt(args[4]); rounds = Integer.parseInt(args[5]);
    String nonce = args[6];
    require(Files.isDirectory(output), "output directory must exist");
    expected(rounds + 1);
    GlobalConfiguration.TX_WAL.setValue(true);
    GlobalConfiguration.TX_WAL_FLUSH.setValue(2);
    GlobalConfiguration.TX_WAL_GROUP_COMMIT.setValue(false);
    GlobalConfiguration.VECTOR_INDEX_GRAPH_BUILD_PARALLELISM.setValue(2);
    GlobalConfiguration.VECTOR_INDEX_INACTIVITY_REBUILD_TIMEOUT_MS.setValue(0);
    GlobalConfiguration.MAX_PAGE_RAM.setValue(64);
    GlobalConfiguration.ASYNC_WORKER_THREADS.setValue(2);
    long openedAt = System.nanoTime();
    boolean seed = mode.equals("seed");
    try (DatabaseFactory factory = new DatabaseFactory(dbPath.toString())) {
      Database db = seed ? factory.create() : factory.open();
      db.setUseWAL(true).setWALFlush(WALFile.FlushType.YES_FULL);
      phase(new JSONObject().put("phase", "open").put("mode", mode).put("pid", ProcessHandle.current().pid())
          .put("nonce", nonce).put("databasePath", dbPath.toString())
          .put("engineLocation", Database.class.getProtectionDomain().getCodeSource().getLocation().toString())
          .put("openSeconds", (System.nanoTime() - openedAt) / 1e9)
          .put("statsAtOpen", seed ? JSONObject.NULL : stats(index(db))));
      try {
        if (seed) {
          long started = System.nanoTime();
          create(db);
          // CREATE INDEX bulk construction is complete before this first ordinary search.
          phase(new JSONObject().put("phase", "bulk-create").put("seconds", (System.nanoTime() - started) / 1e9)
              .put("stats", stats(index(db))));
          verify(db, 0, "initial-search");
          for (int round = 1; round <= rounds; round++) {
            JSONObject before = stats(index(db));
            started = System.nanoTime();
            mutate(db, round);
            phase(new JSONObject().put("phase", "mutation-" + round).put("round", round)
                .put("seconds", (System.nanoTime() - started) / 1e9).put("updates", chunk)
                .put("deletes", chunk).put("inserts", chunk * 2).put("statsBefore", before)
                .put("statsAfterCommit", stats(index(db))));
            verify(db, round, "round-" + round + "-search");
          }
        } else if (mode.equals("crash_writer")) {
          JSONObject before = stats(index(db));
          long started = System.nanoTime();
          mutate(db, rounds + 1);
          phase(new JSONObject().put("phase", "crash-committed-prefix").put("round", rounds + 1)
              .put("seconds", (System.nanoTime() - started) / 1e9).put("statsBefore", before)
              .put("statsAfterCommit", stats(index(db))));
          verifyRecords(db, rounds + 1);
          Path identityFile = output.getParent().resolve("IDENTITIES_" + (rounds + 1) + ".json");
          JSONObject marker = new JSONObject().put("pid", ProcessHandle.current().pid()).put("nonce", nonce)
              .put("databasePath", dbPath.toString()).put("committedRounds", rounds + 1)
              .put("expectedRecords", expected(rounds + 1).size()).put("expectedModelSha256", modelHash(rounds + 1))
              .put("identityManifestSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                  .digest(Files.readAllBytes(identityFile))))
              .put("walEnabled", true).put("walFlush", "YES_FULL").put("groupCommit", false)
              .put("uncommittedInsertId", UNCOMMITTED_ID).put("uncommittedUpdateId", 0)
              .put("uncommittedDeleteId", 1);
          db.begin();
          get(db, 0).modify().set("version", 999, "embedding", vector(0, 999)).save();
          get(db, 1).delete();
          db.newDocument("Item").set("id", UNCOMMITTED_ID, "version", 0,
              "embedding", vector(UNCOMMITTED_ID, 0)).save();
          durableWrite(output.resolve("CRASH_READY.json"), marker.toString(2) + "\n", false);
          System.out.println("CRASH_READY " + marker);
          // The harness kills this exact owned PID. If it vanishes, stop instead of leaking a process.
          long parent = ProcessHandle.current().parent().orElseThrow().pid();
          long deadline = System.nanoTime() + 60_000_000_000L;
          while (System.nanoTime() < deadline && ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false))
            Thread.sleep(100);
          throw new IllegalStateException("crash harness did not kill its child within60s");
        } else {
          int completed = mode.equals("reopen_clean") ? rounds : rounds + 1;
          require(mode.equals("reopen_clean") || mode.equals("recover") || mode.equals("reopen_after_recovery"),
              "unknown mode " + mode);
          verify(db, completed, mode + "-search");
        }
        phase(new JSONObject().put("phase", "before-close").put("stats", stats(index(db))));
      } finally {
        long started = System.nanoTime();
        db.close();
        phase(new JSONObject().put("phase", "closed").put("closeSeconds", (System.nanoTime() - started) / 1e9));
      }
    }
    durableWrite(output.resolve("REPORT.json"), new JSONObject().put("status", "PASS").put("mode", mode)
        .put("nonce", nonce).put("phases", phases).toString(2) + "\n", false);
  }
}
