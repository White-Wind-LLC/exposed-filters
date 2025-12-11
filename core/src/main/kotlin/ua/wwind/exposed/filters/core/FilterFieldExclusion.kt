package ua.wwind.exposed.filters.core

/**
 * Removes filter conditions that reference specified fields from a FilterRequest.
 *
 * The goal is to exclude fields that cannot be filtered at a certain query level,
 * while ensuring the result set is never narrowed (records are never lost).
 * The caller is expected to apply the full filter on the final result.
 *
 * Rules:
 * - AND: removing a field broadens results (safe)
 * - OR: removing a field narrows results (entire OR is removed if it has direct excluded field)
 * - NOT: removing a field can narrow results due to De Morgan's law (entire NOT is removed)
 *
 * @param excludedFields list of field names to exclude from filters
 * @return new FilterRequest without excluded field conditions, or null if all conditions were removed
 */
public fun FilterRequest.excludeFields(excludedFields: List<String>): FilterRequest? {
    if (excludedFields.isEmpty()) return this

    val excludedSet = excludedFields.toSet()
    val filteredRoot = root.removeExcludedFields(excludedSet)

    return filteredRoot?.let { FilterRequest(it) }
}

/**
 * Removes filter conditions that reference specified fields from a FilterRequest.
 *
 * @param excludedFields field names to exclude from filters
 * @return new FilterRequest without excluded field conditions, or null if all conditions were removed
 */
public fun FilterRequest.excludeFields(vararg excludedFields: String): FilterRequest? =
    excludeFields(excludedFields.toList())

// --- Private helpers ---

/**
 * Checks if FilterLeaf directly contains any of the excluded fields.
 */
private fun FilterLeaf.containsExcludedFields(excludedFields: Set<String>): Boolean =
    predicates.any { it.field in excludedFields }

/**
 * Checks if filter node contains any excluded fields at any depth.
 */
private fun FilterNode.containsExcludedFieldsDeep(excludedFields: Set<String>): Boolean =
    when (this) {
        is FilterLeaf -> predicates.any { it.field in excludedFields }
        is FilterGroup -> children.any { it.containsExcludedFieldsDeep(excludedFields) }
    }

/**
 * Removes excluded fields from a FilterLeaf.
 */
private fun FilterLeaf.removeExcludedFields(excludedFields: Set<String>): FilterLeaf? {
    val filteredPredicates = predicates.filter { it.field !in excludedFields }
    return if (filteredPredicates.isEmpty()) null else FilterLeaf(filteredPredicates)
}

/**
 * Simplifies filtered children list to a single node or group.
 */
private fun simplifyChildren(
    filteredChildren: List<FilterNode>,
    combinator: FilterCombinator,
): FilterNode? =
    when {
        filteredChildren.isEmpty() -> null
        filteredChildren.size == 1 && combinator != FilterCombinator.NOT -> filteredChildren.first()
        else -> FilterGroup(combinator, filteredChildren)
    }

/**
 * Processes OR group: removes entire group if any direct leaf contains excluded fields.
 * Removing a field from OR would narrow the result set, which is not allowed.
 */
private fun FilterGroup.processOrGroup(excludedFields: Set<String>): FilterNode? {
    val hasDirectExcludedLeaf =
        children.any { child ->
            child is FilterLeaf && child.containsExcludedFields(excludedFields)
        }
    if (hasDirectExcludedLeaf) return null

    val filteredChildren = children.mapNotNull { it.removeExcludedFields(excludedFields) }
    return simplifyChildren(filteredChildren, combinator)
}

/**
 * Processes AND group: recursively processes children.
 * Removing a field from AND broadens the result set, which is safe.
 */
private fun FilterGroup.processAndGroup(excludedFields: Set<String>): FilterNode? {
    val filteredChildren = children.mapNotNull { it.removeExcludedFields(excludedFields) }
    return simplifyChildren(filteredChildren, combinator)
}

/**
 * Processes NOT group: removes entire group if any child contains excluded fields at any depth.
 * This is because NOT inverts the logic - removing fields from inside NOT(AND(...))
 * would narrow the result set (via De Morgan's law: NOT(A AND B) = NOT(A) OR NOT(B)).
 */
private fun FilterGroup.processNotGroup(excludedFields: Set<String>): FilterNode? {
    if (children.any { it.containsExcludedFieldsDeep(excludedFields) }) {
        return null
    }
    return this
}

/**
 * Recursively removes filter conditions that reference excluded fields.
 *
 * @return filtered node or null if the node should be completely removed
 */
private fun FilterNode.removeExcludedFields(excludedFields: Set<String>): FilterNode? =
    when (this) {
        is FilterLeaf -> {
            removeExcludedFields(excludedFields)
        }

        is FilterGroup -> {
            when (combinator) {
                FilterCombinator.OR -> processOrGroup(excludedFields)
                FilterCombinator.AND -> processAndGroup(excludedFields)
                FilterCombinator.NOT -> processNotGroup(excludedFields)
            }
        }
    }
