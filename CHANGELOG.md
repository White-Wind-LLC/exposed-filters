# Changelog

All notable changes to this project will be documented in this file.

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
