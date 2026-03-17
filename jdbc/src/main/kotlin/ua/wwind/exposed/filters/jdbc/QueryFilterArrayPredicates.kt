package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LiteralOp
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ShortColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.anyFrom
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterOperator
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Predicate builder for array column types (e.g., INT[], TEXT[]).
 */
@OptIn(ExperimentalUuidApi::class)
context(mappersModule: ColumnMappersModule?)
internal fun arrayPredicateFor(
    expr: ExpressionWithColumnType<*>,
    filter: FieldFilter,
    fieldName: String
): Op<Boolean> {
    val arrayColumnType = expr.columnType as ArrayColumnType<*, *>
    val delegate = arrayColumnType.delegate

    @Suppress("UNCHECKED_CAST")
    val arrayExpr = expr as ExpressionWithColumnType<List<Any>>
    val containsOps = filter.values.map { raw ->
        val parsed = parseArrayElementValue(delegate, raw, fieldName)
        @Suppress("UNCHECKED_CAST")
        (LiteralOp(delegate as IColumnType<Any>, parsed) eq anyFrom(arrayExpr))
    }
    return when (filter.operator) {
        FilterOperator.IN -> containsOps.reduce { acc, op -> acc.or(op) }
        FilterOperator.NOT_IN -> not(containsOps.reduce { acc, op -> acc.or(op) })
        else -> error(
            "Operator ${filter.operator} is not supported for array field '$fieldName'. " +
                    "Use IN or NOT_IN."
        )
    }
}

/**
 * Parses a raw string value into the appropriate type for array element comparison.
 */
@OptIn(ExperimentalUuidApi::class)
context(mappersModule: ColumnMappersModule?)
internal fun parseArrayElementValue(
    delegate: IColumnType<*>,
    raw: String,
    fieldName: String
): Any {
    if (mappersModule != null) {
        @Suppress("UNCHECKED_CAST")
        val customMapped = mappersModule.tryMap(delegate as IColumnType<Any>, raw)
        if (customMapped != null) return customMapped
    }
    return when (delegate) {
        is IntegerColumnType -> raw.toInt()
        is LongColumnType -> raw.toLong()
        is ShortColumnType -> raw.toShort()
        is DoubleColumnType -> raw.toDouble()
        is VarCharColumnType, is TextColumnType -> raw
        is UUIDColumnType -> java.util.UUID.fromString(raw)
        is UuidColumnType -> Uuid.parse(raw)
        is BooleanColumnType -> raw.toBooleanStrict()
        is org.jetbrains.exposed.v1.core.EnumerationNameColumnType<*> -> enumValueOf(delegate, raw)
        else -> error("Unsupported array element type for field '$fieldName'")
    }
}