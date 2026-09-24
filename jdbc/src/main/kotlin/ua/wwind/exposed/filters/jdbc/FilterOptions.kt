package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Table

/**
 * Replaces the column a nested field path reads with an expression over a wider source.
 *
 * @property source Stands in for the referenced table as the `EXISTS` subquery's source: the table
 * joined to another one, an alias of it, a subquery, a CTE, a temporary table.
 * @property expression The expression the predicate is built against instead of the plain column.
 * @property idExpression The expression in [source] that holds the referenced id — the one the
 * subquery compares against the base column. Leave it `null` when [source] still exposes the
 * referenced table's own id column (the table joined to another one); set it whenever [source]
 * renames or replaces that table, e.g. `alias[Products.id]` for an alias or a subquery, or the id
 * column of a CTE. A `null` here with a source that does not expose the id column fails fast, since
 * the column would otherwise either be missing from the subquery or bind to the outer query.
 */
public data class NestedFieldProjection(
    public val source: ColumnSet,
    public val expression: ExpressionWithColumnType<*>,
    public val idExpression: ExpressionWithColumnType<*>? = null,
)

/**
 * Global options that tune how filter predicates are built.
 *
 * @property caseSensitiveStrings When `false` (default), predicates on `VarChar`/`Text` columns
 * (and string-typed `EntityID`, JSON string values) compare both sides in lower case, so that
 * `EQ`, `NEQ`, `IN`, `NOT_IN`, `CONTAINS`, `STARTS_WITH`, `ENDS_WITH`, `BETWEEN`, and `GT/GTE/LT/LTE`
 * are case-insensitive. Set to `true` to opt into case-sensitive comparisons.
 * @property normalizedStringFields Field names whose column values are guaranteed by the caller to
 * be stored lowercase. For these fields case-insensitive predicates lowercase only the input value
 * and compare the column raw (`column = ?` instead of `LOWER(column) = ?`), so a plain B-tree index
 * on the column can serve the query. Behavior is unchanged from the caller's perspective. Ignored
 * when [caseSensitiveStrings] is `true`. Matching is by exact field name as written in the filter;
 * JSON/JSONB string paths are not affected and keep the `LOWER()` form.
 * @property referenceResolver Resolves a nested field path (`someId.name`) for a column that carries
 * no physical foreign key. It is consulted only after Exposed's own `referee` lookup comes back
 * empty, so a declared reference always wins and the resolver never changes existing behavior.
 * Return the [ReferenceInfo] describing the logical target to make the path filterable, or `null`
 * to leave the column unresolvable and keep the failure. Useful when a module boundary forbids the
 * physical FK but the logical one is recorded elsewhere (an annotation, a registry). For a column of
 * a table alias or a subquery it receives the original table column behind the clone, never the clone.
 * @property nestedFieldResolver Replaces what a nested field path reads, given the resolved target
 * table and the nested field name. Unlike [referenceResolver] it is consulted for every nested path,
 * including one Exposed resolves itself, because the reference is not what needs replacing — the
 * column behind it is. Return `null` to read the target table's own column, which is the default and
 * keeps the emitted SQL unchanged. Useful when the readable value lives beside the target table
 * rather than in it, as a translation does.
 * @property aggregateFields Field names whose expressions are aggregates the library cannot recognize
 * on its own, e.g. a `CustomFunction("array_agg", ...)`. Predicates on these fields go to `HAVING`
 * instead of `WHERE`. Exposed's built-in aggregates (`sum()`, `count()`, `min()`, `max()`, `avg()`,
 * the standard deviations and variances, `groupConcat()`), including wrapped in another expression,
 * are detected without listing them here. Matching is by exact field name as written in the filter.
 */
public data class FilterOptions(
    public val caseSensitiveStrings: Boolean = false,
    public val normalizedStringFields: Set<String> = emptySet(),
    public val referenceResolver: ((Column<*>) -> ReferenceInfo?)? = null,
    public val nestedFieldResolver: ((Table, String) -> NestedFieldProjection?)? = null,
    public val aggregateFields: Set<String> = emptySet(),
)

internal fun FilterOptions.usesNormalizedComparison(fieldName: String): Boolean =
    !caseSensitiveStrings && fieldName in normalizedStringFields

internal val DefaultFilterOptions: FilterOptions = FilterOptions()
