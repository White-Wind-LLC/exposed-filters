package ua.wwind.exposed.filters.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * DTO for a single filter condition.
 * Matches the expected format for deserialization in `parseFilterRequestOrNull`.
 */
@Serializable
public data class ConditionDto(
    val op: FilterOperator,
    val value: String? = null,
    val values: List<String>? = null,
)

/**
 * DTO for a nested filter node (child in tree structure).
 * Matches the expected format for deserialization in `parseFilterRequestOrNull`.
 */
@Serializable
public data class FilterNodeDto(
    val combinator: FilterCombinator? = null,
    val filters: Map<String, List<ConditionDto>>? = null,
    val children: List<FilterNodeDto>? = null,
)

/**
 * DTO for the root filter body.
 * Matches the expected format for deserialization in `parseFilterRequestOrNull`.
 */
@Serializable
public data class FilterBodyDto(
    val filters: Map<String, List<ConditionDto>>? = null,
    val combinator: FilterCombinator? = null,
    val children: List<FilterNodeDto>? = null,
)

private val json by lazy {
    Json {
        encodeDefaults = false
        explicitNulls = false
    }
}

/**
 * Serialize [FilterRequest] to a JSON string that can be deserialized
 * via `parseFilterRequestOrNull`.
 *
 * Example:
 * ```kotlin
 * val request = filterRequest {
 *     eq("status", "ACTIVE")
 *     or {
 *         eq("type", "A")
 *         eq("type", "B")
 *     }
 * }
 * val json = request?.toJsonString()
 * ```
 */
public fun FilterRequest.toJsonString(): String {
    val dto = toFilterBodyDto()
    return json.encodeToString(dto)
}

/**
 * Convert [FilterRequest] to [FilterBodyDto] for serialization.
 */
public fun FilterRequest.toFilterBodyDto(): FilterBodyDto {
    return root.toFilterBodyDto()
}

private fun FilterNode.toFilterBodyDto(): FilterBodyDto {
    return when (this) {
        is FilterLeaf -> {
            FilterBodyDto(
                filters = predicatesToFiltersMap(),
                combinator = null,
                children = null,
            )
        }

        is FilterGroup -> {
            val (leafFilters, childNodes) = extractLeafAndChildren()
            FilterBodyDto(
                filters = leafFilters,
                combinator = combinator,
                children = childNodes.takeIf { it.isNotEmpty() },
            )
        }
    }
}

private fun FilterGroup.extractLeafAndChildren(): Pair<Map<String, List<ConditionDto>>?, List<FilterNodeDto>> {
    val firstLeaf = children.firstOrNull { it is FilterLeaf } as? FilterLeaf
    val leafFilters = firstLeaf?.predicatesToFiltersMap()

    val childNodes = children.mapNotNull { child ->
        when (child) {
            is FilterLeaf -> {
                // Skip first leaf as it goes to top-level filters
                if (child === firstLeaf) null else child.toFilterNodeDto()
            }

            is FilterGroup -> child.toFilterNodeDto()
        }
    }

    return leafFilters to childNodes
}

private fun FilterNode.toFilterNodeDto(): FilterNodeDto {
    return when (this) {
        is FilterLeaf -> {
            FilterNodeDto(
                combinator = null,
                filters = predicatesToFiltersMap(),
                children = null,
            )
        }

        is FilterGroup -> {
            val (leafFilters, childNodes) = extractLeafAndChildren()
            FilterNodeDto(
                combinator = combinator,
                filters = leafFilters,
                children = childNodes.takeIf { it.isNotEmpty() },
            )
        }
    }
}

private fun FilterLeaf.predicatesToFiltersMap(): Map<String, List<ConditionDto>> {
    return predicates.groupBy { it.field }.mapValues { (_, fieldFilters) ->
        fieldFilters.map { filter ->
            filter.toConditionDto()
        }
    }
}

private fun FieldFilter.toConditionDto(): ConditionDto {
    return when (operator) {
        FilterOperator.IN,
        FilterOperator.NOT_IN,
        FilterOperator.BETWEEN -> {
            ConditionDto(
                op = operator,
                value = null,
                values = values.takeIf { it.isNotEmpty() },
            )
        }

        FilterOperator.IS_NULL,
        FilterOperator.IS_NOT_NULL -> {
            ConditionDto(
                op = operator,
                value = null,
                values = null,
            )
        }

        else -> {
            ConditionDto(
                op = operator,
                value = values.firstOrNull(),
                values = null,
            )
        }
    }
}
