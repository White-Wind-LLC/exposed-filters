package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterCombinator
import ua.wwind.exposed.filters.core.FilterGroup
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterNode

context(mappersModule: ColumnMappersModule?, options: FilterOptions)
internal fun nodeToPredicate(
    node: FilterNode,
    expressions: Map<String, ExpressionWithColumnType<*>>,
): Op<Boolean>? = when (node) {
    is FilterLeaf -> {
        val parts = node.predicates.map { predicate ->
            predicateForField(expressions, predicate)
        }
        if (parts.isEmpty()) {
            null
        } else {
            parts.reduce { acc, op -> acc.and(op) }
        }
    }

    is FilterGroup -> {
        val parts = node.children.mapNotNull { child -> nodeToPredicate(child, expressions) }
        if (parts.isEmpty()) {
            null
        } else {
            when (node.combinator) {
                FilterCombinator.AND -> parts.reduce { acc, op -> acc.and(op) }
                FilterCombinator.OR -> parts.reduce { acc, op -> acc.or(op) }
                FilterCombinator.NOT -> not(parts.reduce { acc, op -> acc.and(op) })
            }
        }
    }
}

context(mappersModule: ColumnMappersModule?, options: FilterOptions)
internal fun predicateForField(
    expressions: Map<String, ExpressionWithColumnType<*>>,
    filter: FieldFilter,
): Op<Boolean> {
    val resolved = resolveField(expressions, filter.field)
    val inner = if (resolved.jsonPath.isNotEmpty()) {
        jsonPathPredicateFor(resolved.expression, resolved.jsonPath, filter)
    } else {
        predicateFor(resolved.expression, filter, filter.field)
    }
    return resolved.hops.foldRight(inner) { hop, acc -> hopPredicate(hop, acc, filter.field) }
}

/**
 * Wraps [inner], a predicate on what [hop] reads, in the `EXISTS` subquery that correlates the hop's
 * source with the reference the enclosing scope holds. A hop naming one of several targets is also
 * required, outside the subquery, to find that target in its discriminator.
 */
context(mappersModule: ColumnMappersModule?, options: FilterOptions)
internal fun hopPredicate(hop: ResolvedHop, inner: Op<Boolean>, field: String): Op<Boolean> {
    val correlated = exists(
        hop.source.selectAll().andWhere {
            @Suppress("UNCHECKED_CAST")
            (hop.idExpression as ExpressionWithColumnType<Any?>).eq(hop.referenceExpression as Column<Any?>) and inner
        },
    )
    val target = hop.target ?: return correlated
    val discriminator = checkNotNull(hop.discriminator) { "A hop naming target '$target' carries no discriminator" }
    // Read from the occurrence the reference is read from; a target is a tag, so case always matters.
    val scoped = (hop.referenceExpression.table as? Alias<*>)?.get(discriminator) ?: discriminator
    val chosen = context(options.copy(caseSensitiveStrings = true)) { eqValue(scoped, target, field) }
    return chosen and correlated
}

/**
 * Describes the table a reference column points at, so a nested field path can be turned into an
 * `EXISTS` subquery against it.
 *
 * @property referencedIdColumn Column on [referencedTable] the reference column is compared against.
 * @property referencedTable Table the nested property is read from.
 */
public data class ReferenceInfo(
    public val referencedIdColumn: Column<*>,
    public val referencedTable: Table,
)

/**
 * The table column behind [this] one. Exposed exposes a column of a table [Alias] — and of a
 * `QueryAlias`, which clones each column onto `table.alias(queryAlias.alias)` — as a clone on an
 * [Alias]; this unwraps such clones, through nested aliases, back to the column they were made from.
 */
internal fun Column<*>.originalColumn(): Column<*> {
    var column: Column<*> = this
    while (true) {
        val alias = column.table as? Alias<*> ?: return column
        column = alias.originalColumn(column) ?: return column
    }
}

internal fun resolveReference(column: Column<*>): ReferenceInfo? {
    // Try common Exposed internal names reflectively to locate the referenced column.
    val viaField = runCatching {
        val f = column.javaClass.getDeclaredField("referee")
        f.isAccessible = true
        f.get(column) as? Column<*>
    }.getOrNull()
    if (viaField != null) return ReferenceInfo(viaField, viaField.table)

    val viaGetter = runCatching {
        column.javaClass.methods
            .firstOrNull { it.name == "getReferee" && it.parameterCount == 0 }
            ?.invoke(column) as? Column<*>
    }.getOrNull()
    if (viaGetter != null) return ReferenceInfo(viaGetter, viaGetter.table)

    return null
}
