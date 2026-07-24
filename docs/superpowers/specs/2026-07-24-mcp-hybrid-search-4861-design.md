# MCP hybrid search (#4861)

**Issue:** [#4861](https://github.com/ArcadeData/arcadedb/issues/4861)
**Epic:** [#4859 - MCP GraphRAG & Agent-Memory Surface](https://github.com/ArcadeData/arcadedb/issues/4859)
**Date:** 2026-07-24
**Status:** implemented

## Purpose

`hybrid_search` provides one bounded Model Context Protocol (MCP) operation for dense vector
retrieval, optional graph expansion, and optional full-text fusion. It removes the need for an
agent to coordinate several ArcadeDB-specific statements while enforcing traversal limits that
would be easy to omit in a hand-written query.

The tool does not generate embeddings. Callers supply a dense query vector produced by their
embedding model.

## Input

Required fields:

- `database`
- `vectorIndexName`
- `queryVector`
- `k`, from 1 through 1,000

Optional graph expansion:

```json
{
  "expand": {
    "edgeTypes": ["CITES", "AUTHORED"],
    "direction": "out",
    "maxDepth": 2
  }
}
```

`maxDepth` is always capped at 3. Expansion is breadth-first, deduplicates records reached by
multiple paths, and stops after 10,000 reached nodes. Omitting `edgeTypes`, or passing an empty
array, traverses all edge types in the requested direction.

Optional full-text fusion requires both fields:

```json
{
  "fulltextIndexName": "Paper[title,abstract]",
  "fulltextQuery": "+graph +database",
  "fusionStrategy": "RRF"
}
```

Supported fusion strategies are Reciprocal Rank Fusion (`RRF`), Distribution-Based Score Fusion
(`DBSF`), and min-max linear fusion (`LINEAR`). Reciprocal Rank Fusion is the default.

## Worked request

```json
{
  "database": "research",
  "vectorIndexName": "Paper[embedding]",
  "queryVector": [0.12, -0.08, 0.31],
  "k": 20,
  "expand": {
    "edgeTypes": ["CITES"],
    "direction": "out",
    "maxDepth": 2
  },
  "fulltextIndexName": "Paper[title,abstract]",
  "fulltextQuery": "\"graph database\"",
  "fusionStrategy": "RRF"
}
```

The response includes each result's record identifier, fused score, properties, and, when the
record came from graph expansion, its depth and representative path. Metadata reports source
count, expanded count, and whether the expansion cap truncated traversal.

## Ranking contract

Vector distance is converted to a higher-is-better score before score-based fusion. Full-text
scores are globally comparable across an index's buckets; this tool therefore depends on the
global BM25 scoring correction tracked by #5267. Graph expansion contributes a deterministic
score based on seed rank and depth.

When neither graph expansion nor full-text input is requested, the tool preserves the vector
ranking and reports fusion strategy `NONE`.

For pure two-way fusion without graph expansion, callers that need custom weights, grouping, or
other advanced options can use `vector.fuse` through the generic `query` tool.

## Safety and permissions

- The pipeline is read-only and requires `allowReads`.
- Generated fusion SQL is analyzed and rejected unless idempotent.
- Vector and full-text index inputs use the validation performed by their dedicated tools.
- Expansion requires vector results to be vertices.
- Missing records encountered during concurrent deletion are skipped.

## Validation

Tests cover vector-only ranking, graph expansion and deduplication, vector plus graph plus
full-text fusion, all three fusion strategies, depth enforcement, read denial, paired full-text
arguments, and rejection of expansion from document-only vector results.
