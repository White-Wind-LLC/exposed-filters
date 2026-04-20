package ua.wwind.exposed.filters.jdbc

/**
 * Global options that tune how filter predicates are built.
 *
 * @property caseSensitiveStrings When `false` (default), predicates on `VarChar`/`Text` columns
 * (and string-typed `EntityID`, JSON string values) compare both sides in lower case, so that
 * `EQ`, `NEQ`, `IN`, `NOT_IN`, `CONTAINS`, `STARTS_WITH`, `ENDS_WITH`, `BETWEEN`, and `GT/GTE/LT/LTE`
 * are case-insensitive. Set to `true` to opt into case-sensitive comparisons.
 */
public data class FilterOptions(
    public val caseSensitiveStrings: Boolean = false,
)

internal val DefaultFilterOptions: FilterOptions = FilterOptions()
