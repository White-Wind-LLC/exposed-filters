# `IN_HIERARCHY` operator for tree columns — design

Issue: [#22](https://github.com/White-Wind-LLC/exposed-filters/issues/22)

## Goal

Many tables model a tree through a nullable self-reference (`parentId` → `id`): folders, categories,
groups. A common filter is "rows whose category is X **or any descendant of X**". Today the caller
resolves all descendant ids up front and sends a large `IN` list, which goes stale as the tree changes
and cannot be stored as part of a filter.

`IN_HIERARCHY` makes that a single, storable predicate: the values are one or more node ids, and a row
matches when the filtered column equals one of them or a descendant of one of them. The database
resolves the descendants at query time through a recursive CTE.

## Success criteria

- `IN_HIERARCHY` on a tree column matches the given nodes and all their descendants.
- Works inside AND / OR / NOT groups and on nested reference paths, and survives a JSON round-trip
  (flat and tree bodies) with all values intact.
- Missing or ambiguous hierarchy information fails with a clear error naming the field.
- Tested on PostgreSQL and H2; README and CHANGELOG updated.

## Non-goals

- No `NOT_IN_HIERARCHY` operator — a `NOT` group expresses it.
- No ancestor search ("X or any ancestor of X").
- No dialect-specific rendering. Oracle (`CONNECT BY`, no `RECURSIVE` keyword) and SQL Server (no CTE
  inside a subquery) are not supported. The emitted SQL is standard and works on PostgreSQL, H2,
  MySQL 8+ and SQLite; only PostgreSQL and H2 are tested.
- No support on array columns, JSON paths or computed expressions.

## Public API

### `core`

```kotlin
public enum class FilterOperator {
    EQ, NEQ, CONTAINS, STARTS_WITH, ENDS_WITH, IN, NOT_IN, BETWEEN, GT, GTE, LT, LTE, IS_NULL, IS_NOT_NULL,
    IN_HIERARCHY, // appended last: existing ordinals stay unchanged
}
```

`FilterRequestBuilder` gains, next to `inList`:

```kotlin
public fun inHierarchy(field: String, values: Iterable<Any>)
public fun inHierarchy(field: String, vararg values: Any)
```

Wire format is the same as `IN`: `{"op": "IN_HIERARCHY", "values": ["1", "2"]}`.

`IN_HIERARCHY` is a list-valued operator everywhere the code distinguishes list operators from
single-value ones. Two places must list it next to `IN`, otherwise every value but the first is
silently dropped:

- `core/FilterRequestSerialization.kt` `toConditionDto` — serializes `values`, not `value`.
- `rest/FilterExtraction.kt` `buildLeafOrNull` — reads `values`, not `value`.

### `jdbc`

```kotlin
/**
 * Describes the tree a filtered column points into.
 *
 * @property idColumn The node id column. The tree's table is `idColumn.table`.
 * @property parentColumn The nullable link to the parent node's [idColumn]; must be on the same table.
 */
public data class HierarchyInfo(
    public val idColumn: Column<*>,
    public val parentColumn: Column<*>,
)
```

The table is derived from `idColumn` rather than passed separately, so a mismatched table cannot be
expressed. `parentColumn.table` must be `idColumn.table` (checked with `require` when the predicate is
built).

`FilterOptions` gains a last parameter:

```kotlin
public val hierarchyResolver: ((Column<*>) -> HierarchyInfo?)? = null
```

Adding it changes the constructor arity: **not binary-compatible** with 1.13.0, callers must recompile
(same note as 1.13.0).

## Hierarchy resolution

Given the filtered column `col`, first unwrap aliases: `original = col.originalColumn()` (same as
reference paths — a column of a table `Alias` or a `QueryAlias` is a clone without `referee`).

1. **Resolver.** If `options.hierarchyResolver` is set and returns a non-null `HierarchyInfo` for
   `original`, use it. The resolver is consulted first so the caller can override auto-detection —
   for a table that has a physical self-reference which is not the hierarchy, or for a logical-only
   parent link with no foreign key.
2. **Auto-detection.** Otherwise:
   - target id: `resolveReference(original)?.referencedIdColumn ?: original`
     (an FK into the tree points at the tree's id; a column without a reference is taken as the tree's
     id itself);
   - parent: the columns of `targetId.table` whose `resolveReference(...)?.referencedIdColumn` is
     `targetId`. Exactly one → `HierarchyInfo(targetId, thatColumn)`.
   - Zero → error: `IN_HIERARCHY on field '<field>': no column of <table> references <table>.<id>;
     declare the tree with FilterOptions.hierarchyResolver`.
   - Two or more → error: `IN_HIERARCHY on field '<field>': <table> has several self-references
     (<a>, <b>); declare the tree with FilterOptions.hierarchyResolver`.

Consequences worth documenting:

- `Products.categoryId IN_HIERARCHY 5` → products in category 5 or any of its descendants.
- `Categories.id IN_HIERARCHY 5` → category 5 and its descendants.
- `Categories.parentId IN_HIERARCHY 5` → strict descendants of 5 (their parent is 5 or a descendant).

## SQL rendering

New file `jdbc/QueryFilterHierarchy.kt` holds `HierarchyInfo`, the resolution above, and an internal
`HierarchyInOp : Op<Boolean>` whose `toQueryBuilder` renders:

```sql
<col> IN (
  WITH RECURSIVE ef_tree(id) AS (
    SELECT t.id FROM t WHERE <anchor>
    UNION
    SELECT ef_child.id FROM t ef_child INNER JOIN ef_tree ON ef_child.parent_id = ef_tree.id
  )
  SELECT ef_tree.id FROM ef_tree
)
```

- `<col>` is the filtered expression as given (the alias clone, not the original — the outer query
  sees the clone).
- `<anchor>` is `inListValue(idColumn, values, fieldName)`: the existing `IN` builder, so value
  coercion, custom `ColumnMappersModule` mappers, `EntityID` ids and case-insensitive string ids
  (`caseSensitiveStrings`, `normalizedStringFields`) behave exactly as for `IN`. Parameters are bound,
  never inlined.
- `t` and `ef_child` are rendered through Exposed (`table.describe`, `table.alias("ef_child")[column]`),
  so identifiers are quoted per dialect.
- `t` inside the subquery binds to the inner `FROM`, even when the outer query reads the same table.
  The fixed CTE name `ef_tree` cannot collide: each `IN_HIERARCHY` gets its own subquery scope.
- `UNION` (not `UNION ALL`) deduplicates, which also terminates on cyclic data.
- A `NULL` value in `<col>` never matches; under `NOT` it also drops out (`NOT (NULL IN …)` is `NULL`),
  the same as `NOT_IN` today. Documented, not special-cased.

### Dispatch

In `predicateFor` (`QueryFilterPredicatesCore.kt`), before the array-column branch:

- `IN_HIERARCHY` with empty `values` → `Op.FALSE` (same as `IN`);
- otherwise `hierarchyPredicate(expr, filter.values, fieldName)`.

`hierarchyPredicate` requires `expr` to be a `Column<*>`, else
`IN_HIERARCHY requires a table column: '<field>'`.

The exhaustive `when`s over `FilterOperator` in `QueryFilterJsonbPredicates.kt` and
`QueryFilterArrayPredicates.kt` get an explicit error: `IN_HIERARCHY is not supported for JSON
fields: '<field>'` / `... array fields ...`.

Nested reference paths need no extra code: `predicateForField` already calls `predicateFor` with the
target column inside the `EXISTS` subquery (`product.categoryId IN_HIERARCHY 5` on `Orders`). With a
`nestedFieldResolver` projection whose expression is not a column, the "requires a table column"
error applies.

`WHERE`/`HAVING` splitting is unaffected: a column is never an aggregate.

## Tests

`jdbc` — `QueryFilterHierarchyContract` (abstract, shared cases) with `QueryFilterHierarchyH2Test`
and `QueryFilterHierarchyPostgresTest` (Testcontainers), following the `CompositeSource*` pattern.
Fixture: a category tree with two roots and depth ≥ 3, products pointing into it (one with a `NULL`
category), orders referencing products. Cases:

- filter on the tree's own id; on an FK into the tree; on `parentId` (strict descendants);
- several roots in `values`; a leaf; an id that does not exist (empty result); empty `values` (empty
  result);
- inside an OR group and a NOT group (NULL-category product excluded under NOT);
- nested reference path `product.categoryId` from orders;
- `hierarchyResolver` for a logical-only parent column (no FK); resolver overriding auto-detection on
  a table with two self-references;
- cyclic data terminates;
- `IntIdTable` tree with `EntityID` ids (`reference("parent", Self)`);
- a column of a table `Alias`;
- errors: no hierarchy, ambiguous self-references, `parentColumn` on another table, computed
  expression via `applyFilters(map)`, JSON path, array column.

`core` — `inHierarchy` builder (both overloads); JSON round-trip preserving all values.

`rest` — flat and tree bodies with `"op": "IN_HIERARCHY", "values": [...]` parse to a `FieldFilter`
with every value.

## Documentation

- README: add `IN_HIERARCHY` to the operator lists ("Features", "All supported operators",
  "Supported operators"); new section "Filtering trees (`IN_HIERARCHY`)" covering auto-detection, the
  resolver, the three column cases above, NULL behavior and supported dialects.
- CHANGELOG: `Unreleased` entry referencing #22, with the binary-compatibility note for
  `FilterOptions`.
- CLAUDE.md: a short point under "Architecture / data flow" on hierarchy resolution order (resolver
  first, then auto-detection) and the CTE rendering.

## Files touched

- `core/src/main/.../FiltersCore.kt` — enum value.
- `core/src/main/.../FilterRequestBuilder.kt` — `inHierarchy`.
- `core/src/main/.../FilterRequestSerialization.kt` — list-valued handling.
- `rest/src/main/.../FilterExtraction.kt` — list-valued handling.
- `jdbc/src/main/.../FilterOptions.kt` — `hierarchyResolver` + KDoc.
- `jdbc/src/main/.../QueryFilterHierarchy.kt` — new.
- `jdbc/src/main/.../QueryFilterPredicatesCore.kt` — dispatch.
- `jdbc/src/main/.../QueryFilterJsonbPredicates.kt`, `QueryFilterArrayPredicates.kt` — errors.
- Tests in `core`, `rest`, `jdbc`; `README.md`, `CHANGELOG.md`, `CLAUDE.md`.
