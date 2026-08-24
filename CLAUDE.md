# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Kotlin library that accepts dynamic filter requests (JSON), normalizes them into a typed filter tree, and translates them into JetBrains Exposed DSL `Op<Boolean>` predicates. Published to Maven Central as three artifacts under `ua.wwind.exposed-filters`.

## Build / test commands

JVM toolchain is Java 17. Use the Gradle wrapper.

- Build everything without tests (matches CI): `./gradlew clean build -x test`
- Run all tests: `./gradlew test`
- Run tests for one module: `./gradlew :jdbc:test` (or `:core:test`, `:rest:test`)
- Run a single test class: `./gradlew :jdbc:test --tests "ua.wwind.exposed.filters.jdbc.QueryFilterExtensionsTest"`
- Run a single test method: `./gradlew :jdbc:test --tests "ua.wwind.exposed.filters.jdbc.QueryFilterExtensionsTest.someMethod"`
- Generate Dokka docs (all modules): `./gradlew generateDocs`
- Skip the `example` sample module (used by CI/publish): `-PexcludeSamples=true`
- Local Maven Central publish: see `RELEASING.md` (requires signing + credentials env vars)

Note: `jdbc` tests use Testcontainers + PostgreSQL and H2; Docker must be running for PostgreSQL-backed tests.

## Module layout and dependencies

Three published modules plus a non-published sample:

- `core` — pure-Kotlin filter model (no Exposed/Ktor dependency). Contains `FilterRequest`, `FilterNode` (`FilterLeaf` / `FilterGroup`), `FieldFilter`, `FilterOperator`, `FilterCombinator`, the `filterRequest { ... }` DSL builder (`FilterRequestBuilder.kt`), JSON (de)serialization (`FilterRequestSerialization.kt`), and field-exclusion utility (`FilterFieldExclusion.kt`).
- `jdbc` — depends on `core` + Exposed. Translates `FilterNode` into Exposed `Op<Boolean>`. This is the main logic module.
- `rest` — depends on `core` + Ktor. Only responsibility: receive/parse a JSON body into a `FilterRequest` (`ApplicationCall.receiveFilterRequestOrNull`, `parseFilterRequestOrNull`). Accepts both flat and tree JSON shapes and normalizes them.
- `example` — not published. Ktor + H2 sample app; excluded via `-PexcludeSamples=true` in CI/publish.

All modules use `explicitApi()` — new public declarations must be explicitly marked `public`.

The `jdbc` module enables Kotlin context parameters (`-Xcontext-parameters`); several internal functions use `context(mappersModule: ColumnMappersModule?, options: FilterOptions)` to thread filter state without plumbing it through every signature. Preserve this pattern when editing.

## Architecture / data flow

Understanding the translation pipeline is key:

1. **JSON → `FilterRequest`** (`rest/FilterExtraction.kt`). The parser accepts two shapes — flat (`{ filters: {...} }`) and tree (`{ combinator, children: [...] }`) — and normalizes them. **Parsing is strict about shape, lenient about scalar syntax** (`ignoreUnknownKeys = false`, `coerceInputValues = false`, `isLenient = true`): a non-blank body that is not a valid filter body throws `FilterRequestParseException`. Do not turn `ignoreUnknownKeys` or `coerceInputValues` back on — they turn a client mistake into an unfiltered result set with `200 OK`, which is the bug this replaced. Do not turn `isLenient` off either: filter values are declared as `String` but clients send them typed (`{"op":"GT","value":5}`), and strict scalar parsing rejects those with a 400 — the 1.9.0 → 1.9.1 regression. A blank body, `{}` and `{"filters":{}}` still mean "no filters" and return `null`. Important normalization rules live in `normalize()` and `toNodeOrNull()`:
   - Empty leaves/groups are stripped.
   - Single-child AND/OR groups collapse to their child.
   - **NOT groups with a single child are preserved** (collapsing would drop the negation).
   - When a single-leaf body has an `OR`/`NOT` combinator, each predicate is wrapped in its own `FilterLeaf` so the combinator applies per-predicate rather than being meaningless.
2. **`FilterRequest` → Exposed predicate** (`jdbc/QueryFilterNodePredicates.kt` `nodeToPredicate`). Recursively walks the tree: `FilterLeaf` predicates AND together; `FilterGroup` applies its `FilterCombinator` (`AND`/`OR`/`NOT`). `NOT` groups wrap the AND of their children in `not(...)`.
3. **Field resolution** (`jdbc/QueryFilterExtensions.kt`). `Query.applyFiltersOn(Table, filter)` uses reflection (`Table::propertyToColumnMap`) to map **Kotlin property names** (e.g. `warehouseId`) to columns — not SQL column names. For a non-`Table` `ColumnSet` (Join/Alias), it falls back to SQL column names. `applyFilters(Map<String, ExpressionWithColumnType<*>>, ...)` is the low-level escape hatch for unions, aliases, and computed expressions.
4. **Dot-path fields** (`predicateForField`). Two unrelated features share `foo.bar` syntax:
   - If the base column has `JsonColumnMarker`: treat the rest as a JSON path (arbitrary depth) → routes to `QueryFilterJsonbPredicates` / `QueryFilterJsonTypes`.
   - Otherwise the base must be a reference column; uses `resolveReference` (reflective access to Exposed's `referee` field/getter) to build an `EXISTS` subquery against the referenced table. **Only one level of reference nesting is supported** (`ref.field`, not `a.b.c`).
5. **Value coercion** (string → column type). Built-in handling covers Int/Long/Short/Double/String/UUID/Boolean/enums/date/time (both `java.time` and `kotlinx.datetime`). Users can inject `ColumnMappersModule` to override or add types; custom mappers are tried **before** built-ins, in **reverse registration order** (last added first).
   - **Enums** (`jdbc/QueryFilterEnumSupport.kt`) cover all three Exposed storage forms: `EnumerationNameColumnType`, `EnumerationColumnType` (ordinal), and `CustomEnumerationColumnType`. Predicates never build the stored value by hand - they resolve the raw string to an enum constant and let the column type's own `notNullValueToDB` convert it. **Clients never send the stored representation**; a value is accepted as the constant `name` (exact match, case sensitive - deliberately *not* subject to `caseSensitiveStrings`, since this is an in-memory lookup, not a SQL comparison) or as its `ordinal`, identically for all three forms. Name wins first, so a constant literally named `"2"` still resolves by name.
   - `CustomEnumerationColumnType` exposes no enum class, and its `fromDb`/`toDb` lambdas are `invokedynamic` with fully erased signatures - reflecting on the lambda cannot recover the class, don't try. Instead the class comes from the Kotlin property declaring the column (`val status: Column<Status>` → `Status`), via the same `memberProperties` reflection `propertyToColumnMap()` uses; `Column<List<Status>>` is unwrapped one level deeper for array columns, and `Alias` columns resolve through `Alias.delegate`. The result is memoized per column-type instance (identity-keyed - `CustomEnumerationColumnType` doesn't override `equals`/`hashCode`) because the scan is far too slow to repeat per filter value. When the class cannot be found (a computed expression via `applyFilters(map)`, not a real column), the filter fails loudly rather than falling back to `fromDb` - resolving through `fromDb` would mean the client had to know the database representation, which defeats the library's purpose.
   - Comparison operators (`GT`/`GTE`/`LT`/`LTE`/`BETWEEN`) on enums are allowed **only** for `EnumerationColumnType`, where SQL ordering equals declaration order. The other two forms fail with an explanatory error rather than silently ordering by the stored representation.

## Filter semantics worth knowing

- **Case sensitivity**: `FilterOptions.caseSensitiveStrings` defaults to `false`. String comparisons (EQ/NEQ/IN/NOT_IN/CONTAINS/STARTS_WITH/ENDS_WITH/BETWEEN/GT/GTE/LT/LTE on `VarChar`/`Text`) are lower-cased on both sides by default. Changing this default is a breaking behavior change.
- **Operator constraints** (enforced in the JDBC predicates): LIKE-style operators only on strings; `BETWEEN` requires exactly two values and isn't supported for UUID/Boolean (for enums, only ordinal-stored columns — see above); `IN` with empty values is an error, `NOT_IN` with empty values is a no-op.
- **`excludeFields`** (`core/FilterFieldExclusion.kt`) has asymmetric semantics because it must never narrow results: removing a field inside an OR or NOT group drops the whole group, not just the predicate. Don't "fix" this to be symmetric.
- **DSL marker**: `FilterRequestBuilder` is annotated with a `@DslMarker` to prevent nested builder context leakage (see commit `a3f4b8c`). Keep it when refactoring builder types.

## Release process

- Version lives in `gradle.properties` (`version=...`).
- Tag `vX.Y.Z` triggers `.github/workflows/publish.yml` which builds, signs, and publishes to Maven Central. CI on `main` only runs `build -x test`.
- Update the compatibility table and install snippet in `README.md` when bumping the version.
- See `RELEASING.md` for required repository secrets and local publish command.
