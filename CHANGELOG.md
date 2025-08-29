# Changelog

All notable changes to this project will be documented in this file.

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
