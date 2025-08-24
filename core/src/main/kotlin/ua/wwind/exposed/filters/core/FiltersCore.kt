package ua.wwind.exposed.filters.core

public enum class FilterOperator {
    EQ, NEQ, CONTAINS, STARTS_WITH, ENDS_WITH, IN, NOT_IN, BETWEEN, GT, GTE, LT, LTE, IS_NULL, IS_NOT_NULL
}

public enum class FilterCombinator { AND, OR }

public data class FieldFilter(
    val field: String,
    val operator: FilterOperator,
    val values: List<String>
)

public sealed interface FilterNode

public data class FilterLeaf(val predicates: List<FieldFilter>) : FilterNode

public data class FilterGroup(
    val combinator: FilterCombinator,
    val children: List<FilterNode>
) : FilterNode

public data class FilterRequest(val root: FilterNode?) {
    public val isEmpty: Boolean get() = root == null
}
