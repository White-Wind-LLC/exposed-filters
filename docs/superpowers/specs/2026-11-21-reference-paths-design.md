# Reference paths of any depth — design

Issues: [#37](https://github.com/White-Wind-LLC/exposed-filters/issues/37) — reference paths of any depth;
[#38](https://github.com/White-Wind-LLC/exposed-filters/issues/38) — a hop through a reference with several targets.

## Why

A filter reaches a field of a related row through one reference: `warehouseId.name` becomes
`EXISTS (SELECT … FROM warehouses WHERE warehouses.id = products.warehouse_id AND …)`. A second dot fails with
"Unknown nested field", and so does a JSON column behind a reference. Applications that let a user pick a field of a
related record from a tree of references need paths of any depth (`orderId.customerId.name`). Not every reference has
one target either: a row may store a document id next to a document type, and the type decides the table the id points
into.

What consumers need besides the walk:

- to check every hop of a path (read access to each table, an allow-list of fields per table, a depth limit) by the
  library's own resolution rules, not by re-implementing them — including outside a query, e.g. to report which stored
  filters a new rule would refuse;
- one exception type for a path that cannot be resolved, naming the segment that failed, so it maps to one answer;
- a depth limit they set.

## Goals

1. A path resolves through any number of references; each hop is one more nested `EXISTS`.
2. A table met twice on a path is aliased, so the inner occurrence never shadows the outer one.
3. Resolvers work per hop.
4. A hop through a reference with several targets names the target it takes:
   `documentId[RECEIPT].senderId.name`.
5. The resolved path is public as a value; predicate building consumes that same value.
6. Every path failure is a `FilterPathException` naming the field, the segment and the reason.
7. A depth limit is an option.
8. A quote in a JSON path segment stays data, wherever the JSON column sits — pinned by a test, not refused.

## Success criteria

- The SQL of every path that resolves today is byte-identical: a one-hop path to a different table, a JSON path on a
  root column, `IN_HIERARCHY` on a root column. The benchmark's `NESTED_REF` SQL check passes unchanged.
- Tests on H2 and PostgreSQL (see Testing) pass; README, CHANGELOG and the repository's `CLAUDE.md` describe the new
  behaviour; the version is released.

## Non-goals

- Sorting by a path.
- Any change to the `rest` module: a filter key stays a free string there.
- A path that ends at a target (`documentId[RECEIPT]` with nothing after it).
- New JSON path syntax (array indexes, keys containing dots): JSON segments behave as today.
- An allow-list of fields per table inside the library: the consumer builds it on the resolved path.
- Joins instead of `EXISTS`, caching resolved paths.
- Changing the contracts of `referenceResolver` and `hierarchyResolver`.

## Anti-goals

- Changing the SQL of a path that works today.
- A path silently resolving to something other than what is written — an inner table binding to the outer query, a
  projection built over the unaliased table where an alias was needed. Such cases fail loudly.
- A path failure surfacing as anything but `FilterPathException`.
- A developer's misconfigured resolver reported as a path failure: that stays a plain `IllegalArgumentException` from
  `require`, so a consumer can answer the user's mistake and its own bug differently.

## Approaches

- **A. Resolve, then build (chosen with the maintainer).** A public, pure resolution turns a field into a resolved path —
  hops with their tables, aliases and projections, the compared expression and the JSON segments. Predicate building
  consumes the same value, so there is one set of rules. A consumer resolves paths for its checks, or for a report on
  stored filters, without building a query. Cost: a consumer that checks before applying resolves twice; the per-table
  field map behind it is cached per class.
- **B. A guard called per hop during building** (`FilterOptions.pathGuard`). One pass, but the path cannot be learned
  without building a query, so a report on stored filters would build throwaway queries.
- **C. Both.** More public surface; the guard adds nothing once the value exists.

## Path syntax

```
field     = root ( "." segment )*
segment   = name | name "[" target "]"
```

- **Root** — the longest dot-delimited prefix that is a key of the expression map, as `splitField` does today. A
  candidate prefix ending in `[target]` matches the key without it; a key written literally with brackets still wins.
  `splitByClause` and `requireUnambiguousFields` derive the root the same way.
- **Reference segment** — `name` is non-empty and has no `[` or `]`; `target` is non-empty and has no `.`, `[` or `]`.
  Anything else is `INVALID_SEGMENT`.
- **JSON** — once a JSON column is reached, the rest of the field, split on `.`, is the JSON path, taken verbatim:
  brackets mean nothing there.

`documentId[RECEIPT].senderId.name` stays one filter key, survives a JSON body unchanged and never collides with a
field code: Kotlin property names and SQL column names carry no brackets.

## The walk

`expr₀` is the root's expression. Segments are consumed in order; segment *i* (≥ 1) is a field of the target of
`expr₍ᵢ₋₁₎`:

1. If `expr₍ᵢ₋₁₎` is JSON (`JsonColumnMarker`), the remaining segments are its JSON path and the walk ends. Each JSON
   segment must be non-empty (`INVALID_SEGMENT`). A target on the segment that reached the JSON
   column is `TARGET_NOT_SUPPORTED`.
2. Otherwise `expr₍ᵢ₋₁₎` must be a `Column` (`NOT_A_COLUMN`). Its reference is resolved on the original column behind
   any alias clone (`originalColumn()`):
   - the segment that reached it names a target → `polymorphicReferenceResolver` must answer (`TARGET_NOT_SUPPORTED`)
     and know the target (`UNKNOWN_TARGET`);
   - no target → Exposed's `referee`, then `referenceResolver`, as today; when neither answers and
     `polymorphicReferenceResolver` does, `TARGET_REQUIRED`; otherwise `NOT_A_REFERENCE`.
3. Hop *i* past `maxReferenceHops` is `DEPTH_EXCEEDED`.
4. The target table is read as its **occurrence**: the table itself, or `table.alias("ef_<i>")` (see Aliases).
5. The field read is segment *i*'s name: `nestedFieldResolver(occurrence, name)` gives a projection, or the
   occurrence's own column is used (`fieldMap()` of the target; through an alias, the alias's clone of that column).
   Neither → `UNKNOWN_FIELD`.
6. `exprᵢ` is the projection's expression, or that column.

A path whose last segment names a target is `TARGET_WITHOUT_FIELD`. An unknown root is `UNKNOWN_FIELD` at segment 0.
When the walk ends, the last expression is compared by `predicateFor` — `IN_HIERARCHY` included — or, for a JSON path,
by `jsonPathPredicateFor`.

## SQL

The innermost predicate is built on the compared expression. Hops then wrap it from the last outwards:

```
hop i:  EXISTS (SELECT … FROM <sourceᵢ> WHERE <idᵢ> = <expr₍ᵢ₋₁₎> AND <inner>)
```

`sourceᵢ` is the projection's source, or the occurrence; `idᵢ` is the projection's `idExpression`, or the target id
column read through the occurrence. It is rendered exactly as today
(`source.selectAll().andWhere { (id eq reference) and inner }`), which keeps one-hop SQL byte-identical.

A hop through a reference with several targets adds its discriminator outside the subquery:

```
<discriminator> IS NOT NULL AND <discriminator> = <target> AND EXISTS (…)
```

The discriminator is read from the same table occurrence as the reference column — the alias clone when the reference
column is one. The target is converted like an `EQ` value on the discriminator (custom mappers, enums by constant name),
but compared case-sensitively: it is a tag, not user text. The `IS NOT NULL` guard keeps the predicate two-valued when
the discriminator is `NULL`, so such a row matches under `NOT`, like a missing link.

A `NULL` reference anywhere on the path makes its `EXISTS` false: the row does not match, and matches under `NOT`.

Two hops, orders → customers → regions:

```sql
EXISTS (SELECT … FROM customers WHERE customers.id = orders.customer_id AND
  EXISTS (SELECT … FROM regions WHERE regions.id = customers.region_id AND LOWER(regions.name) = ?))
```

A self-reference, `parentId.parentId.name` on categories:

```sql
EXISTS (SELECT … FROM categories ef_1 WHERE ef_1.id = categories.parent_id AND
  EXISTS (SELECT … FROM categories ef_2 WHERE ef_2.id = ef_1.parent_id AND LOWER(ef_2.name) = ?))
```

The one-hop self-reference `parentId.name` changes SQL: it rendered `categories.id = categories.parent_id` inside the
subquery, which compared each inner row with itself. It is now aliased and correct; the changelog says so.

## Aliases

The names in scope along a path are: the name of the root expression's table (an alias's name when it is one), then,
per hop, the names its source makes visible — the occurrence's name when the hop reads the occurrence; for a
projection, the names its source makes visible (a table's or an alias's name, a `QueryAlias`'s alias, a join's parts).
A plain table counts by its name without the schema, as SQL sees an unaliased `FROM archive.orders`. Hop *i*'s target
is aliased `ef_<i>` (1-based) when its table name is among them, and read unaliased otherwise — so a one-hop path to a
table of another name renders as today. Names starting with `ef_` are reserved by the library, as `ef_tree` and
`ef_child` already are.

An aliased occurrence reaches `nestedFieldResolver` as its `Table` argument (`Alias.delegate` is the target table). A
resolver that keys by table identity returns `null` for an alias, and the field is then read plainly from the alias.
The `idExpression` rule stays as it is: when `null`, the occurrence's id column must be among the source's columns.

The library renames only the target. A projection's source can still bind a reference to the wrong scope — a source
joining, unaliased, the table the hop correlates with (a localized projection over a translation table when the root
is that translation table), or an expression built over the plain table while the source reads its alias. Every
projection is therefore checked with `require` before any SQL is built — an `IllegalArgumentException`, since the
resolver is misconfigured, not the path:

- the names its source makes visible (a table's name, an alias's or a `QueryAlias`'s name, a join's parts) must not
  include a name already in scope outside it on the path;
- its `expression` and `idExpression` may read only columns of tables its source makes visible, compared by the
  qualifier a column renders with (its schema included).

A CTE, subquery or temporary table at an aliased hop passes when it sets `idExpression`. A one-hop projection that
breaks either rule renders SQL today that compares against the wrong row; it now fails instead, and the changelog says
so.

## Resolvers per hop

- `referenceResolver` — unchanged: one column, the original behind any clone, called for each hop.
- `nestedFieldResolver` — same type, `(Table, String)`, now called once per hop with one field name — never a dotted
  remainder — and the occurrence. For every path that resolves today it receives the same arguments as before. A
  projection at a middle hop must let the next hop continue: its expression is a `Column` with a reference to follow, or
  JSON. The next hop correlates on that expression inside the projection's source.
- `polymorphicReferenceResolver` — new: `((Column<*>) -> PolymorphicReferenceInfo?)?`, `null` by default, receives the
  original column.

  ```kotlin
  public data class PolymorphicReferenceInfo(
      public val discriminator: Column<*>,             // same table as the reference column (require)
      public val targets: Map<String, ReferenceInfo>,  // keyed by discriminator value as a path writes it
  )
  ```

- `hierarchyResolver` — unchanged.
- `normalizedStringFields`, `jsonPathType`, `aggregateFields` — still receive the whole field as written, so a consumer
  keeps recognising `…attributes.<key>` by its last segments wherever it sits.

## Resolution as a value

```kotlin
public fun ColumnSet.resolveFilterField(field: String, options: FilterOptions = DefaultFilterOptions): ResolvedFilterField
public fun Map<String, ExpressionWithColumnType<*>>.resolveFilterField(
    field: String,
    options: FilterOptions = DefaultFilterOptions,
): ResolvedFilterField

public class ResolvedFilterField internal constructor(
    public val field: String,                            // as written
    public val rootName: String,                         // the key of the expression map
    public val rootExpression: ExpressionWithColumnType<*>,
    public val hops: List<ResolvedHop>,                  // empty for a root field and a JSON path on it
    public val expression: ExpressionWithColumnType<*>,  // compared; the JSON expression for a JSON path
    public val jsonPath: List<String>,                   // empty unless a JSON path
)

public class ResolvedHop internal constructor(
    public val segment: String,                          // leaving the previous scope, as written: "documentId[RECEIPT]"
    public val referenceColumn: Column<*>,               // original column holding the reference
    public val referenceExpression: Column<*>,           // that column as the enclosing scope reads it (alias clone)
    public val target: String?,                          // named target of a reference with several targets
    public val discriminator: Column<*>?,                // its discriminator, original
    public val reference: ReferenceInfo,                 // target id column and table, original
    public val occurrence: Table,                        // reference.referencedTable or an alias of it
    public val field: String,                            // the field read in the target
    public val projection: NestedFieldProjection?,
    public val source: ColumnSet,                        // what the hop reads from: the projection's source or occurrence
    public val idExpression: ExpressionWithColumnType<*>, // the expression in source the reference is compared with
    public val expression: ExpressionWithColumnType<*>,  // what the hop reads
    public val tables: List<Table>,                      // original tables its subquery reads
)

public fun Column<*>.filterHierarchy(options: FilterOptions = DefaultFilterOptions): HierarchyInfo
```

- Both are plain classes, not data classes, and only the library constructs them: a field added later changes no
  `copy`, `componentN` or constructor a consumer compiled against. `toString()` names columns and tables as SQL does.
- `tables` lists the target table and every table of the projection's source: joins flattened, aliases unwrapped to
  their tables, a `QueryAlias` contributing the tables its query reads (every statement of a set operation). A consumer
  checks read access to each. Tables read only by a scalar subquery inside a projection's expression, or inside the
  `WHERE` of a subquery the source reads, are not listed.
- `filterHierarchy` exposes the tree the library compares an `IN_HIERARCHY` column against, so a consumer checks the
  tree of `expression` without re-implementing the detection. Its failures stay what they are today.
- The `ColumnSet` variant applies the same ambiguity check as `applyFiltersOn`.
- Resolution runs the resolvers exactly as building does — a resolver reading the current transaction sees the same
  state — builds no SQL and reads no rows. `applyFiltersOn` and `applyFilters` resolve each predicate's field and build
  from the result.

## Errors

```kotlin
public class FilterPathException(
    public val field: String,
    public val segmentIndex: Int,  // 0 is the root
    public val segment: String,
    public val reason: Reason,
    message: String,
) : IllegalArgumentException(message) {
    public enum class Reason {
        UNKNOWN_FIELD, NOT_A_COLUMN, NOT_A_REFERENCE, TARGET_REQUIRED, TARGET_NOT_SUPPORTED, UNKNOWN_TARGET,
        TARGET_WITHOUT_FIELD, INVALID_SEGMENT, DEPTH_EXCEEDED,
    }
}
```

Messages name the field and the segment; `Unknown filter field: <field>` is kept for an unknown root. Path failures
that threw `IllegalStateException` (an unknown base of a dotted path, an unknown nested field, a column that is not a
reference) now throw `FilterPathException` — a behaviour change the changelog records. Not path failures, and
unchanged: values that do not parse, an operator a type does not support, `IN_HIERARCHY` tree detection, a
misconfigured projection.

## Depth limit

`FilterOptions.maxReferenceHops: Int? = 1`. A hop through a reference with several targets counts as one; JSON
segments do not count. `null` means no limit; a negative value fails in the `FilterOptions` constructor.

The default keeps what an upgrading consumer accepts today — one hop — so a consumer whose checks cover the first hop
only does not start accepting deeper paths unseen. A consumer opts into depth by raising it. A path that a one-hop limit
refuses fails with `DEPTH_EXCEEDED` where it failed with "Unknown nested field" before.

## A quote in a JSON path segment

Not refused. Since Exposed 1.6.0, which this release requires, a JSON path segment reaches the database as a bound
parameter (`JSONB_EXTRACT_PATH_TEXT(col, ?)` on PostgreSQL; Oracle escapes it as a literal), so a quote is data and
`o'neil` is a valid key. A PostgreSQL test pins it, at the root and behind a hop: the segment is bound, not spliced
into the SQL text, and the key with a quote matches. If Exposed ever splices segments again, that test fails.

## Benchmarks

- Existing scenarios are unchanged; `verifySameSql` keeps pinning one-hop SQL through `NESTED_REF`.
- A new scenario, `NESTED_REF_2_HOPS`, over a new table referencing `Products`, so no existing scenario's
  `SELECT …` list changes, filtering `productId.warehouseId.name` against two hand-written nested `EXISTS`.
- `benchmark/results/1.16.0.json` is committed with the release, as `RELEASING.md` asks.

## Testing

An abstract contract with H2 and PostgreSQL subclasses, like the existing contracts. Its schema covers a chain of three
tables, a self-referencing table, a logical reference resolved by `referenceResolver`, a translation table for a
projection, a JSON column on a target, a tree for `IN_HIERARCHY`, and a reference with several targets.

1. Two and three hops: the rows, and one `EXISTS` per hop.
2. A self-reference at one hop (the fix) and at two; `ef_1`, `ef_2` in the SQL.
3. A hop back to the root table at depth two.
4. A logical reference in the middle of a path.
5. A projection on the last hop; on a middle hop, with the next hop correlating on its expression; for an aliased
   occurrence; a CTE projection with `idExpression` at an aliased hop. A projection whose source re-joins a table in
   scope, and one whose expression reads the plain table while the source reads its alias, fail with
   `IllegalArgumentException`, not a path failure.
6. Hops, then a JSON path: rows on PostgreSQL (JSONB); on H2, which cannot render JSON extraction, the resolution
   reaches the JSON column and building fails with `UnsupportedByDialectException`, as a root JSON path does today.
7. Hops, then `IN_HIERARCHY`.
8. A `NULL` link in the middle: no match; under `NOT`, a match.
9. A reference with several targets: the named target is read and other kinds are excluded; the path continues past
   it; a target on a root key; under `NOT`; no target, an unknown target, a target on a plain reference and a path
   ending at a target each fail with their reason.
10. Depth: at the limit, over it (`DEPTH_EXCEEDED` with the segment index), the default of one, `null`.
11. A quote in a JSON segment, at the root and behind a hop (PostgreSQL): bound as a parameter, absent from the SQL
    text, and the key with the quote matches.
12. Every reason, with its segment index and segment; an unknown root.
13. Resolution: hops, occurrences, tables including a projection's join, expression, JSON path; no SQL runs.
14. One-hop SQL pinned exactly against the hand-written predicate.
15. Dotted keys with a path below them split the same in `splitByClause` and `requireUnambiguousFields`.

Existing tests that pin SQL pass unchanged; assertions on the exception type of a path failure move from
`IllegalStateException` to `FilterPathException`.

## Release

`1.16.0`. `FilterOptions` changes constructor arity, which earlier minors recorded as not binary-compatible. The
changelog lists: paths of any depth; the hop through a reference with several targets; the resolution API and
`filterHierarchy`; `FilterPathException` (behaviour change from `IllegalStateException`); `maxReferenceHops` with its
default; the one-hop self-reference fix (SQL change); the checks on projections (a projection that bound to the wrong
scope now fails); `nestedFieldResolver` now called per hop and possibly with an alias. README: the references section rewritten, the compatibility table and
install snippet bumped. The repository's `CLAUDE.md`, point 4 of the data flow, rewritten.

Process: two issues; one pull request closing them; a release pull request (version, changelog date, README,
benchmark results); the tag `v1.16.0` after it is merged; the artifact verified on Maven Central.

## Decisions

Taken with the maintainer: the target syntax `name[target]`; approach A; a quote in a JSON segment is not refused
but pinned by a test (Exposed 1.6.0 binds segments).

Our calls, open to review:

- `nestedFieldResolver` keeps its type and receives an alias for an aliased occurrence — source-compatible; a richer
  parameter would break every existing resolver at compile time.
- The alias rule above, and the two checks on every projection.
- `FilterPathException` extends `IllegalArgumentException`: a path is caller input.
- `maxReferenceHops` defaults to one.
- A path ending at a target is refused.
- A target is compared case-sensitively.
- Version `1.16.0`, two issues.

## Left to the builder

- Exact names of the public types, functions and reasons, as long as what they carry stays as above.
- The internal structure: files, and how resolution and building are split.
- Error message texts, except the one kept above.
- The test fixtures' schema and the benchmark scenario's tables.
