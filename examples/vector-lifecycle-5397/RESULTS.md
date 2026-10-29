# Vector lifecycle observations, October 28–29, 2026

The same valid-vector workload passed on both pinned revisions below. The
published runner targets the October 29 revision. All runs used OpenJDK 21.0.12.1
on Linux x86_64, two visible CPUs, two graph-build threads, a 2 GiB heap,
JVector 4.0.2, full WAL flush, and group commit disabled.

| Revision | Runner | Complete lifecycle, seconds | Outcome |
|---|---|---:|---|
| [`a0a6842101`](https://github.com/ArcadeData/arcadedb/commit/a0a6842101cfc6cfee97f934f5bb36ccd9c464b0), October 28 | Local bounded coordinator | 37.20 | Pass |
| [`313f23e234`](https://github.com/ArcadeData/arcadedb/commit/313f23e234468eb109da727d5bd88a446a009201), October 29 | Local bounded coordinator | 34.81 | Pass |
| Same October 29 revision | Published portable runner | 35.15 | Pass |

Each time includes all five JVM lifetimes, exact validation, and the controlled
crash. These are single-host descriptive observations, not a randomized or
repeated A/B performance comparison. The local coordinator lowers process CPU
and I/O priority; the portable runner retains the invoking process's defaults.
The timing difference does not establish an upstream speedup.

The construction sizes and measured recall were identical in all three runs:

| Stage | Records | Completed graph-construction sizes | Mean recall@10 |
|---|---:|---|---:|
| Initial build | 8192 | 8192 | 1.0 |
| Mutation round 1 | 8704 | 8704 | 1.0 |
| Mutation round 2 | 9216 | 9216 | 1.0 |
| Mutation round 3 | 9728 | 9728 | 1.0 |
| Mutation round 4 | 10240 | 10240 | 1.0 |
| Clean unchanged reopen | 10240 | none | 1.0 |
| Recovery after committed round 5 and an uncommitted transaction | 10752 | 10752 | 1.0 |
| Second unchanged reopen after recovery | 10752 | none | 1.0 |

All exact records, vector bits, surviving RIDs, and result membership/distances
passed. Every sampled self query returned its record first. The uncommitted
insert/update/delete did not survive the crash. Recall is measured on 24 queries
per query phase, with 32 initial and 64 subsequent self probes; it does not imply
exhaustive ANN recall over the corpus.

The four online thresholds were 1638, 1740, 1843, and 1945 pending index mutations.
Each round produced 2560 pending mutations and rebuilt the entire live corpus.
Online construction revisited 37888 vectors after the initial 8192-vector build.
Crash recovery constructed 10752 more, for 56832 total construction entries;
neither unchanged reopen rebuilt. The effective threshold after round 4 was
2048 and after recovery 2150. Before lazy graph loading, the reported threshold
was the absolute floor 100; interpret it with `graphNodeCount`.

The local coordinator's maximum sampled RSS was 730918912 bytes on October 28
and 748683264 bytes on October 29 (approximately 697 and 714 MiB). The portable
runner does not report RSS, so no portable-run RSS result is claimed.

Independent retained-evidence audits checked each revision's helper/classes,
37 runtime classpath entries (35 JARs and two POM metadata files), native logs,
committed identity manifests, construction accounting, and normally closed
native reports. The local coordinator also hashes the closed database and
records process birth identities. The portable audit verifies the owned-child
reaping receipts and current absence of the five recorded native PIDs. No audit
reopens the database engine. Every native child was reaped.

## Upstream changes and coverage

The October 29 revision includes fixes for retired vector-index handles
([#10900](https://github.com/ArcadeData/arcadedb/pull/10900)), reuse of PRODUCT
quantization codebooks ([#10904](https://github.com/ArcadeData/arcadedb/pull/10904)),
and invalid array/vector values with skipped-record reporting
([#10964](https://github.com/ArcadeData/arcadedb/pull/10964)). This fixture uses
valid float vectors and `NONE` quantization, resolves an index afresh in each
phase, and makes no SQL `REBUILD INDEX` call after mutation. It therefore does
not claim to exercise those specific regressions. The new
`lastBuildSkippedRecords` counter was zero in all 25 captured local snapshots.

No engine implementation was patched. These results support the fixture as a
pinned lifecycle baseline and confirm repeated whole-graph construction at
this scale. They do not implement #5397 or establish million-vector throughput,
production recall, physical power-loss durability, or live upgrade readiness.
JDK 25, another host, and a future tiered implementation remain comparison targets.
