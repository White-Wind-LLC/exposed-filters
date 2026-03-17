<!--firebender-plan
name: jsonb-dot-path-filters
overview: Add dot-path filtering for JSON/JSONB columns alongside existing reference-field dot-path support, with strict typed value inference and cross-dialect behavior via Exposed JSON APIs. Expand test coverage in the main test suite with H2 and PostgreSQL Testcontainers validation.
todos:
  - id: deps-json-testcontainers
    content: "Add exposed-json and Testcontainers dependencies in version catalog and jdbc module build config."
  - id: json-path-routing
    content: "Extend dot-path resolution to branch between JSON-path handling and existing reference nested handling."
  - id: strict-type-inference
    content: "Implement strict JSON value type inference and operator mapping with clear validation errors."
  - id: h2-json-tests
    content: "Add/extend H2-based tests for JSON dot-path behavior and edge-case validation."
  - id: pg-testcontainers-tests
    content: "Add PostgreSQL Testcontainers integration tests in default test task for critical JSONB scenarios."
  - id: regression-check
    content: "Run jdbc tests and verify no regression in existing reference nested filtering behavior."
-->

# JSON/JSONB Dot-Path Filtering Plan

## Summary

Implement support for filter fields like `jsonColumn.a.b.c` so they are resolved as JSON path filters when the base
field is a JSON/JSONB column, while preserving current reference-column behavior (`referenceField.nestedField`).

The implementation uses Exposed JSON extraction APIs (`exposed-json`) and strict type inference for values to support
the full existing operator set.

## Scope and behavior

- Keep existing behavior for non-dot fields unchanged.
- Keep existing reference nested filters unchanged for reference columns (single-hop as today).
- Add JSON path resolution for dot fields when the base expression column type is JSON/JSONB.
- Support arbitrary JSON path depth after the base field (e.g., `payload.user.profile.age`).
- `IS_NULL` / `IS_NOT_NULL`: missing JSON key is treated as `NULL` (`IS_NULL = true`, `IS_NOT_NULL = false`).
- `CONTAINS` / `STARTS_WITH` / `ENDS_WITH`: allowed only for JSON string values.
- Cross-dialect target: rely on Exposed `jsonExtract` implementation for supported dialects.

## Public API / interface impact

- No new public API methods.
- Existing filter field syntax is extended:
    - Existing: `referenceField.nestedField` (reference traversal)
    - New: `jsonField.path.to.value` (JSON traversal)
- Error behavior additions:
    - Invalid JSON path (`a.`, `a..b`) -> clear `IllegalArgumentException`.
    - Type-inference mismatch in `IN/NOT_IN/BETWEEN` -> clear `IllegalArgumentException`.
    - String operators on non-string inferred values -> clear `IllegalArgumentException`.

## Implementation design

- Add dependency `org.jetbrains.exposed:exposed-json` in `jdbc` module.
- In `jdbc/src/main/kotlin/ua/wwind/exposed/filters/jdbc/QueryFilterExtensions.kt`:
    - Extend `predicateForField(...)` routing:
        - If field contains dot:
            - resolve `baseName` + `pathSegments`.
            - if base expression is JSON/JSONB column -> build JSON-path predicate.
            - else fallback to current reference nested logic.
    - Introduce JSON-path predicate builder that:
        - extracts scalar value using Exposed JSON `extract(..., toScalar = true)` from base column by path segments.
        - infers a strict value type family for each operator input.
        - applies typed predicate operators against extracted scalar expression.
- Add strict inference model (internal only) for raw filter values:
    - Families: `NUMBER`, `BOOLEAN`, `DATE`, `DATETIME`, `STRING`.
    - Inference rules:
        - `true`/`false` -> boolean
        - numeric parse success -> number
        - ISO date parse success -> date
        - ISO datetime/timestamp parse success -> datetime
        - otherwise -> string
    - `IN/NOT_IN`: all values must infer to same family, else error.
    - `BETWEEN`: exactly two values and same family, else error.
- Operator mapping for JSON path:
    - `EQ/NEQ/IN/NOT_IN/BETWEEN/GT/GTE/LT/LTE`: typed by inferred family.
    - `CONTAINS/STARTS_WITH/ENDS_WITH`: only `STRING` family.
    - `IS_NULL/IS_NOT_NULL`: null checks on extracted scalar expression.

## Edge cases and failure modes

- Empty path segment (`a..b`, trailing dot) -> reject.
- Unknown base field -> existing unknown-field error.
- Dot-path on non-reference and non-JSON columns -> descriptive error.
- Mixed-family values in set/range operators -> descriptive error.
- Empty arrays semantics remain unchanged:
    - `IN`/`BETWEEN` with empty values -> false predicate.
    - `NOT_IN` with empty values -> true predicate.

## Test plan

### Unit/integration tests in `jdbc/src/test/kotlin/ua/wwind/exposed/filters/jdbc/QueryFilterExtensionsTest.kt`

- Add JSON table(s) and fixtures for H2 JSON support (if available in current H2 mode).
- Validate:
    - dot-path extraction with depth > 1
    - typed operators across families (`NUMBER`, `BOOLEAN`, `DATE`, `DATETIME`, `STRING`)
    - `IS_NULL` behavior for missing keys
    - string-only operator enforcement
    - type mismatch errors for `IN/BETWEEN`
    - coexistence with reference nested fields (no regression)

### PostgreSQL Testcontainers integration in main `test`

- Add PostgreSQL-backed integration test class in `jdbc` tests (executed by default under `test`).
- Validate critical JSONB semantics:
    - deep path (`a.b.c`) equality and comparison
    - null/missing behavior for `IS_NULL`/`IS_NOT_NULL`
    - compatibility with existing reference nested path filtering in same query

## Build/config updates

- `gradle/libs.versions.toml`:
    - add `exposed-json` library entry with version ref `exposed`.
    - add Testcontainers dependencies for PostgreSQL tests.
- `jdbc/build.gradle.kts`:
    - add `implementation(libs.exposed.json)`.
    - add `testImplementation` for Testcontainers PostgreSQL + JUnit integration.

## Assumptions and defaults

- JSON-path behavior is enabled only when the base field expression is an actual JSON/JSONB Exposed column type.
- Cross-dialect support is delegated to Exposed JSON function providers; dialect-specific SQL is not handwritten in this
  library.
- PostgreSQL integration tests in default `test` are accepted for CI runtime increase.
- No README/CHANGELOG updates in this task unless explicitly requested.
