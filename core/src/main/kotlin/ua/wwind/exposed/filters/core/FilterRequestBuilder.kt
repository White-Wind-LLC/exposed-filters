package ua.wwind.exposed.filters.core

/**
 * DSL builder for constructing [FilterRequest] with support for arbitrary nesting.
 *
 * - Allows adding field predicates with various operators
 * - Supports configuring the node combinator (AND/OR/NOT)
 * - Supports nested children via `child { ... }`, `and { ... }`, `or { ... }`, `not { ... }`
 *
 * Example usage:
 * ```kotlin
 * val request = filterRequest {
 *     eq("status", "ACTIVE")
 *     gte("age", 18)
 *     or {
 *         eq("role", "ADMIN")
 *         eq("role", "MODERATOR")
 *     }
 * }
 * ```
 */
@Suppress("TooManyFunctions")
public class FilterRequestBuilder internal constructor(
    initialCombinator: FilterCombinator? = null,
    initialPredicates: List<FieldFilter>? = null,
    initialChildren: List<FilterNode>? = null,
) {
    private var nodeCombinator: FilterCombinator? = initialCombinator
    private val predicates: MutableList<FieldFilter> = mutableListOf()
    private val children: MutableList<FilterNode> = mutableListOf()

    init {
        initialPredicates?.let { predicates.addAll(it) }
        initialChildren?.let { children.addAll(it) }
    }

    // ----- Combinator -----

    /** Set this node combinator. */
    public fun combinator(combinator: FilterCombinator): FilterRequestBuilder = apply {
        this.nodeCombinator = combinator
    }

    /** Convenience setters for combinator. */
    public fun and(): FilterRequestBuilder = combinator(FilterCombinator.AND)
    public fun or(): FilterRequestBuilder = combinator(FilterCombinator.OR)
    public fun not(): FilterRequestBuilder = combinator(FilterCombinator.NOT)

    // ----- Build -----

    /** Build final [FilterRequest] or null if empty. */
    public fun build(): FilterRequest? {
        val node = buildNode() ?: return null
        return FilterRequest(node)
    }

    /** Build the filter node structure. */
    internal fun buildNode(): FilterNode? {
        val hasPredicates = predicates.isNotEmpty()
        val hasChildren = children.isNotEmpty()
        val comb = nodeCombinator ?: FilterCombinator.AND

        if (!hasPredicates && !hasChildren) return null

        val leaf = if (hasPredicates) FilterLeaf(predicates.toList()) else null

        return when {
            hasPredicates && !hasChildren -> {
                if (comb == FilterCombinator.AND) leaf else FilterGroup(comb, listOfNotNull(leaf))
            }

            !hasPredicates && hasChildren -> {
                simplifyGroup(comb, children.toList())
            }

            else -> {
                FilterGroup(comb, listOfNotNull(leaf) + children)
            }
        }
    }

    private fun simplifyGroup(combinator: FilterCombinator, nodes: List<FilterNode>): FilterNode? = when {
        nodes.isEmpty() -> null
        nodes.size == 1 && combinator != FilterCombinator.NOT -> nodes.first()
        else -> FilterGroup(combinator, nodes)
    }

    // ----- Predicate Management -----

    /** Add a raw predicate. */
    public fun add(field: String, operator: FilterOperator, values: List<String>) {
        predicates.add(FieldFilter(field, operator, values))
    }

    /** Add a predicate with a single value. */
    public fun add(field: String, operator: FilterOperator, value: Any?) {
        add(field, operator, value?.let { listOf(toStringValue(it)) } ?: emptyList())
    }

    /**
     * Replace all predicates for the given [field] with a single predicate.
     */
    public fun replace(field: String, operator: FilterOperator, values: List<String>) {
        predicates.removeAll { it.field == field }
        predicates.add(FieldFilter(field, operator, values))
    }

    /** Replace with a single value. */
    public fun replace(field: String, operator: FilterOperator, value: Any?) {
        replace(field, operator, value?.let { listOf(toStringValue(it)) } ?: emptyList())
    }

    /** Remove all predicates for the given [field]. */
    public fun remove(field: String) {
        predicates.removeAll { it.field == field }
    }

    /**
     * Add predicate only if the [field] does not have any predicates yet.
     * Returns true when the predicate was added.
     */
    public fun addIfAbsent(field: String, operator: FilterOperator, values: List<String>): Boolean {
        val exists = predicates.any { it.field == field }
        if (!exists) {
            predicates.add(FieldFilter(field, operator, values))
        }
        return !exists
    }

    // ----- Children -----

    /** Add an already built child [FilterNode]. */
    public fun child(node: FilterNode) {
        children.add(node)
    }

    /** Create and add a child with optional predefined combinator. */
    public fun child(combinator: FilterCombinator? = null, init: FilterRequestBuilder.() -> Unit) {
        val builder = FilterRequestBuilder(initialCombinator = combinator)
        builder.init()
        builder.buildNode()?.let { children.add(it) }
    }

    /** Convenience child builders with combinator presets. */
    public fun and(init: FilterRequestBuilder.() -> Unit): Unit = child(FilterCombinator.AND, init)
    public fun or(init: FilterRequestBuilder.() -> Unit): Unit = child(FilterCombinator.OR, init)
    public fun not(init: FilterRequestBuilder.() -> Unit): Unit = child(FilterCombinator.NOT, init)

    // ----- Single-value operators -----

    public fun eq(field: String, value: Any) {
        add(field, FilterOperator.EQ, value)
    }

    public fun neq(field: String, value: Any) {
        add(field, FilterOperator.NEQ, value)
    }

    public fun contains(field: String, value: String) {
        add(field, FilterOperator.CONTAINS, value)
    }

    public fun startsWith(field: String, value: String) {
        add(field, FilterOperator.STARTS_WITH, value)
    }

    public fun endsWith(field: String, value: String) {
        add(field, FilterOperator.ENDS_WITH, value)
    }

    public fun gt(field: String, value: Any) {
        add(field, FilterOperator.GT, value)
    }

    public fun gte(field: String, value: Any) {
        add(field, FilterOperator.GTE, value)
    }

    public fun lt(field: String, value: Any) {
        add(field, FilterOperator.LT, value)
    }

    public fun lte(field: String, value: Any) {
        add(field, FilterOperator.LTE, value)
    }

    public fun isNull(field: String) {
        add(field, FilterOperator.IS_NULL, emptyList<String>())
    }

    public fun isNotNull(field: String) {
        add(field, FilterOperator.IS_NOT_NULL, emptyList<String>())
    }

    // ----- Single-value operators (nullable-aware: null -> remove) -----

    public fun eqOrRemove(field: String, value: Any?) {
        if (value == null) remove(field) else eq(field, value)
    }

    public fun neqOrRemove(field: String, value: Any?) {
        if (value == null) remove(field) else neq(field, value)
    }

    public fun containsOrRemove(field: String, value: String?) {
        if (value == null) remove(field) else contains(field, value)
    }

    public fun startsWithOrRemove(field: String, value: String?) {
        if (value == null) remove(field) else startsWith(field, value)
    }

    public fun endsWithOrRemove(field: String, value: String?) {
        if (value == null) remove(field) else endsWith(field, value)
    }

    public fun gtOrRemove(field: String, value: Any?) {
        if (value == null) remove(field) else gt(field, value)
    }

    public fun gteOrRemove(field: String, value: Any?) {
        if (value == null) remove(field) else gte(field, value)
    }

    public fun ltOrRemove(field: String, value: Any?) {
        if (value == null) remove(field) else lt(field, value)
    }

    public fun lteOrRemove(field: String, value: Any?) {
        if (value == null) remove(field) else lte(field, value)
    }

    /** EQ if value is not null, IS_NULL otherwise. */
    public fun eqOrIsNull(field: String, value: Any?) {
        if (value == null) {
            isNull(field)
        } else {
            eq(field, value)
        }
    }

    /** Add EQ predicate only if the field has no predicates yet. */
    public fun eqIfAbsent(field: String, value: Any): Boolean =
        addIfAbsent(field, FilterOperator.EQ, listOf(toStringValue(value)))

    // ----- Multi-value operators -----

    public fun inList(field: String, values: Iterable<Any>) {
        add(field, FilterOperator.IN, values.map { toStringValue(it) })
    }

    public fun inList(field: String, vararg values: Any) {
        inList(field, values.asList())
    }

    public fun notInList(field: String, values: Iterable<Any>) {
        add(field, FilterOperator.NOT_IN, values.map { toStringValue(it) })
    }

    public fun notInList(field: String, vararg values: Any) {
        notInList(field, values.asList())
    }

    /**
     * BETWEEN expects two values: lower and upper bounds.
     */
    public fun between(field: String, from: Any?, to: Any?) {
        val values = listOf(
            from?.let { toStringValue(it) } ?: "",
            to?.let { toStringValue(it) } ?: ""
        )
        add(field, FilterOperator.BETWEEN, values)
    }

    // ----- Multi-value operators (nullable-aware) -----

    public fun inListOrRemove(field: String, values: Iterable<Any?>?) {
        if (values == null) {
            remove(field)
        } else {
            add(field, FilterOperator.IN, values.mapNotNull { it?.let { v -> toStringValue(v) } })
        }
    }

    public fun notInListOrRemove(field: String, values: Iterable<Any?>?) {
        if (values == null) {
            remove(field)
        } else {
            add(field, FilterOperator.NOT_IN, values.mapNotNull { it?.let { v -> toStringValue(v) } })
        }
    }

    public fun betweenOrRemove(field: String, from: Any?, to: Any?) {
        if (from == null && to == null) {
            remove(field)
        } else {
            between(field, from, to)
        }
    }

    // ----- Helpers -----

    private fun toStringValue(value: Any): String = value.toString()

    // ----- Infix extension functions on String -----

    /** Infix version: `"field" eq value` */
    @JvmName("eqInfix")
    public infix fun String.eq(value: Any): Unit = eq(this, value)

    /** Infix version: `"field" neq value` */
    @JvmName("neqInfix")
    public infix fun String.neq(value: Any): Unit = neq(this, value)

    /** Infix version: `"field" contains value` */
    @JvmName("containsInfix")
    public infix fun String.contains(value: String): Unit = contains(this, value)

    /** Infix version: `"field" startsWith value` */
    @JvmName("startsWithInfix")
    public infix fun String.startsWith(value: String): Unit = startsWith(this, value)

    /** Infix version: `"field" endsWith value` */
    @JvmName("endsWithInfix")
    public infix fun String.endsWith(value: String): Unit = endsWith(this, value)

    /** Infix version: `"field" gt value` */
    @JvmName("gtInfix")
    public infix fun String.gt(value: Any): Unit = gt(this, value)

    /** Infix version: `"field" gte value` */
    @JvmName("gteInfix")
    public infix fun String.gte(value: Any): Unit = gte(this, value)

    /** Infix version: `"field" lt value` */
    @JvmName("ltInfix")
    public infix fun String.lt(value: Any): Unit = lt(this, value)

    /** Infix version: `"field" lte value` */
    @JvmName("lteInfix")
    public infix fun String.lte(value: Any): Unit = lte(this, value)

    /** Infix version: `"field" inList listOf(...)` */
    @JvmName("inListInfix")
    public infix fun String.inList(values: Iterable<Any>): Unit = inList(this, values)

    /** Infix version: `"field" notInList listOf(...)` */
    @JvmName("notInListInfix")
    public infix fun String.notInList(values: Iterable<Any>): Unit = notInList(this, values)

    /** Infix version: `"field" between (from to to)` */
    @JvmName("betweenPairInfix")
    public infix fun String.between(range: Pair<Any?, Any?>): Unit = between(this, range.first, range.second)

    /** Infix version: `"field" between 15..28` for ClosedRange */
    @JvmName("betweenRangeInfix")
    public infix fun <T : Comparable<T>> String.between(range: ClosedRange<T>): Unit =
        between(this, range.start, range.endInclusive)

    /** Extension version: `"field".isNull()` */
    @JvmName("isNullExt")
    public fun String.isNull(): Unit = isNull(this)

    /** Extension version: `"field".isNotNull()` */
    @JvmName("isNotNullExt")
    public fun String.isNotNull(): Unit = isNotNull(this)
}

/**
 * DSL entry point for building a [FilterRequest].
 *
 * Example:
 * ```kotlin
 * val request = filterRequest {
 *     eq("status", "ACTIVE")
 *     gte("createdAt", "2024-01-01")
 *     or {
 *         eq("type", "A")
 *         eq("type", "B")
 *     }
 * }
 * ```
 */
public fun filterRequest(
    combinator: FilterCombinator = FilterCombinator.AND,
    init: FilterRequestBuilder.() -> Unit
): FilterRequest? {
    val builder = FilterRequestBuilder(initialCombinator = combinator)
    builder.init()
    return builder.build()
}

/**
 * Create a builder pre-populated from an existing [FilterRequest].
 */
public fun FilterRequest.toBuilder(): FilterRequestBuilder {
    val (predicates, children) = extractFromNode(root)
    val combinator = when (val node = root) {
        is FilterGroup -> node.combinator
        is FilterLeaf -> FilterCombinator.AND
    }
    return FilterRequestBuilder(
        initialCombinator = combinator,
        initialPredicates = predicates,
        initialChildren = children,
    )
}

private fun extractFromNode(node: FilterNode): Pair<List<FieldFilter>?, List<FilterNode>?> = when (node) {
    is FilterLeaf -> node.predicates to null
    is FilterGroup -> {
        val leaves = node.children.filterIsInstance<FilterLeaf>()
        val groups = node.children.filter { it !is FilterLeaf || leaves.indexOf(it) > 0 }
        val predicates = leaves.firstOrNull()?.predicates
        val children = if (groups.isEmpty()) null else groups + leaves.drop(1)
        predicates to children
    }
}
