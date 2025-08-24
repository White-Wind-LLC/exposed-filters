# Exposed ORM Universal Filters

[![Maven Central](https://img.shields.io/maven-central/v/ua.wwind.exposed-filters/exposed-filters-core)](https://central.sonatype.com/artifact/ua.wwind.exposed-filters/exposed-filters-core)

Type-safe, normalized filter model and utilities for building dynamic queries with JetBrains Exposed and Ktor.

- Accept filters as JSON, parse them into a normalized filter tree, and safely apply them to Exposed queries.
- Strong typing and SQL injection safety via Exposed DSL.
- Flexible: use flat or nested (tree) filter structures with AND/OR combinators.

- **Modules**:
    - `core` – domain model (`FilterOperator`, `FilterNode`, normalization utils)
    - `jdbc` – translation of filters to Exposed DSL predicates
    - `rest` – Ktor helpers to parse HTTP JSON bodies into `FilterRequest`
- **Artifacts** (Maven Central):
    - `ua.wwind.exposed-filters:exposed-filters-core:<version>`
    - `ua.wwind.exposed-filters:exposed-filters-jdbc:<version>`
    - `ua.wwind.exposed-filters:exposed-filters-rest:<version>`

## Installation

Prerequisites: Kotlin 2.2.0, repository `mavenCentral()`.

```kotlin
dependencies {
    implementation("ua.wwind.exposed-filters:exposed-filters-core:1.0.1")
    implementation("ua.wwind.exposed-filters:exposed-filters-jdbc:1.0.1")
    implementation("ua.wwind.exposed-filters:exposed-filters-rest:1.0.1")
}
```

## Compatibility

| Library version | Kotlin    | Ktor      | Exposed          |
|-----------------|-----------|-----------|------------------|
| <= 1.0.1        | \>= 2.2.0 | \>= 3.2.3 | \>= 1.0.0-beta-1 |

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

Notes:

- `filters` is a map of field -> list of conditions.
- Each condition has `op` and either `value` or `values` (depending on operator).
- `combinator` can be `AND` or `OR`. Omitted combinators default to `AND`.
- Empty/unknown parts are ignored; if nothing remains after normalization, no filter is applied.

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

Operator constraints:

- `LIKE`-style operators only for string columns.
- `BETWEEN` requires exactly two values; not supported for UUID/Enum/Boolean.
- `IN`/`NOT_IN` requires non-empty values.

## Why use this

- Clear separation: request format → normalized model → SQL predicates.
- Safer queries by leveraging Exposed DSL.
- Reusable across endpoints; supports both simple and complex trees of conditions.

## CI and Release

- CI builds on pushes and PRs against `main`.
- Releases are published to Maven Central on pushing tags `v*`.

## License

Apache License 2.0 — © 2025 White Wind LLC
