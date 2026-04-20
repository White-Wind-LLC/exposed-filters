package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.JsonColumnMarker
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
    val field = filter.field
    val dotIndex = field.indexOf('.')
    if (dotIndex < 0) {
        val expr = requireNotNull(expressions[field]) { "Unknown filter field: $field" }
        return predicateFor(expr, filter, field)
    }

    // Dot-path support:
    // 1) JSON/JSONB field path: payload.a.b.c
    // 2) Existing reference nested field: referenceField.nestedField
    val baseName = field.substring(0, dotIndex)
    val pathSegments = field.substring(dotIndex + 1).split('.')
    require(pathSegments.isNotEmpty() && pathSegments.none { it.isEmpty() }) { "Invalid nested field path: $field" }

    val baseExpr = checkNotNull(expressions[baseName]) { "Unknown filter field: $baseName" }
    if (baseExpr.columnType is JsonColumnMarker) {
        return jsonPathPredicateFor(baseExpr, pathSegments, filter)
    }

    val nestedName = pathSegments.joinToString(".")
    require(baseExpr is Column<*>) {
        "Nested field filters (e.g., '$field') are only supported for Column types, " +
                "not for computed expressions."
    }

    val refInfo = resolveReference(baseExpr)
        ?: error("Field $baseName is not a reference; cannot use nested property $nestedName")

    val targetColumns = refInfo.referencedTable.propertyToColumnMap()
    val targetColumn =
        checkNotNull(targetColumns[nestedName]) { "Unknown nested field: $nestedName for reference $baseName" }

    // Build subquery: select referenced id from target table where target predicate holds.
    val targetPredicate = predicateFor(targetColumn, filter, field)
    val subQuery = refInfo.referencedTable
        .selectAll()
        .andWhere {
            @Suppress("UNCHECKED_CAST")
            ((refInfo.referencedIdColumn as Column<Any?>).eq(baseExpr as Column<Any?>)) and targetPredicate
        }

    return exists(subQuery)
}

internal data class ReferenceInfo(
    val referencedIdColumn: Column<*>,
    val referencedTable: Table
)

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
