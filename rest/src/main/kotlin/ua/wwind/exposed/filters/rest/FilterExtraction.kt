package ua.wwind.exposed.filters.rest

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveNullable
import kotlinx.serialization.SerializationException
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

/**
 * Strict on purpose: a body that does not match the expected shape must fail loudly instead of
 * decoding into an empty [FilterBodyDto], which would silently drop the client's filter and return
 * an unfiltered result set.
 */
private val json by lazy {
    Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }
}

public suspend fun ApplicationCall.receiveFilterRequestOrNull(): FilterRequest? {
    val raw: String = runCatching { this.receiveNullable<String>() }.getOrNull()?.trim().orEmpty()
    return parseFilterRequestOrNull(raw)
}

/**
 * Parse [FilterRequest] from a raw JSON string.
 *
 * Returns `null` when the string is blank or when the body carries no predicates
 * (`{}`, `{"filters":{}}`) — both mean "no filters".
 *
 * Parsing is strict: a non-blank body that is not a valid filter body — an unknown key, an unknown
 * operator or combinator, a wrong shape, malformed JSON — throws [FilterRequestParseException]
 * rather than degrading into "no filters". Map it to `400 Bad Request`.
 */
public fun parseFilterRequestOrNull(raw: String): FilterRequest? {
    val text: String = raw.trim()
    if (text.isEmpty()) return null
    val body: FilterBodyDto = try {
        json.decodeFromString<FilterBodyDto>(text)
    } catch (e: SerializationException) {
        throw FilterRequestParseException(
            "Invalid filter request body: ${e.message}. " +
                "Expected either {\"filters\":{...}} or {\"combinator\":...,\"children\":[...]}; " +
                "send an empty body or {} to apply no filters.",
            e,
        )
    }
    return buildFilterRequestOrNull(body)
}

private fun buildFilterRequestOrNull(body: FilterBodyDto): FilterRequest? {
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
            if (combinator == FilterCombinator.AND) {
                topLeaf
            } else {
                // For OR/NOT: wrap each predicate in its own FilterLeaf so combinator applies correctly
                val individualLeaves = topLeaf.predicates.map { FilterLeaf(listOf(it)) }
                FilterGroup(combinator, individualLeaves)
            }
        }

        else -> {
            FilterGroup(
                combinator = combinator,
                children = combinedChildren
            ).normalize()
        }
    }

    return root?.let { FilterRequest(it) }
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
                // For OR/NOT: wrap each predicate in its own FilterLeaf so combinator applies correctly
                val individualLeaves = leaf.predicates.map { FilterLeaf(listOf(it)) }
                FilterGroup(comb, individualLeaves)
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
        conditions.map { condition ->
            val values: List<String> = when (condition.op) {
                FilterOperator.IN,
                FilterOperator.BETWEEN,
                FilterOperator.NOT_IN -> {
                    condition.values ?: emptyList()
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
            when (combinator) {
                // Preserve NOT even for a single child, so the negation is not lost
                FilterCombinator.NOT -> {
                    when {
                        normChildren.isEmpty() -> null
                        else -> copy(children = normChildren)
                    }
                }

                else -> {
                    when {
                        normChildren.isEmpty() -> null
                        normChildren.size == 1 -> normChildren.first()
                        else -> copy(children = normChildren)
                    }
                }
            }
        }
    }
}
