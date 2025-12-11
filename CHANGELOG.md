# Changelog

All notable changes to this project will be documented in this file.

## [1.2.1] - 2025-12-11

- JDBC: add `ColumnSet` support for filters with automatic field name resolution
    - `applyFiltersOn` now accepts any `ColumnSet` (`Table`, `Join`, `Alias`, etc.) instead of only `Table`.
    - For `Table`: filter field names are matched against Kotlin property names (e.g., `warehouseId`).
    - For other `ColumnSet` types: filter field names are matched against SQL column names (e.g., `warehouse_id`).
    - New `applyFilters(columns, filterRequest)` overloads allow fully custom column mapping for maximum flexibility.
    - `Table.propertyToColumnMap()` is now public for advanced use cases.
- JDBC: add comprehensive test suite covering all filter operators and column types.
- Dependencies: bump Kotlin to `2.2.21`, Exposed to `1.0.0-rc-4`, Ktor to `3.3.2`.

## [1.2.0] - 2025-11-03

- JDBC: introduce pluggable value mapping for custom column types
    - New `ColumnMappersModule` and `ColumnValueMapper` let you convert raw filter values into column-typed values when
      standard mappings are insufficient (e.g., domain-specific value objects, custom enums, money, JSON-backed types).
    - New overload: `Query.applyFiltersOn(table, filterRequest, mappersModule)`; the existing signature remains
      unchanged.
- Dependencies: bump Exposed to `1.0.0-rc-2`, Ktor to `3.3.1`, JUnit to `5.10.2`, and SLF4J to `2.0.13`.

## [1.1.0] - 2025-09-18

- Core: add NOT combinator to `FilterCombinator` to support negation of groups.
- REST: parsing and normalization now preserve `NOT` groups even with a single child, ensuring negation is not lost.
- JDBC: implement `NOT` semantics as `NOT(AND(children))` at that level.
- Docs: update `README.md` and `rest/README.md` with `NOT` usage and examples.
- Example: add tests covering `NOT` over a single leaf and over an inner `OR` group.

## [1.0.8] - 2025-09-13

- REST: expose a raw-string parsing entrypoint
    - Added public `parseFilterRequestOrNull(raw: String)` in `:rest` to build a `FilterRequest` from JSON text
      when `ApplicationCall` is not available.
    - Returns null for empty input; invalid JSON continues to throw, preserving error surfacing semantics.
- REST: refactor `ApplicationCall.receiveFilterRequestOrNull()` to delegate to the new parser; no behavior changes.

## [1.0.7] - 2025-09-12

- Build: upgrade Gradle wrapper to `9.0.0`.
- Dependencies: Kotlin `2.2.20`, Exposed `1.0.0-rc-1`, Ktor `3.3.0`.
- JDBC: adapt to Exposed v1 API changes by removing `SqlExpressionBuilder` scope and using explicit operators; no
  behavior change intended.
- Docs: update README with 1.0.7 coordinates and refreshed compatibility table.

## [1.0.6] - 2025-09-01

- JDBC: treat empty input lists according to operator semantics
    - `IN []` and `BETWEEN []` now produce an always-false predicate (no rows match)
    - `NOT_IN []` is ignored (the predicate is dropped), matching expected "no-op" semantics
    - Updated behavior is covered by an integration test in the `example` module

## [1.0.5] - 2025-08-29

- JDBC: Added first-class date and timestamp filtering support
    - Date-only columns: Java `LocalDate` and `SQL DATE`, plus `kotlinx.datetime.LocalDate` backed columns
    - Date-time columns: `LocalDateTime`, `Instant` (including `kotlin.time.Instant`), and SQL `TIMESTAMP`
    - Supported operators: `EQ`, `NEQ`, `IN`, `NOT_IN`, `BETWEEN`, `GT`, `GTE`, `LT`, `LTE`, `IS_NULL`, `IS_NOT_NULL`
    - Parsing rules
        - Date: `YYYY-MM-DD`
        - Timestamp: `YYYY-MM-DDTHH:MM` or `YYYY-MM-DDTHH:MM:SS`; space instead of `T` is allowed; date-only for
          timestamp implies 00:00:00
- Example app: added `Events` table and `POST /events` endpoint demonstrating LocalDate and Instant filtering
- Docs: updated README with date/timestamp filtering docs and example

## [1.0.4] - 2025-08-26

- Added filtering by related entity fields via dot-path notation on reference columns.
    - Example: `"warehouseId.name": [{ "op": "STARTS_WITH", "value": "Cent" }]`.
    - Implemented using an `EXISTS` subquery against the referenced table.
    - Supports one-level nested paths (field.property) for `reference` columns.
- Added validation for unknown fields in filters.
    - Requests referencing non-existing fields now raise `IllegalArgumentException` (surface as HTTP 400 in the REST
      example) with a clear message, e.g. `Unknown filter field: nonExistingField`.
    - Attempting to use a nested path on a non-reference field yields a descriptive error.

## [1.0.3] - 2025-08-26

- Fixed handling of `EQ` and `IN` operators for `EntityID` columns (Exposed ORM):
    - Values are now correctly parsed according to the underlying identifier type (`Int`, `Long`, `Short`, `String`,
      `UUID`).
    - Comparisons are performed through the `EntityID` column itself, preventing ClassCastException and invalid SQL
      generation.

## [1.0.2] - 2025-08-26

- Filters now match by Kotlin `Table` property names (e.g., `warehouseId`) instead of DB column names (e.g.,
  `warehouse_id`).
    - Keeps APIs decoupled from physical schema naming.
    - Backward-compatible: DB column names are still accepted as a fallback.
- `:jdbc`: added `kotlin-reflect` dependency and reflection-based mapping of `Table` properties to `Column`s.
- `:example`: added `Warehouses` and `Products` tables with a UUID foreign key, endpoint `POST /products`, and tests
  demonstrating filtering by `warehouseId`.
- `README`: documented the “Field naming in filters” behavior with examples.

## [1.0.1] - 2025-08-24

- Switched Maven group to `ua.wwind.exposed-filters` for clearer namespace on Maven Central. No code or API changes.

## [1.0.0] - 2025-08-24

- Initial public release: `core`, `jdbc`, and `rest` modules.
