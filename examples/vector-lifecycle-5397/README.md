# Reproducible vector lifecycle fixture for ArcadeDB #5397

This standalone fixture checks bulk construction, repeated mutation/search cycles,
clean restart, and recovery of committed writes after an owned JVM is killed with
one transaction still uncommitted. It changes only its newly created disposable
database. It does not modify the engine or connect to a server.

Tested source:
[`313f23e234468eb109da727d5bd88a446a009201`](https://github.com/ArcadeData/arcadedb/commit/313f23e234468eb109da727d5bd88a446a009201)
(October 29, 2026; engine 26.11.1-SNAPSHOT; JVector 4.0.2).

Requirements: Linux or macOS, Python 3.11+, Git, Maven wrapper, a Java 21+ installation
with the `jdk.compiler` module, and at least 4 GiB available RAM/free disk. The Java
process uses a 2 GiB maximum heap, two visible CPUs and two graph-build threads.
The supplied runner has been exercised on Linux/JDK 21. JDK 25 and macOS runs are
additional validation targets, not claimed results.

## Build the pinned engine

From a directory containing the supplied files:

```bash
git clone https://github.com/ArcadeData/arcadedb.git arcadedb
git -C arcadedb checkout --detach 313f23e234468eb109da727d5bd88a446a009201
cd arcadedb
./mvnw -B -ntp -pl engine -am package -Dmaven.test.skip=true -DskipITs -Dlicense.skip=true
./mvnw -B -ntp -pl engine dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile="$(pwd)/runtime-dependencies.classpath"
cd ..
```

The packaging command skips the repository test suite. The lifecycle below is a
focused standalone test, not a replacement for engine regressions.

## Run

```bash
python3 portable_run.py --source ./arcadedb --dependencies ./arcadedb/runtime-dependencies.classpath --output ./smoke-jdk21 --scale smoke
python3 portable_run.py --source ./arcadedb --dependencies ./arcadedb/runtime-dependencies.classpath --output ./main-jdk21 --scale main
```

Select another JVM with `--java /path/to/jdk-25/bin/java` and a new output directory.
The helper recompiles for the selected JVM in each run. Output roots must not
already exist. Failures retain their database, logs and `FAILURE.json`; never
reuse a failed output root as a new run. The runner never deletes existing files.

## Workload and checks

Vectors have 768 float dimensions and 64 deterministic clusters. The initial corpus
is loaded before creating a single-bucket `LSM_VECTOR` index with `COSINE`,
`NONE` quantization, `maxConnections=16`, and `beamWidth=100`. Queries use
`efSearch=256` and `k=10`. Initial construction is requested once through
`CREATE INDEX`; there are no explicit `REBUILD INDEX` calls after mutation.
The index is resolved afresh from the current database/schema in each phase.

| Scale | Initial records | Updates / deletes / inserts per round | Online rounds | Committed records after crash round |
|---|---:|---:|---:|---:|
| smoke |1024|64 /64 /128|2|1216|
| main |8192|512 /512 /1024|4|10752|

Each round updates and deletes disjoint existing IDs and adds new IDs. Every
record's ID, version, vector float bits and primary-index RID are checked against
an independently computed expected model. Surviving IDs must retain their RIDs
across mutation and restart. Each query phase compares 24 deterministic queries
with exhaustive double-precision cosine ground truth. Additional queries probe
sampled updated/new/deleted vectors. Every returned result must be live, unique,
and scored against its current expected vector. Recall and self-retrieval are
reported measurements, with no arbitrary recall pass threshold.

The main run crosses four actual mutation thresholds. Current defaults count an
update as a removal and an insertion: one round produces 2560 pending index
mutations for 2048 logical record operations. The effective threshold and graph
rebuild counters are recorded before and after every phase. The inactivity timer
alone is disabled to prevent oracle-computation pauses selecting a time-based
trigger; count/ratio/scan policies retain their defaults. The 50,000-mutation cap
is not reduced, so this small fixture does not exercise the million-vector cap.

The crash writer commits one extra round using WAL `YES_FULL` and group commit
disabled, then prepares an uncommitted update, delete and insert. It fsyncs a
ready marker identifying the completed prefix and its PID. The Python parent
validates that marker, kills only its owned unreaped child, and checks the exact
committed state in two fresh JVMs. This tests a process crash, not power failure,
filesystem faults, or interruption at every possible merge/publication boundary.

## Evidence to compare

`PLAN.json` records revision, JVM, dependency/helper/class hashes and workload.
Each stage retains `native.log`, `phases.jsonl`, and a lifetime receipt. Normally
closed stages also have `REPORT.json`. The crash stage has `CRASH_READY.json`
and `PARENT_CRASH.json`. `IDENTITIES_*.json` records committed ID-to-RID mappings.

For cumulative graph-construction work, sum the N values in
`Building JVector graph index with N vectors`, distinguishing attempts that reach
`JVector graph index built successfully`. That message precedes publication and
persistence; it is a construction counter, not a durability receipt. Keep initial
construction, online rebuilds, crash recovery, and unchanged reopens separate.
The native `graphRebuildCount`, `mutationsSinceRebuild`, and
`effectiveMutationsThreshold` snapshots provide a second view.

Record checks precede search checks and warm record pages. Reported query latency
therefore describes this fixture sequence, not cold-cache service startup. The
clustered synthetic corpus and small population do not establish production
recall, throughput, or memory scaling. No tiered implementation is tested yet.

See `RESULTS.md` for observations from the October 28 and October 29 revisions. Share the workload/revision/JVM,
stage summaries, thresholds and rebuild sizes when comparing hosts. Generated
logs contain local paths, so inspect them before public posting.

The runner deliberately rejects a source revision other than its `PIN`. To compare
a future implementation branch, explicitly change that pin to the full commit
being evaluated, retain the changed runner hash, and use a fresh output directory.
That comparison is a new experiment, not one of the results reported here. This
fixture uses valid vectors with `NONE` quantization and does not exercise product
quantization, invalid-vector handling, or retained index objects across SQL
`REBUILD INDEX`.
