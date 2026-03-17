package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.EnumerationNameColumnType
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ShortColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.not
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterOperator
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Core predicate builder for field filters - dispatches to specialized handlers.
 */
context(mappersModule: ColumnMappersModule?)
internal fun predicateFor(
    expr: ExpressionWithColumnType<*>,
    filter: FieldFilter,
    fieldName: String
): Op<Boolean> {
    // Empty array handling: return FALSE for IN/BETWEEN, TRUE for NOT_IN.
    if ((filter.operator == FilterOperator.IN ||
                filter.operator == FilterOperator.BETWEEN) &&
        filter.values.isEmpty()
    ) {
        return Op.FALSE
    }
    if (filter.operator == FilterOperator.NOT_IN && filter.values.isEmpty()) {
        return Op.TRUE
    }
    if (expr.columnType is ArrayColumnType<*, *>) {
        return arrayPredicateFor(expr, filter, fieldName)
    }

    return when (filter.operator) {
        FilterOperator.EQ -> eqValue(expr, filter.values.firstOrNull(), fieldName)
        FilterOperator.NEQ -> not(eqValue(expr, filter.values.firstOrNull(), fieldName))
        FilterOperator.CONTAINS -> likeString(expr, "%${filter.values.firstOrNull() ?: ""}%", fieldName)
        FilterOperator.STARTS_WITH -> likeString(expr, "${filter.values.firstOrNull() ?: ""}%", fieldName)
        FilterOperator.ENDS_WITH -> likeString(expr, "%${filter.values.firstOrNull() ?: ""}", fieldName)
        FilterOperator.IN -> inListValue(expr, filter.values, fieldName)
        FilterOperator.NOT_IN -> not(inListValue(expr, filter.values, fieldName))
        FilterOperator.BETWEEN -> betweenValues(expr, filter.values, fieldName)
        FilterOperator.GT -> compareGreater(expr, filter.values.firstOrNull(), fieldName)
        FilterOperator.GTE -> compareGreaterEq(expr, filter.values.firstOrNull(), fieldName)
        FilterOperator.LT -> compareLess(expr, filter.values.firstOrNull(), fieldName)
        FilterOperator.LTE -> compareLessEq(expr, filter.values.firstOrNull(), fieldName)
        FilterOperator.IS_NULL -> expr.isNull()
        FilterOperator.IS_NOT_NULL -> expr.isNotNull()
    }
}

/**
 * LIKE predicate for string columns.
 */
internal fun likeString(
    expr: ExpressionWithColumnType<*>,
    pattern: String,
    fieldName: String
): Op<Boolean> = when (expr.columnType) {
    is VarCharColumnType, is TextColumnType -> (expr as ExpressionWithColumnType<String>).like(pattern)
    else -> error("LIKE is only supported for string fields: '$fieldName'")
}

/**
 * Equality predicate for a single value.
 */
@OptIn(ExperimentalUuidApi::class)
context(mappersModule: ColumnMappersModule?)
internal fun eqValue(
    expr: ExpressionWithColumnType<*>,
    raw: String?,
    fieldName: String
): Op<Boolean> {
    requireNotNull(raw) { "EQ requires a value" }

    // First, try custom mappers.
    if (mappersModule != null) {
        val customMapped = mappersModule.tryMap(expr.columnType, raw)
        if (customMapped != null) {
            @Suppress("UNCHECKED_CAST")
            return (expr as ExpressionWithColumnType<Any>).eq(customMapped)
        }
    }

    // Handle EntityID columns (only applicable for Column type).
    if (expr is org.jetbrains.exposed.v1.core.Column<*> && expr.columnType is EntityIDColumnType<*>) {
        @Suppress("UNCHECKED_CAST")
        return eqEntityIdValue(expr as Column<EntityID<*>>, raw, fieldName)
    }

    // Handle date/time expressions.
    if (isDateOnlyExpr(expr)) {
        val value = parseDateForExpr(expr, raw, fieldName)
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Any>).eq(value)
    }
    if (isTimestampExpr(expr)) {
        val value = parseTimestampForExpr(expr, raw)
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Any>).eq(value)
    }

    // Built-in type mappers.
    return when (expr.columnType) {
        is IntegerColumnType -> (expr as ExpressionWithColumnType<Int>).eq(raw.toInt())
        is LongColumnType -> (expr as ExpressionWithColumnType<Long>).eq(raw.toLong())
        is ShortColumnType -> (expr as ExpressionWithColumnType<Short>).eq(raw.toShort())
        is DoubleColumnType -> (expr as ExpressionWithColumnType<Double>).eq(raw.toDouble())
        is VarCharColumnType, is TextColumnType -> (expr as ExpressionWithColumnType<String>).eq(raw)
        is UUIDColumnType -> (expr as ExpressionWithColumnType<java.util.UUID>).eq(java.util.UUID.fromString(raw))
        is UuidColumnType -> (expr as ExpressionWithColumnType<Uuid>).eq(Uuid.parse(raw))
        is BooleanColumnType -> (expr as ExpressionWithColumnType<Boolean>).eq(raw.toBooleanStrict())
        is EnumerationNameColumnType<*> -> {
            val enumValue = enumValueOf(expr, raw)
            @Suppress("UNCHECKED_CAST")
            (expr as ExpressionWithColumnType<Enum<*>>).eq(enumValue)
        }

        else -> error("Unsupported equality for field '$fieldName'")
    }
}

/**
 * IN predicate for a list of values.
 */
@OptIn(ExperimentalUuidApi::class)
context(mappersModule: ColumnMappersModule?)
internal fun inListValue(
    expr: ExpressionWithColumnType<*>,
    raws: List<String>,
    fieldName: String
): Op<Boolean> {
    if (raws.isEmpty()) return Op.FALSE

    // First, try custom mappers.
    if (mappersModule != null) {
        val customMapped = raws.mapNotNull { raw ->
            mappersModule.tryMap(expr.columnType, raw)
        }
        if (customMapped.size == raws.size) {
            @Suppress("UNCHECKED_CAST")
            return (expr as ExpressionWithColumnType<Any>).inList(customMapped)
        }
    }

    // Handle EntityID columns.
    if (expr is org.jetbrains.exposed.v1.core.Column<*> && expr.columnType is EntityIDColumnType<*>) {
        return inListEntityIdValue(expr, raws, fieldName)
    }

    // Handle date/time expressions.
    if (isDateOnlyExpr(expr)) {
        val values: List<Any> = raws.map { parseDateForExpr(expr, it, fieldName) }
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Any>).inList(values)
    }
    if (isTimestampExpr(expr)) {
        val values: List<Any> = raws.map { parseTimestampForExpr(expr, it) }
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Any>).inList(values)
    }

    return when (expr.columnType) {
        is IntegerColumnType -> (expr as ExpressionWithColumnType<Int>).inList(raws.map(String::toInt))
        is LongColumnType -> (expr as ExpressionWithColumnType<Long>).inList(raws.map(String::toLong))
        is ShortColumnType -> (expr as ExpressionWithColumnType<Short>).inList(raws.map(String::toShort))
        is DoubleColumnType -> (expr as ExpressionWithColumnType<Double>).inList(raws.map(String::toDouble))
        is VarCharColumnType, is TextColumnType -> (expr as ExpressionWithColumnType<String>).inList(raws)
        is UUIDColumnType -> (expr as ExpressionWithColumnType<java.util.UUID>).inList(raws.map(java.util.UUID::fromString))
        is UuidColumnType -> (expr as ExpressionWithColumnType<Uuid>).inList(raws.map(Uuid::parse))
        is BooleanColumnType -> (expr as ExpressionWithColumnType<Boolean>).inList(raws.map(String::toBooleanStrict))
        is EnumerationNameColumnType<*> -> {
            @Suppress("UNCHECKED_CAST")
            (expr as ExpressionWithColumnType<Enum<*>>).inList(raws.map { enumValueOf(expr, it) })
        }

        else -> error("Unsupported IN for field '$fieldName'")
    }
}

/**
 * BETWEEN predicate for range of values.
 */
context(mappersModule: ColumnMappersModule?)
internal fun betweenValues(
    expr: ExpressionWithColumnType<*>,
    raws: List<String>,
    fieldName: String
): Op<Boolean> {
    if (raws.isEmpty()) return Op.FALSE
    require(raws.size == 2) { "BETWEEN requires exactly two values" }
    val (from, to) = raws

    // First, try custom mappers.
    if (mappersModule != null) {
        val left = mappersModule.tryMap(expr.columnType, from)
        val right = mappersModule.tryMap(expr.columnType, to)
        if (left != null && right != null) {
            require(left is Comparable<*>) { "BETWEEN requires comparable values for field '$fieldName'" }
            require(right is Comparable<*>) { "BETWEEN requires comparable values for field '$fieldName'" }
            @Suppress("UNCHECKED_CAST")
            return (expr as ExpressionWithColumnType<Comparable<Any>>).between(
                left as Comparable<Any>,
                right as Comparable<Any>
            )
        }
    }

    // Handle date/time expressions.
    if (isDateOnlyExpr(expr)) {
        val left = parseDateForExpr(expr, from, fieldName)
        val right = parseDateForExpr(expr, to, fieldName)
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Comparable<Any>>).between(
            left as Comparable<Any>,
            right as Comparable<Any>
        )
    }
    if (isTimestampExpr(expr)) {
        val left = parseTimestampForExpr(expr, from)
        val right = parseTimestampForExpr(expr, to)
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Comparable<Any>>).between(
            left as Comparable<Any>,
            right as Comparable<Any>
        )
    }

    return when (expr.columnType) {
        is IntegerColumnType -> (expr as ExpressionWithColumnType<Int>).between(from.toInt(), to.toInt())
        is LongColumnType -> (expr as ExpressionWithColumnType<Long>).between(from.toLong(), to.toLong())
        is ShortColumnType -> (expr as ExpressionWithColumnType<Short>).between(from.toShort(), to.toShort())
        is DoubleColumnType -> (expr as ExpressionWithColumnType<Double>).between(from.toDouble(), to.toDouble())
        is VarCharColumnType, is TextColumnType -> (expr as ExpressionWithColumnType<String>).between(from, to)
        else -> error("Unsupported BETWEEN for field '$fieldName'")
    }
}

/**
 * Enum value resolution by name.
 */
@Suppress("UNCHECKED_CAST")
internal fun enumValueOf(expr: ExpressionWithColumnType<*>, name: String): Enum<*> {
    val type = expr.columnType as EnumerationNameColumnType<*>
    return enumValueOf(type, name)
}

@Suppress("UNCHECKED_CAST")
internal fun enumValueOf(type: EnumerationNameColumnType<*>, name: String): Enum<*> {
    val constants = type.klass.java.enumConstants as Array<out Enum<*>>
    return constants.first { it.name == name }
}