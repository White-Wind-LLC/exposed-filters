package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ShortColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.lowerCase
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Greater-than comparison predicate.
 */
@OptIn(ExperimentalUuidApi::class)
context(mappersModule: ColumnMappersModule?, options: FilterOptions)
internal fun compareGreater(
    expr: ExpressionWithColumnType<*>,
    raw: String?,
    fieldName: String
): Op<Boolean> {
    requireNotNull(raw) { "Comparison requires a value" }

    // First, try custom mappers.
    if (mappersModule != null) {
        val mapped = mappersModule.tryMap(expr.columnType, raw)
        if (mapped != null) {
            require(mapped is Comparable<*>) { "Comparison requires comparable value for field '$fieldName'" }
            @Suppress("UNCHECKED_CAST")
            return (expr as ExpressionWithColumnType<Comparable<Any>>).greater(mapped as Comparable<Any>)
        }
    }

    // Handle date/time expressions.
    if (isDateOnlyExpr(expr)) {
        val value = parseDateForExpr(expr, raw, fieldName)
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Comparable<Any>>).greater(value as Comparable<Any>)
    }
    if (isTimestampExpr(expr)) {
        val value = parseTimestampForExpr(expr, raw)
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Comparable<Any>>).greater(value as Comparable<Any>)
    }

    return when (expr.columnType) {
        is IntegerColumnType -> (expr as ExpressionWithColumnType<Int>).greater(raw.toInt())
        is LongColumnType -> (expr as ExpressionWithColumnType<Long>).greater(raw.toLong())
        is ShortColumnType -> (expr as ExpressionWithColumnType<Short>).greater(raw.toShort())
        is DoubleColumnType -> (expr as ExpressionWithColumnType<Double>).greater(raw.toDouble())
        is VarCharColumnType, is TextColumnType -> {
            @Suppress("UNCHECKED_CAST")
            val stringExpr = expr as ExpressionWithColumnType<String>
            if (options.caseSensitiveStrings) stringExpr.greater(raw)
            else stringExpr.lowerCase().greater(raw.lowercase())
        }
        is UUIDColumnType -> (expr as ExpressionWithColumnType<java.util.UUID>).greater(java.util.UUID.fromString(raw))
        is UuidColumnType -> (expr as ExpressionWithColumnType<Uuid>).greater(Uuid.parse(raw))
        else -> error("Unsupported comparison for field '$fieldName'")
    }
}

/**
 * Greater-than-or-equal comparison predicate.
 */
@OptIn(ExperimentalUuidApi::class)
context(mappersModule: ColumnMappersModule?, options: FilterOptions)
internal fun compareGreaterEq(
    expr: ExpressionWithColumnType<*>,
    raw: String?,
    fieldName: String
): Op<Boolean> {
    requireNotNull(raw) { "Comparison requires a value" }

    // First, try custom mappers.
    if (mappersModule != null) {
        val mapped = mappersModule.tryMap(expr.columnType, raw)
        if (mapped != null) {
            require(mapped is Comparable<*>) { "Comparison requires comparable value for field '$fieldName'" }
            @Suppress("UNCHECKED_CAST")
            return (expr as ExpressionWithColumnType<Comparable<Any>>).greaterEq(mapped as Comparable<Any>)
        }
    }

    // Handle date/time expressions.
    if (isDateOnlyExpr(expr)) {
        val value = parseDateForExpr(expr, raw, fieldName)
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Comparable<Any>>).greaterEq(value as Comparable<Any>)
    }
    if (isTimestampExpr(expr)) {
        val value = parseTimestampForExpr(expr, raw)
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Comparable<Any>>).greaterEq(value as Comparable<Any>)
    }

    return when (expr.columnType) {
        is IntegerColumnType -> (expr as ExpressionWithColumnType<Int>).greaterEq(raw.toInt())
        is LongColumnType -> (expr as ExpressionWithColumnType<Long>).greaterEq(raw.toLong())
        is ShortColumnType -> (expr as ExpressionWithColumnType<Short>).greaterEq(raw.toShort())
        is DoubleColumnType -> (expr as ExpressionWithColumnType<Double>).greaterEq(raw.toDouble())
        is VarCharColumnType, is TextColumnType -> {
            @Suppress("UNCHECKED_CAST")
            val stringExpr = expr as ExpressionWithColumnType<String>
            if (options.caseSensitiveStrings) stringExpr.greaterEq(raw)
            else stringExpr.lowerCase().greaterEq(raw.lowercase())
        }
        is UUIDColumnType -> (expr as ExpressionWithColumnType<java.util.UUID>).greaterEq(java.util.UUID.fromString(raw))
        is UuidColumnType -> (expr as ExpressionWithColumnType<Uuid>).greaterEq(Uuid.parse(raw))
        else -> error("Unsupported comparison for field '$fieldName'")
    }
}

/**
 * Less-than comparison predicate.
 */
@OptIn(ExperimentalUuidApi::class)
context(mappersModule: ColumnMappersModule?, options: FilterOptions)
internal fun compareLess(
    expr: ExpressionWithColumnType<*>,
    raw: String?,
    fieldName: String
): Op<Boolean> {
    requireNotNull(raw) { "Comparison requires a value" }

    // First, try custom mappers.
    if (mappersModule != null) {
        val mapped = mappersModule.tryMap(expr.columnType, raw)
        if (mapped != null) {
            require(mapped is Comparable<*>) { "Comparison requires comparable value for field '$fieldName'" }
            @Suppress("UNCHECKED_CAST")
            return (expr as ExpressionWithColumnType<Comparable<Any>>).less(mapped as Comparable<Any>)
        }
    }

    // Handle date/time expressions.
    if (isDateOnlyExpr(expr)) {
        val value = parseDateForExpr(expr, raw, fieldName)
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Comparable<Any>>).less(value as Comparable<Any>)
    }
    if (isTimestampExpr(expr)) {
        val value = parseTimestampForExpr(expr, raw)
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Comparable<Any>>).less(value as Comparable<Any>)
    }

    return when (expr.columnType) {
        is IntegerColumnType -> (expr as ExpressionWithColumnType<Int>).less(raw.toInt())
        is LongColumnType -> (expr as ExpressionWithColumnType<Long>).less(raw.toLong())
        is ShortColumnType -> (expr as ExpressionWithColumnType<Short>).less(raw.toShort())
        is DoubleColumnType -> (expr as ExpressionWithColumnType<Double>).less(raw.toDouble())
        is VarCharColumnType, is TextColumnType -> {
            @Suppress("UNCHECKED_CAST")
            val stringExpr = expr as ExpressionWithColumnType<String>
            if (options.caseSensitiveStrings) stringExpr.less(raw)
            else stringExpr.lowerCase().less(raw.lowercase())
        }
        is UUIDColumnType -> (expr as ExpressionWithColumnType<java.util.UUID>).less(java.util.UUID.fromString(raw))
        is UuidColumnType -> (expr as ExpressionWithColumnType<Uuid>).less(Uuid.parse(raw))
        else -> error("Unsupported comparison for field '$fieldName'")
    }
}

/**
 * Less-than-or-equal comparison predicate.
 */
@OptIn(ExperimentalUuidApi::class)
context(mappersModule: ColumnMappersModule?, options: FilterOptions)
internal fun compareLessEq(
    expr: ExpressionWithColumnType<*>,
    raw: String?,
    fieldName: String
): Op<Boolean> {
    requireNotNull(raw) { "Comparison requires a value" }

    // First, try custom mappers.
    if (mappersModule != null) {
        val mapped = mappersModule.tryMap(expr.columnType, raw)
        if (mapped != null) {
            require(mapped is Comparable<*>) { "Comparison requires comparable value for field '$fieldName'" }
            @Suppress("UNCHECKED_CAST")
            return (expr as ExpressionWithColumnType<Comparable<Any>>).lessEq(mapped as Comparable<Any>)
        }
    }

    // Handle date/time expressions.
    if (isDateOnlyExpr(expr)) {
        val value = parseDateForExpr(expr, raw, fieldName)
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Comparable<Any>>).lessEq(value as Comparable<Any>)
    }
    if (isTimestampExpr(expr)) {
        val value = parseTimestampForExpr(expr, raw)
        @Suppress("UNCHECKED_CAST")
        return (expr as ExpressionWithColumnType<Comparable<Any>>).lessEq(value as Comparable<Any>)
    }

    return when (expr.columnType) {
        is IntegerColumnType -> (expr as ExpressionWithColumnType<Int>).lessEq(raw.toInt())
        is LongColumnType -> (expr as ExpressionWithColumnType<Long>).lessEq(raw.toLong())
        is ShortColumnType -> (expr as ExpressionWithColumnType<Short>).lessEq(raw.toShort())
        is DoubleColumnType -> (expr as ExpressionWithColumnType<Double>).lessEq(raw.toDouble())
        is VarCharColumnType, is TextColumnType -> {
            @Suppress("UNCHECKED_CAST")
            val stringExpr = expr as ExpressionWithColumnType<String>
            if (options.caseSensitiveStrings) stringExpr.lessEq(raw)
            else stringExpr.lowerCase().lessEq(raw.lowercase())
        }
        is UUIDColumnType -> (expr as ExpressionWithColumnType<java.util.UUID>).lessEq(java.util.UUID.fromString(raw))
        is UuidColumnType -> (expr as ExpressionWithColumnType<Uuid>).lessEq(Uuid.parse(raw))
        else -> error("Unsupported comparison for field '$fieldName'")
    }
}