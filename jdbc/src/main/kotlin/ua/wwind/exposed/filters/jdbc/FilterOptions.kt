package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column

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
 * physical FK but the logical one is recorded elsewhere (an annotation, a registry).
 */
public data class FilterOptions(
    public val caseSensitiveStrings: Boolean = false,
    public val normalizedStringFields: Set<String> = emptySet(),
    public val referenceResolver: ((Column<*>) -> ReferenceInfo?)? = null,
)

internal fun FilterOptions.usesNormalizedComparison(fieldName: String): Boolean =
    !caseSensitiveStrings && fieldName in normalizedStringFields

internal val DefaultFilterOptions: FilterOptions = FilterOptions()
