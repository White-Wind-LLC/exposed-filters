# Benchmarks

JMH benchmarks measuring how much time `exposed-filters` adds to a request compared with writing the same
predicate by hand in the Exposed DSL. Not published (excluded from publishing by `-PexcludeSamples=true`). CI
compiles the benchmarks as part of `build`, so an API change that breaks them fails the PR, but never runs them.

## Running

```bash
./gradlew :benchmark:jmh                                  # everything (~20 min)
./gradlew :benchmark:jmh -PjmhInclude='FilterBenchmarks.a' # a subset, regex over benchmark names
```

Results land in `benchmark/build/results/jmh/results.json`. Close other heavy processes while it runs —
a Gradle build in the background skews the numbers.

## What is measured

Every scenario in `Fixtures.kt` expresses one filter in three forms: the JSON body a client sends, the
hand-written Exposed predicate, and a field map computed once up front. At setup the benchmark checks that
the manual and library variants render **identical SQL** and return the same row count; if a library change
makes them diverge, the run fails instead of comparing different queries.

| Benchmark | Measures |
|---|---|
| `a0_txBaseline` | Transaction open/close spread over 500 ops. Included in every `a*`/`b*` number. |
| `a1_manual_buildSql` | Hand-written predicate + SQL rendering. The reference point. |
| `a2_library_buildSql` | `applyFiltersOn(source, request)` + SQL rendering. `a2 − a1` is the library's overhead. |
| `a3_library_cachedMap_buildSql` | `applyFilters(precomputedMap, request)`. `a2 − a3` is the cost of per-request field resolution (reflection). |
| `b_library_parseBuildSql` | Same as `a2`, starting from the raw JSON body. |
| `c1_manual_execute` / `c2_library_execute` | Full execution on in-memory H2 (1000 products) — overhead in proportion to a real query. |
| `ReflectionBenchmarks` | `propertyToColumnMap()` and JSON parsing in isolation. |

Scenarios: `SIMPLE_EQ`, `AND_4_FIELDS`, `OR_NOT_IN_BETWEEN`, `NESTED_REF` (`warehouseId.name` → `EXISTS`)
on a `Table`; `JOIN_COLUMNS` (plain join, fields by SQL name) and `STOCK_COMPUTED` (grouped subquery alias
joined to a table, filter on a computed `total`).

When adding a feature with its own resolution path, add a scenario for it here.

## Tracking over time

Before a release, run the full suite and commit the result as `benchmark/results/<version>.json`. Compare
two versions by loading both files into <https://jmh.morethan.io>.

Numbers are only comparable across runs on the same machine and JDK. Baselines so far were recorded on:

| Version | Machine | JDK |
|---|---|---|
| 1.12.0 | Apple M4 Pro | OpenJDK 21.0.12 |

### Baseline 1.12.0 (µs per request, build + render SQL, no DB)

| Scenario | manual (`a1`) | library (`a2`) | overhead | of which field resolution (`a2 − a3`) | + JSON parse (`b`) |
|---|---|---|---|---|---|
| SIMPLE_EQ | 3.8 | 12.2 | +8.4 | ~7.6 | 15.9 |
| AND_4_FIELDS | 4.7 | 13.8 | +9.0 | ~7.2 | 16.2 |
| OR_NOT_IN_BETWEEN | 4.5 | 13.7 | +9.2 | ~7.4 | 17.5 |
| NESTED_REF | 5.2 | 19.3 | +14.1 | ~5.9 (+ target table map, not cached by `a3`) | 24.7 |
| JOIN_COLUMNS | 5.4 | 8.2 | +2.9 | within noise | 16.7 |
| STOCK_COMPUTED | 2.2 | 4.7 | +2.5 | ~1.8 | 5.7 |

On a `Table`, most of the overhead is `propertyToColumnMap()` (~6–7 µs regardless of column count), which is
recomputed on every request. Full execution on in-memory H2 takes 90–330 µs per query, so the overhead is
within run-to-run noise there, and a smaller share still against a networked database.

The suite is intentionally not run in CI: shared runners are noisy well beyond the microsecond differences
measured here.
