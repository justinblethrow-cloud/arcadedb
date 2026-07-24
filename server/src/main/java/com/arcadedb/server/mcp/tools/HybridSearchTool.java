/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.server.mcp.tools;

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.QueryEngine;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.serializer.JsonSerializer;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.ArcadeDBServer;
import com.arcadedb.server.mcp.MCPConfiguration;
import com.arcadedb.server.security.ServerSecurityUser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Model Context Protocol orchestration for vector, graph, and full-text retrieval.
 *
 * @author Justin Blethrow
 */
public class HybridSearchTool {
  private static final int DEFAULT_K          = 10;
  private static final int MAX_K              = 1_000;
  private static final int DEFAULT_MAX_DEPTH  = 1;
  private static final int MAX_DEPTH          = 3;
  private static final int MAX_EDGE_TYPES     = 64;
  private static final int MAX_EXPANDED_NODES = 10_000;

  private enum FusionStrategy {
    RRF, DBSF, LINEAR
  }

  public static JSONObject getDefinition() {
    return new JSONObject()
        .put("name", "hybrid_search")
        .put("description",
            """
            Retrieve by dense vector similarity, optionally expand over graph relationships, and optionally fuse those rankings \
            with a BM25 full-text index. Graph expansion is de-duplicated and bounded to depth 3 and 10,000 reached nodes. \
            Use the generic query tool with vector.fuse when graph expansion is not needed and you require custom fusion options. \
            Embedding generation is not performed by ArcadeDB.""")
        .put("inputSchema", new JSONObject()
            .put("type", "object")
            .put("properties", new JSONObject()
                .put("database", new JSONObject()
                    .put("type", "string")
                    .put("description", "The name of the database to search"))
                .put("vectorIndexName", new JSONObject()
                    .put("type", "string")
                    .put("description", "Name of a dense LSM_VECTOR index"))
                .put("queryVector", new JSONObject()
                    .put("type", "array")
                    .put("items", new JSONObject().put("type", "number"))
                    .put("description", "Pre-computed dense query vector"))
                .put("k", new JSONObject()
                    .put("type", "integer")
                    .put("minimum", 1)
                    .put("maximum", MAX_K)
                    .put("default", DEFAULT_K)
                    .put("description", "Maximum number of fused results to return"))
                .put("expand", new JSONObject()
                    .put("type", "object")
                    .put("description", "Optional bounded graph expansion from the vector hits")
                    .put("properties", new JSONObject()
                        .put("edgeTypes", new JSONObject()
                            .put("type", "array")
                            .put("maxItems", MAX_EDGE_TYPES)
                            .put("items", new JSONObject().put("type", "string"))
                            .put("description", "Edge types to traverse; omit or use an empty array for all edge types"))
                        .put("direction", new JSONObject()
                            .put("type", "string")
                            .put("enum", new JSONArray().put("out").put("in").put("both"))
                            .put("default", "out"))
                        .put("maxDepth", new JSONObject()
                            .put("type", "integer")
                            .put("minimum", 1)
                            .put("maximum", MAX_DEPTH)
                            .put("default", DEFAULT_MAX_DEPTH))))
                .put("fulltextIndexName", new JSONObject()
                    .put("type", "string")
                    .put("description", "Optional full-text index to fuse with vector and graph rankings"))
                .put("fulltextQuery", new JSONObject()
                    .put("type", "string")
                    .put("description", "Full-text query; required together with fulltextIndexName"))
                .put("fusionStrategy", new JSONObject()
                    .put("type", "string")
                    .put("enum", new JSONArray().put("RRF").put("DBSF").put("LINEAR"))
                    .put("default", "RRF")
                    .put("description",
                        "Reciprocal Rank Fusion, Distribution-Based Score Fusion, or min-max linear fusion")))
            .put("required",
                new JSONArray().put("database").put("vectorIndexName").put("queryVector").put("k")));
  }

  public static JSONObject execute(final ArcadeDBServer server, final ServerSecurityUser user, final JSONObject args,
      final MCPConfiguration config) {
    if (!config.isAllowReads())
      throw new SecurityException("Read operations are not allowed by MCP configuration");

    final String databaseName = MCPToolUtils.requireString(args, "database");
    final String vectorIndexName = MCPToolUtils.requireString(args, "vectorIndexName");
    final JSONArray queryVector = args.getJSONArray("queryVector", null);
    final int k = args.getInt("k", DEFAULT_K);
    if (k < 1 || k > MAX_K)
      throw new IllegalArgumentException("'k' must be between 1 and " + MAX_K);

    final FusionStrategy strategy = parseFusionStrategy(args.getString("fusionStrategy", "RRF"));
    final ExpansionConfig expansion = parseExpansion(args.getJSONObject("expand", null));
    final String fulltextIndexName = normalizeOptionalString(args.getString("fulltextIndexName", null));
    final String fulltextQuery = normalizeOptionalString(args.getString("fulltextQuery", null));
    if ((fulltextIndexName == null) != (fulltextQuery == null))
      throw new IllegalArgumentException("'fulltextIndexName' and 'fulltextQuery' must be provided together");

    final JSONObject vectorPayload = VectorSearchTool.executeForFusion(server, user, new JSONObject()
        .put("database", databaseName)
        .put("indexName", vectorIndexName)
        .put("queryVector", queryVector)
        .put("k", k), config);
    final List<VectorHit> vectorHits = readVectorHits(vectorPayload);
    final Database database = MCPToolUtils.resolveDatabase(server, user, databaseName);

    final List<List<Map<String, Object>>> sources = new ArrayList<>();
    sources.add(vectorSource(vectorHits));

    final Map<RID, PathInfo> paths = new LinkedHashMap<>();
    boolean expansionTruncated = false;
    int expandedCount = 0;
    if (expansion != null) {
      final ExpansionResult expanded = expand(database, vectorHits, expansion);
      sources.add(expanded.rows());
      paths.putAll(expanded.paths());
      expansionTruncated = expanded.truncated();
      expandedCount = expanded.rows().size();
    }

    if (fulltextIndexName != null) {
      final JSONObject fulltextPayload = FullTextSearchTool.executeForFusion(server, user, new JSONObject()
          .put("database", databaseName)
          .put("indexName", fulltextIndexName)
          .put("queryText", fulltextQuery)
          .put("limit", k), config);
      sources.add(fulltextSource(fulltextPayload));
    }

    final List<ScoredRID> ranked = sources.size() == 1
        ? rankVectorOnly(vectorHits)
        : fuse(database, sources, strategy, k);

    final JsonSerializer serializer = JsonSerializer.createJsonSerializer()
        .setIncludeVertexEdges(false)
        .setUseCollectionSize(false)
        .setUseCollectionSizeForEdges(false);
    final JSONArray results = new JSONArray();
    for (final ScoredRID scored : ranked) {
      if (results.length() >= k)
        break;

      final Record record;
      try {
        record = database.lookupByRID(scored.rid(), true);
      } catch (final RecordNotFoundException e) {
        continue;
      }
      if (!(record instanceof final Document document))
        continue;

      final JSONObject result = new JSONObject()
          .put("rid", scored.rid().toString())
          .put("fusedScore", scored.score())
          .put("properties", serializer.serializeDocument(document));
      final PathInfo path = paths.get(scored.rid());
      if (path != null) {
        result.put("depth", path.depth());
        final JSONArray pathRids = new JSONArray();
        for (final RID rid : path.path())
          pathRids.put(rid.toString());
        result.put("path", pathRids);
      }
      results.put(result);
    }

    final JSONObject output = new JSONObject()
        .put("vectorIndexName", vectorIndexName)
        .put("fusionStrategy", sources.size() == 1 ? "NONE" : strategy.name())
        .put("sourceCount", sources.size())
        .put("count", results.length())
        .put("expandedCount", expandedCount)
        .put("expansionTruncated", expansionTruncated)
        .put("results", results);
    if (fulltextIndexName != null)
      output.put("fulltextIndexName", fulltextIndexName);
    return output;
  }

  private static FusionStrategy parseFusionStrategy(final String raw) {
    try {
      return FusionStrategy.valueOf(raw == null ? "RRF" : raw.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "'fusionStrategy' must be one of RRF, DBSF, or LINEAR", e);
    }
  }

  private static ExpansionConfig parseExpansion(final JSONObject expand) {
    if (expand == null)
      return null;

    final int maxDepth = expand.getInt("maxDepth", DEFAULT_MAX_DEPTH);
    if (maxDepth < 1 || maxDepth > MAX_DEPTH)
      throw new IllegalArgumentException("'expand.maxDepth' must be between 1 and " + MAX_DEPTH);

    final String rawDirection = expand.getString("direction", "out");
    final Vertex.DIRECTION direction;
    try {
      direction = Vertex.DIRECTION.valueOf(rawDirection.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("'expand.direction' must be one of out, in, or both", e);
    }

    final JSONArray rawEdgeTypes = expand.getJSONArray("edgeTypes", null);
    final LinkedHashSet<String> edgeTypes = new LinkedHashSet<>();
    if (rawEdgeTypes != null) {
      if (rawEdgeTypes.length() > MAX_EDGE_TYPES)
        throw new IllegalArgumentException("'expand.edgeTypes' must contain at most " + MAX_EDGE_TYPES + " entries");
      for (int i = 0; i < rawEdgeTypes.length(); i++) {
        final Object value = rawEdgeTypes.get(i);
        if (!(value instanceof final String edgeType) || edgeType.isBlank())
          throw new IllegalArgumentException("'expand.edgeTypes' must contain only non-blank strings");
        edgeTypes.add(edgeType);
      }
    }
    return new ExpansionConfig(direction, edgeTypes.toArray(new String[0]), maxDepth);
  }

  private static String normalizeOptionalString(final String raw) {
    if (raw == null)
      return null;
    final String normalized = raw.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static List<VectorHit> readVectorHits(final JSONObject vectorPayload) {
    final JSONArray rows = vectorPayload.getJSONArray("results", new JSONArray());
    final List<VectorHit> hits = new ArrayList<>(rows.length());
    for (int i = 0; i < rows.length(); i++) {
      final JSONObject row = rows.getJSONObject(i);
      final String rawRid = row.getString("rid", null);
      if (rawRid == null || !RID.is(rawRid))
        continue;
      final Object rawDistance = row.opt("distance");
      if (!(rawDistance instanceof final Number distance))
        continue;
      hits.add(new VectorHit(new RID(rawRid), distance.floatValue(), i + 1));
    }
    return hits;
  }

  private static List<Map<String, Object>> vectorSource(final List<VectorHit> hits) {
    final List<Map<String, Object>> rows = new ArrayList<>(hits.size());
    for (final VectorHit hit : hits) {
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("@rid", hit.rid());
      row.put("distance", hit.distance());
      rows.add(row);
    }
    return rows;
  }

  private static List<Map<String, Object>> fulltextSource(final JSONObject fulltextPayload) {
    final JSONArray hits = fulltextPayload.getJSONArray("results", new JSONArray());
    final List<Map<String, Object>> rows = new ArrayList<>(hits.length());
    for (int i = 0; i < hits.length(); i++) {
      final JSONObject hit = hits.getJSONObject(i);
      final String rawRid = hit.getString("rid", null);
      final Object rawScore = hit.opt("score");
      if (rawRid == null || !RID.is(rawRid) || !(rawScore instanceof final Number score))
        continue;

      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("@rid", new RID(rawRid));
      row.put("score", score.floatValue());
      rows.add(row);
    }
    return rows;
  }

  private static ExpansionResult expand(final Database database, final List<VectorHit> seeds,
      final ExpansionConfig config) {
    final ArrayDeque<FrontierEntry> frontier = new ArrayDeque<>();
    final Set<RID> seen = new HashSet<>();
    for (final VectorHit seed : seeds) {
      if (seen.add(seed.rid()))
        frontier.addLast(new FrontierEntry(seed.rid(), 0, seed.rank(), List.of(seed.rid())));
    }

    final List<ExpandedHit> hits = new ArrayList<>();
    boolean truncated = false;
    expansion:
    while (!frontier.isEmpty()) {
      final FrontierEntry current = frontier.removeFirst();
      if (current.depth() >= config.maxDepth())
        continue;

      final Record record;
      try {
        record = database.lookupByRID(current.rid(), true);
      } catch (final RecordNotFoundException e) {
        continue;
      }
      if (!(record instanceof final Vertex vertex)) {
        if (current.depth() == 0)
          throw new IllegalArgumentException(
              "'expand' requires the vector index to return vertices, but " + current.rid() + " is not a vertex");
        continue;
      }

      for (final RID neighbor : vertex.getConnectedVertexRIDs(config.direction(), config.edgeTypes())) {
        if (!seen.add(neighbor))
          continue;

        final int depth = current.depth() + 1;
        final List<RID> path = new ArrayList<>(current.path().size() + 1);
        path.addAll(current.path());
        path.add(neighbor);
        final ExpandedHit hit = new ExpandedHit(neighbor, depth, current.seedRank(), List.copyOf(path));
        hits.add(hit);
        frontier.addLast(new FrontierEntry(neighbor, depth, current.seedRank(), hit.path()));

        if (hits.size() >= MAX_EXPANDED_NODES) {
          truncated = true;
          break expansion;
        }
      }
    }

    hits.sort(Comparator.comparingInt(ExpandedHit::depth)
        .thenComparingInt(ExpandedHit::seedRank)
        .thenComparing(ExpandedHit::rid));

    final List<Map<String, Object>> rows = new ArrayList<>(hits.size());
    final Map<RID, PathInfo> paths = new LinkedHashMap<>();
    for (final ExpandedHit hit : hits) {
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("@rid", hit.rid());
      row.put("score", 1.0f / (hit.seedRank() + hit.depth()));
      rows.add(row);
      paths.put(hit.rid(), new PathInfo(hit.depth(), hit.path()));
    }
    return new ExpansionResult(rows, paths, truncated);
  }

  private static List<ScoredRID> rankVectorOnly(final List<VectorHit> hits) {
    final List<ScoredRID> results = new ArrayList<>(hits.size());
    for (final VectorHit hit : hits)
      // Match vector.fuse's score-direction convention: a dense distance is negated so higher remains better.
      results.add(new ScoredRID(hit.rid(), -hit.distance()));
    return results;
  }

  private static List<ScoredRID> fuse(final Database database, final List<List<Map<String, Object>>> sources,
      final FusionStrategy strategy, final int k) {
    final StringBuilder sql = new StringBuilder("SELECT expand(`vector.fuse`(");
    final Map<String, Object> parameters = new LinkedHashMap<>();
    for (int i = 0; i < sources.size(); i++) {
      if (i > 0)
        sql.append(", ");
      final String parameter = "source" + i;
      sql.append(':').append(parameter);
      parameters.put(parameter, sources.get(i));
    }
    sql.append(", :options))");
    parameters.put("options", Map.of("fusion", strategy.name(), "limit", k));

    final QueryEngine.AnalyzedQuery analyzed;
    try {
      analyzed = database.getQueryEngine("sql").analyze(sql.toString());
    } catch (final RuntimeException e) {
      throw invalidFusion(e);
    }
    if (!analyzed.isIdempotent())
      throw new SecurityException("Generated hybrid fusion query is not read-only");

    final List<ScoredRID> results = new ArrayList<>();
    try {
      final ResultSet analyzedResultSet = analyzed.execute(parameters);
      try (final ResultSet resultSet =
          analyzedResultSet != null ? analyzedResultSet : database.query("sql", sql.toString(), parameters)) {
        while (resultSet.hasNext()) {
          final Result row = resultSet.next();
          final RID rid = resolveRID(row);
          final Object rawScore = row.getProperty("score");
          if (rid != null && rawScore instanceof final Number score)
            results.add(new ScoredRID(rid, score.floatValue()));
        }
      }
    } catch (final SecurityException e) {
      throw e;
    } catch (final RuntimeException e) {
      throw invalidFusion(e);
    }
    return results;
  }

  private static RID resolveRID(final Result row) {
    final Object rawRid = row.getProperty("@rid");
    if (rawRid instanceof final RID rid)
      return rid;
    if (rawRid instanceof final String value && RID.is(value))
      return new RID(value);
    return row.getIdentity().orElse(null);
  }

  private static IllegalArgumentException invalidFusion(final RuntimeException cause) {
    final String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    return new IllegalArgumentException("Invalid hybrid fusion: " + detail, cause);
  }

  private record VectorHit(RID rid, float distance, int rank) {
  }

  private record ScoredRID(RID rid, float score) {
  }

  private record ExpansionConfig(Vertex.DIRECTION direction, String[] edgeTypes, int maxDepth) {
  }

  private record FrontierEntry(RID rid, int depth, int seedRank, List<RID> path) {
  }

  private record ExpandedHit(RID rid, int depth, int seedRank, List<RID> path) {
  }

  private record PathInfo(int depth, List<RID> path) {
  }

  private record ExpansionResult(List<Map<String, Object>> rows, Map<RID, PathInfo> paths, boolean truncated) {
  }
}
