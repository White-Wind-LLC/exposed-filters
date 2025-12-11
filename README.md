# Exposed ORM Universal Filters

[![Maven Central](https://img.shields.io/maven-central/v/ua.wwind.exposed-filters/exposed-filters-core)](https://central.sonatype.com/artifact/ua.wwind.exposed-filters/exposed-filters-core)

Type-safe, normalized filter model and utilities for building dynamic queries with JetBrains Exposed and Ktor.

- Accept filters as JSON, parse them into a normalized filter tree, and safely apply them to Exposed queries.
- Strong typing and SQL injection safety via Exposed DSL.
- Flexible: use flat or nested (tree) filter structures with AND/OR/NOT combinators.

- **Modules**:
    - `core` – domain model (`FilterOperator`, `FilterNode`, normalization utils)
    - `jdbc` – translation of filters to Exposed DSL predicates
    - `rest` – Ktor helpers to parse HTTP JSON bodies into `FilterRequest`
- **Artifacts** (Maven Central):
    - `ua.wwind.exposed-filters:exposed-filters-core:<version>`
    - `ua.wwind.exposed-filters:exposed-filters-jdbc:<version>`
    - `ua.wwind.exposed-filters:exposed-filters-rest:<version>`

## Installation

Prerequisites: Kotlin 2.2.20, repository `mavenCentral()`.

```kotlin
dependencies {
    implementation("ua.wwind.exposed-filters:exposed-filters-core:1.2.2")
    implementation("ua.wwind.exposed-filters:exposed-filters-jdbc:1.2.2")
    implementation("ua.wwind.exposed-filters:exposed-filters-rest:1.2.2")
}
```

## Compatibility

| Library version | Kotlin | Ktor  | Exposed      |
|-----------------|--------|-------|--------------|
| 1.2.1           | 2.2.21 | 3.3.2 | 1.0.0-rc-4   |
| 1.2.0           | 2.2.20 | 3.3.1 | 1.0.0-rc-2   |
| 1.0.7           | 2.2.20 | 3.3.0 | 1.0.0-rc-1   |
| 1.0.1           | 2.2.0  | 3.2.3 | 1.0.0-beta-1 |

## Quick start

### 1) Define your table (Exposed)

```kotlin
object Users : Table("users") {
    val id: Column<Int> = integer("id").autoIncrement()
    val name: Column<String> = varchar("name", 100)
    val age: Column<Int> = integer("age")
    override val primaryKey = PrimaryKey(id)
}
```

### 2) Ktor endpoint: receive filters and apply to query

```kotlin
routing {
    post("/users") {
        val filter = call.receiveFilterRequestOrNull()
        val result = transaction {
            Users
                .selectAll()
                .applyFiltersOn(Users, filter)
                .map { row ->
                    mapOf(
                        "id" to row[Users.id],
                        "name" to row[Users.name],
                        "age" to row[Users.age]
                    )
                }
        }
        call.respond(result)
    }
}
```

## Field naming in filters

- Always use the Kotlin `Table` property name (usually camelCase) in your JSON filters, not the physical DB column name.
- This decouples your API from database naming conventions.

Example with a UUID foreign key:

```kotlin
object Warehouses : Table("warehouses") {
    val id: Column<UUID> = uuid("id")
}

object Products : Table("products") {
    val id: Column<Int> = integer("id").autoIncrement()
    val warehouseId: Column<UUID> = reference("warehouse_id", Warehouses.id) // property name vs DB column
}
```

Filter by property `warehouseId` (maps to DB `warehouse_id`):

```json
{
  "filters": {
    "warehouseId": [ { "op": "EQ", "value": "11111111-1111-1111-1111-111111111111" } ]
  }
}
```

Notes:

- Property names are resolved via reflection. This also works for enums, UUIDs, booleans, numbers, and strings.

## Filtering by related entities (references)

- You can filter by a field of a related entity using dot-paths on reference columns: `referenceField.nestedField`.
- Under the hood, this is implemented via an `EXISTS` subquery against the referenced table.
- Currently supports one-level nesting on reference columns.

Example: filter products by warehouse name prefix

```json
{
  "filters": {
    "warehouseId.name": [ { "op": "STARTS_WITH", "value": "Cent" } ]
  }
}
```

Constraints:

- Nested paths are allowed only on reference columns; using `field.subField` on a non-reference column raises an error.
- Operator constraints still apply based on the target column type (e.g., `CONTAINS`/`STARTS_WITH` only for strings).
- Path nesting for references is limited to one level (e.g., `warehouseId.name`). Multi-hop like `a.b.c` is not
  supported.

## Validation and errors

- Requests that reference unknown fields will fail fast with a clear error message, e.g. `Unknown filter field: foo`.
- In the sample Ktor app, such cases are mapped to HTTP 400 Bad Request via a global `StatusPages` handler.

## JSON request format

You can send a flat structure or a nested tree. Both parse into a `FilterRequest`.

### Flat (single level)

```json
{
  "combinator": "AND",
  "filters": {
    "name": [{ "op": "CONTAINS", "value": "Al" }],
    "age":  [{ "op": "GTE", "value": "18" }]
  }
}
```

### Tree (nested groups)

```json
{
  "combinator": "OR",
  "children": [
    { "filters": { "age": [{ "op": "BETWEEN", "values": ["18", "25"] }] } },
    { "filters": { "name": [{ "op": "STARTS_WITH", "value": "Da" }] } }
  ]
}
```

### Multi-level nested groups (AND/OR/NOT)

Arbitrary depth is supported for grouping with `AND`/`OR`/`NOT` combinators. You can nest groups within `children` to
any
level to express complex logic.

```json
{
  "combinator": "AND",
  "children": [
    {
      "filters": {
        "status": [{ "op": "IN", "values": ["ACTIVE", "PENDING"] }]
      }
    },
    {
      "combinator": "OR",
      "children": [
        { "filters": { "name": [{ "op": "CONTAINS", "value": "Ann" }] } },
        {
          "combinator": "AND",
          "children": [
            { "filters": { "age": [{ "op": "GTE", "value": "21" }] } },
            { "filters": { "age": [{ "op": "LT", "value": "30" }] } }
          ]
        }
      ]
    }
  ]
}
```

### NOT groups

Negate a group by using `combinator: "NOT"`. All children at that level are combined with AND and wrapped in NOT.

```json
{
  "combinator": "NOT",
  "children": [
    {
      "filters": {
        "status": [
          {
            "op": "EQ",
            "value": "DELETED"
          }
        ]
      }
    },
    {
      "filters": {
        "archived": [
          {
            "op": "EQ",
            "value": "true"
          }
        ]
      }
    }
  ]
}
```

Notes:

- Nested `children` can be combined with `AND` or `OR` at each level. This supports many levels of nesting.
- Single-child groups are normalized away; empty groups are ignored.
- A NOT group with a single child is preserved (not flattened) and negates that child's conjunction.
- This is independent from nested field paths for references, which remain limited to one level (see next section).

## Supported operators

- Equality: `EQ`, `NEQ`
- String search: `CONTAINS`, `STARTS_WITH`, `ENDS_WITH`
- Sets: `IN`, `NOT_IN`
- Ranges and comparisons: `BETWEEN`, `GT`, `GTE`, `LT`, `LTE`
- Nullability: `IS_NULL`, `IS_NOT_NULL`

## Type handling (JDBC adapter)

Automatic conversion from JSON strings to column types:

- Int, Long, Short, Double
- String (`VarChar`, `Text`) with `LIKE` support for the string operators
- UUID
- Boolean (`toBooleanStrict()`: only "true"/"false")
- Enums (by enum constant `name`)
- Date and time
    - Date-only (LocalDate): supports both Java `java.time.LocalDate` and `kotlinx.datetime.LocalDate` backed columns
    - Timestamp/DateTime: supports `LocalDateTime`, `Instant` (including `kotlin.time.Instant`), and SQL `TIMESTAMP`
    - Accepted formats in JSON
        - Date: `YYYY-MM-DD`
        - Timestamp: ISO date-time with optional seconds, `YYYY-MM-DDTHH:MM` or `YYYY-MM-DDTHH:MM:SS` (a space instead
          of `T` is allowed). A date-only value for a timestamp column is treated as start of day (00:00:00)

Operator constraints:

- `LIKE`-style operators only for string columns.
- `BETWEEN` requires exactly two values; not supported for UUID/Enum/Boolean.
- `IN` requires non-empty values; `NOT_IN` with an empty `values` array is treated as a no-op (ignored).
- Date/time operators: `EQ`, `IN`, `BETWEEN`, `GT`, `GTE`, `LT`, `LTE`, `IS_NULL`, `IS_NOT_NULL` are supported for date
  and timestamp columns.

## Excluding fields from filters

When building complex queries with joins, subqueries, or multi-level data aggregation, you may need to apply the same
user filter at different query levels. However, not all fields may be available at every level — some columns might
only exist in the final result set, or certain fields should not constrain intermediate queries.

The `excludeFields` utility allows you to safely remove specific field conditions from a `FilterRequest` while
preserving correct filtering semantics. The key guarantee is that **excluding fields will never narrow the result
set** — it may only broaden it. This is safe because you can apply the full, unmodified filter on the final result.

### How it works

The behavior depends on the logical combinator:

- **AND**: Removing a field broadens the result set (safe). The remaining conditions still apply.
- **OR**: Removing a field from an OR group would narrow results (records matching only the removed condition would be
  lost). Therefore, **the entire OR group is removed** if any direct leaf contains an excluded field.
- **NOT**: Due to De Morgan's law, removing fields inside a NOT group can narrow results. Therefore, **the entire NOT
  group is removed** if any child at any depth contains an excluded field.

### Usage

```kotlin
val filter: FilterRequest = ...

// Exclude specific fields
val partialFilterForTable1 = filter.excludeFields("profileId")
val partialFilterForTable2 = filter.excludeFields("userId")

// Use partial filter for intermediate query (missing some columns)
val intermediateQuery1 = SomeTable1
        .selectAll()
        .applyFiltersOn(SomeTable1, partialFilterForTable1)

val intermediateQuery2 = SomeTable2
        .selectAll()
        .applyFiltersOn(SomeTable2, partialFilterForTable2)


// Apply full filter on the final result
val finalQuery =
    (intermediateQuery1 union intermediateQuery2)
        .selectAll()

finalQuery
        .applyFiltersOn(finalQuery, filter)
```

### Example scenario

Imagine a query that joins `Orders` with `OrderItems` and computes a `totalAmount` field. The user sends a filter:

```json
{
  "combinator": "AND",
  "filters": {
    "status": [{ "op": "EQ", "value": "COMPLETED" }],
    "totalAmount": [{ "op": "GTE", "value": "1000" }]
  }
}
```

The `totalAmount` field only exists after aggregation, so you cannot apply it to the base `Orders` query. Using
`excludeFields("totalAmount")` removes that condition for the intermediate query while keeping the `status` filter.
After aggregation, you apply the full filter including `totalAmount` on the final dataset.

## Custom column type mappers

For custom column types not covered by built-in support, you can provide value mappers using `ColumnMappersModule`. This
allows you to register multiple mappers and apply them to filter operations.

### Using the DSL

Create a mappers module with the `columnMappers` DSL function:

```kotlin
val customMappers = columnMappers {
    // Add a mapper using a lambda
    mapper { columnType, rawValue ->
        when {
            columnType is BigDecimalColumnType -> BigDecimal(rawValue)
            columnType.javaClass.simpleName == "JsonColumnType" -> Json.parse(rawValue)
            else -> null // return null if this mapper doesn't handle the type
        }
    }
    
    // Add another mapper for different types
    mapper { columnType, rawValue ->
        if (columnType is MyCustomType) {
            parseMyCustomValue(rawValue)
        } else null
    }
    
    // Or add a pre-built mapper object
    addMapper(MyCustomMapper())
}
```

### Applying mappers to queries

Pass the mappers module as the third argument to `applyFiltersOn`:

```kotlin
routing {
    post("/products") {
        val filter = call.receiveFilterRequestOrNull()
        val result = transaction {
            Products
                .selectAll()
                .applyFiltersOn(Products, filter, customMappers)
                .map { /* ... */ }
        }
        call.respond(result)
    }
}
```

### How mappers work

- **Custom mappers are tried first**: Mappers registered in the module are tried in **reverse order** (last registered
  first), giving priority to the most recently added mappers.
- **Built-in mappers as fallback**: If no custom mapper handles the type (returns `null`), the library falls back to
  built-in type handling for standard types (Int, Long, String, UUID, Date, etc.).
- **Error on unsupported types**: If neither custom nor built-in mappers handle the type, an error is thrown.

This design allows you to override built-in behavior for standard types if needed, while still providing sensible
defaults.

### Implementing a reusable mapper

You can implement the `ColumnValueMapper` interface for reusable mappers:

```kotlin
class BigDecimalMapper : ColumnValueMapper {
    override fun <T : Any> map(columnType: IColumnType<T>, raw: String): T? {
        return if (columnType is BigDecimalColumnType) {
            @Suppress("UNCHECKED_CAST")
            BigDecimal(raw) as T
        } else null
    }
}

// Usage:
val mappers = columnMappers {
    addMapper(BigDecimalMapper())
}
```

### Example: JSON column support

```kotlin
val mappers = columnMappers {
    mapper { columnType, rawValue ->
        if (columnType.javaClass.simpleName.contains("Json")) {
            // Parse JSON string into your JSON object type
            kotlinx.serialization.json.Json.parseToJsonElement(rawValue)
        } else null
    }
}

// Apply to query
MyTable
    .selectAll()
    .applyFiltersOn(MyTable, filterRequest, mappers)
```

## Example: filtering by date and timestamp

The `example` module contains an `Events` table demonstrating both a date-only field and a timestamp field, along with a
`POST /events` endpoint that accepts filters:

```json
{
  "filters": {
    "day": [ { "op": "EQ", "value": "2024-01-01" } ],
    "occurredAt": [ { "op": "GTE", "value": "2024-07-01T00:00:00" } ]
  }
}
```

## Why use this

- Clear separation: request format → normalized model → SQL predicates.
- Safer queries by leveraging Exposed DSL.
- Reusable across endpoints; supports both simple and complex trees of conditions.

## CI and Release

- CI builds on pushes and PRs against `main`.
- Releases are published to Maven Central on pushing tags `v*`.

## License

Apache License 2.0 — © 2025 White Wind LLC
