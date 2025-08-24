# Exposed ORM Universal Filters

Type-safe, normalized filter model and utilities for building dynamic queries with JetBrains Exposed and Ktor.

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
    implementation("ua.wwind.exposed-filters:exposed-filters-core:1.0.0")
    implementation("ua.wwind.exposed-filters:exposed-filters-jdbc:1.0.0")
    implementation("ua.wwind.exposed-filters:exposed-filters-rest:1.0.0")
}
```

## Compatibility

| Library version | Kotlin    | Ktor      | Exposed          |
|-----------------|-----------|-----------|------------------|
| <= 1.0.1        | \>= 2.2.0 | \>= 3.2.3 | \>= 1.0.0-beta-1 |

## Quick start

- **REST → Core**: parse request into filters
```kotlin
val filter: FilterRequest? = call.receiveFilterRequestOrNull()
if (filter != null && !filter.isEmpty) {
    // pass filter.root to JDBC layer
}
```

- **Core → JDBC (Exposed DSL)**: apply filters to a query
```kotlin
fun findAll(filters: FilterRequest?): List<YourEntity> {
    return YourTable.selectAll().applyFiltersOn(YourTable, filters).map { row ->
        row.toYourEntity()
    }
}
```

See more in `rest/README.md`.

## CI and Release

- CI builds on pushes and PRs against `main`.
- Releases are published to Maven Central on pushing tags `v*`.

## License

Apache License 2.0 — © 2025 White Wind LLC
