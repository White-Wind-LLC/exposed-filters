package ua.wwind.exposed.filters.rest

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveNullable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterCombinator
import ua.wwind.exposed.filters.core.FilterGroup
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterNode
import ua.wwind.exposed.filters.core.FilterOperator
import ua.wwind.exposed.filters.core.FilterRequest

@Serializable
internal data class ConditionDto(
    val op: FilterOperator,
    val value: String? = null,
    val values: List<String>? = null,
)

@Serializable
internal data class FilterNodeDto(
    val combinator: FilterCombinator? = null,
    val filters: Map<String, List<ConditionDto>>? = null,
    val children: List<FilterNodeDto>? = null,
)

@Serializable
internal data class FilterBodyDto(
    // Flat structure
    val filters: Map<String, List<ConditionDto>>? = null,
    val combinator: FilterCombinator? = null,
    // Tree structure
    val children: List<FilterNodeDto>? = null,
)

private val json by lazy {
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
}

public suspend fun ApplicationCall.receiveFilterRequestOrNull(): FilterRequest? {
    val raw: String = runCatching { this.receiveNullable<String>() }.getOrNull()?.trim().orEmpty()
    val body: FilterBodyDto? = if (raw.isEmpty()) {
        null
    } else {
        json.decodeFromString<FilterBodyDto>(raw)
    }
    if (body == null) return null

    val topLeaf: FilterLeaf? = buildLeafOrNull(body.filters)
    val nestedChildren: List<FilterNode> = body.children?.mapNotNull { it.toNodeOrNull() }.orEmpty()
    val combinator: FilterCombinator = body.combinator ?: FilterCombinator.AND

    val combinedChildren: List<FilterNode> = buildList {
        if (topLeaf != null) add(topLeaf)
        addAll(nestedChildren)
    }

    val root: FilterNode? = when {
        combinedChildren.isEmpty() -> null
        // Preserve previous behavior for only-filters case
        topLeaf != null && nestedChildren.isEmpty() -> {
            if (combinator == FilterCombinator.AND) topLeaf else FilterGroup(combinator, listOf(topLeaf))
        }

        else -> {
            FilterGroup(
                combinator = combinator,
                children = combinedChildren
            ).normalize()
        }
    }

    return if (root == null) null else FilterRequest(root)
}

private fun FilterNodeDto.toNodeOrNull(): FilterNode? {
    val leaf = buildLeafOrNull(filters)
    val nested = children?.mapNotNull { it.toNodeOrNull() }.orEmpty()
    val hasLeaf = leaf != null
    val hasNested = nested.isNotEmpty()
    if (!hasLeaf && !hasNested) return null
    val comb = combinator ?: FilterCombinator.AND
    return when {
        hasLeaf && !hasNested -> {
            if (comb == FilterCombinator.AND) {
                leaf
            } else {
                FilterGroup(comb, listOf(leaf))
            }
        }

        !hasLeaf -> {
            FilterGroup(comb, nested)
        }

        else -> {
            FilterGroup(
                comb,
                listOf(leaf) + nested
            )
        }
    }.normalize()
}

private fun buildLeafOrNull(filters: Map<String, List<ConditionDto>>?): FilterLeaf? {
    if (filters == null) return null
    val predicates = filters.flatMap { (field, conditions) ->
        conditions.mapNotNull { condition ->
            val values: List<String> = when (condition.op) {
                FilterOperator.IN,
                FilterOperator.NOT_IN,
                FilterOperator.BETWEEN -> {
                    condition.values ?: return@mapNotNull null
                }

                FilterOperator.IS_NULL,
                FilterOperator.IS_NOT_NULL -> {
                    emptyList()
                }

                else -> {
                    listOfNotNull(condition.value)
                }
            }
            FieldFilter(
                field = field,
                operator = condition.op,
                values = values
            )
        }
    }
    return if (predicates.isEmpty()) null else FilterLeaf(predicates)
}

private fun FilterNode.normalize(): FilterNode? {
    return when (this) {
        is FilterLeaf -> {
            if (predicates.isEmpty()) null else this
        }

        is FilterGroup -> {
            val normChildren = children.mapNotNull { child -> child.normalize() }
            when {
                normChildren.isEmpty() -> null
                normChildren.size == 1 -> normChildren.first()
                else -> copy(children = normChildren)
            }
        }
    }
}
