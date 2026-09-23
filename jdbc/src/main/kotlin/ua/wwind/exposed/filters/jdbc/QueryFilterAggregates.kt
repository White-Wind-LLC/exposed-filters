package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Avg
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.GroupConcat
import org.jetbrains.exposed.v1.core.IExpressionAlias
import org.jetbrains.exposed.v1.core.Max
import org.jetbrains.exposed.v1.core.Min
import org.jetbrains.exposed.v1.core.StdDevPop
import org.jetbrains.exposed.v1.core.StdDevSamp
import org.jetbrains.exposed.v1.core.Sum
import org.jetbrains.exposed.v1.core.VarPop
import org.jetbrains.exposed.v1.core.VarSamp
import org.jetbrains.exposed.v1.core.WindowFunctionDefinition
import ua.wwind.exposed.filters.core.FilterCombinator
import ua.wwind.exposed.filters.core.FilterGroup
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterNode
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/** What a filter field's expression is, as far as the clause its predicate belongs to is concerned. */
internal enum class ExpressionKind { PLAIN, AGGREGATE, WINDOW }

/**
 * The parts of a filter tree that go to `WHERE` and to `HAVING`; `null` when a clause gets nothing.
 * Both parts together are equivalent to the original tree: they are only ever ANDed.
 */
internal class ClauseSplit(val where: FilterNode?, val having: FilterNode?)

/**
 * Splits this filter tree between `WHERE` and `HAVING`. A predicate on an aggregate field (detected
 * by [expressionKind], or listed in [FilterOptions.aggregateFields]) goes to `HAVING`, any other one
 * to `WHERE`. Conjuncts are split one by one, through nested AND groups; an OR or NOT group cannot be
 * split without changing its meaning, so it goes to `HAVING` whole as soon as it touches an aggregate.
 * That is still correct when its other fields are grouped columns, and the database rejects it when
 * they are not.
 *
 * A tree without aggregates is returned as the `WHERE` part untouched, so the SQL it renders does not
 * change. A field that is a window function fails: it can be filtered in neither clause of the query
 * that computes it.
 */
context(options: FilterOptions)
internal fun FilterNode.splitByClause(expressions: Map<String, ExpressionWithColumnType<*>>): ClauseSplit {
    val kinds = HashMap<String, Boolean>()
    val isAggregate = { field: String ->
        val baseName = field.substringBefore('.')
        kinds.getOrPut(baseName) { isAggregateField(baseName, expressions[baseName]) }
    }
    if (referencedFields().none { isAggregate(it) }) return ClauseSplit(where = this, having = null)

    val where = mutableListOf<FilterNode>()
    val having = mutableListOf<FilterNode>()
    fun visit(node: FilterNode) {
        when (node) {
            is FilterLeaf -> {
                val (aggregate, plain) = node.predicates.partition { isAggregate(it.field) }
                if (plain.isNotEmpty()) where += FilterLeaf(plain)
                if (aggregate.isNotEmpty()) having += FilterLeaf(aggregate)
            }

            is FilterGroup -> when {
                node.combinator == FilterCombinator.AND -> node.children.forEach(::visit)
                node.referencedFields().any { isAggregate(it) } -> having += node
                else -> where += node
            }
        }
    }
    visit(this)
    return ClauseSplit(where = where.toConjunction(), having = having.toConjunction())
}

private fun List<FilterNode>.toConjunction(): FilterNode? = when (size) {
    0 -> null
    1 -> single()
    else -> FilterGroup(FilterCombinator.AND, this)
}

context(options: FilterOptions)
private fun isAggregateField(name: String, expression: ExpressionWithColumnType<*>?): Boolean {
    if (name in options.aggregateFields) return true
    return when (expression?.expressionKind()) {
        ExpressionKind.AGGREGATE -> true
        ExpressionKind.WINDOW -> throw IllegalArgumentException(
            "Filter field '$name' is a window function: it cannot be filtered in WHERE or HAVING of the query " +
                "that computes it. Alias the query and filter the alias instead, e.g. applyFiltersOn(query.alias(...), ...)."
        )
        ExpressionKind.PLAIN, null -> false
    }
}

/**
 * Whether this expression computes an aggregate, a window function, or neither. Exposed has no common
 * marker for aggregates, so the expression tree is walked reflectively: an aggregate wrapped in another
 * expression (`coalesce(qty.sum(), 0)`, `qty.sum() + 1`) is still an aggregate. The walk stops at
 * whatever reads an already computed value — a [Column] (including a subquery's column) and an
 * alias-only reference such as the ones [org.jetbrains.exposed.v1.core.QueryAlias.get] returns for a
 * computed field. A scalar subquery is not walked either, since its aggregates belong to its own query.
 * An aggregate this walk does not know (a `CustomFunction`) is declared through
 * [FilterOptions.aggregateFields] instead.
 */
internal fun Expression<*>.expressionKind(): ExpressionKind =
    kindOf(this, Collections.newSetFromMap(IdentityHashMap()))

private fun kindOf(value: Any?, visited: MutableSet<Any>): ExpressionKind = when (value) {
    is Expression<*> -> if (visited.add(value)) expressionKindOf(value, visited) else ExpressionKind.PLAIN
    is Iterable<*> -> value.maxKind(visited)
    is Array<*> -> value.asIterable().maxKind(visited)
    is Pair<*, *> -> listOf(value.first, value.second).maxKind(visited)
    else -> ExpressionKind.PLAIN
}

private fun expressionKindOf(expression: Expression<*>, visited: MutableSet<Any>): ExpressionKind = when {
    expression is WindowFunctionDefinition<*> -> ExpressionKind.WINDOW
    expression.isAggregateFunction() -> ExpressionKind.AGGREGATE
    expression is Column<*> || expression.isAliasOnlyReference() -> ExpressionKind.PLAIN
    else -> expression.instanceFieldValues().maxKind(visited)
}

private fun Iterable<*>.maxKind(visited: MutableSet<Any>): ExpressionKind {
    var result = ExpressionKind.PLAIN
    for (element in this) {
        val kind = kindOf(element, visited)
        if (kind == ExpressionKind.WINDOW) return kind
        if (kind > result) result = kind
    }
    return result
}

private fun Expression<*>.isAggregateFunction(): Boolean =
    this is Sum<*> || this is Count || this is Min<*, *> || this is Max<*, *> || this is Avg<*, *> ||
        this is StdDevPop<*> || this is StdDevSamp<*> || this is VarPop<*> || this is VarSamp<*> ||
        this is GroupConcat<*>

/**
 * `IExpressionAlias.aliasOnlyExpression()` renders only the alias label, but the anonymous expression it
 * returns keeps the alias (and so the aliased expression) in a field. Walking into it would take a
 * subquery's `SUM` for one computed at this query level, so it is recognized by its declaring scope.
 */
private fun Expression<*>.isAliasOnlyReference(): Boolean =
    javaClass.name.startsWith(ALIAS_ONLY_CLASS_PREFIX)

private val ALIAS_ONLY_CLASS_PREFIX = IExpressionAlias::class.java.name + "\$aliasOnlyExpression"

/** The values of every instance field declared along this object's class hierarchy. */
private fun Any.instanceFieldValues(): List<Any?> =
    generateSequence<Class<*>>(javaClass) { it.superclass }
        .takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }
        .filterNot { Modifier.isStatic(it.modifiers) }
        .mapNotNull { field -> runCatching { field.apply { isAccessible = true }.get(this) }.getOrNull() }
        .toList()
