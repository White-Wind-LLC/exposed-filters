package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import ua.wwind.exposed.filters.core.*
import java.util.*
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

public fun Query.applyFiltersOn(table: Table, filterRequest: FilterRequest?): Query {
    if (filterRequest == null) return this
    val root = filterRequest.root
    val columns = table.propertyToColumnMap()
    val predicate = nodeToPredicate(root, columns) ?: return this
    return andWhere { predicate }
}

private fun Table.propertyToColumnMap(): Map<String, Column<*>> =
    this::class.memberProperties
        .mapNotNull { prop ->
            @Suppress("UNCHECKED_CAST")
            val p = prop as? KProperty1<Any, *> ?: return@mapNotNull null
            // Some properties can be non-public on generated tables; make accessible defensively
            p.isAccessible = true
            val value = runCatching { p.get(this) }.getOrNull()
            if (value is Column<*>) {
                prop.name to value
            } else null
        }
        .toMap()

private fun nodeToPredicate(node: FilterNode, columns: Map<String, Column<*>>): Op<Boolean>? = when (node) {
    is FilterLeaf -> {
        val parts = node.predicates.mapNotNull { p -> columns[p.field]?.let { c -> predicateFor(c, p) } }
        if (parts.isEmpty()) {
            null
        } else {
            parts.reduce { acc, op -> acc.and(op) }
        }
    }

    is FilterGroup -> {
        val parts = node.children.mapNotNull { child -> nodeToPredicate(child, columns) }
        if (parts.isEmpty()) {
            null
        } else {
            when (node.combinator) {
                FilterCombinator.AND -> parts.reduce { acc, op -> acc.and(op) }
                FilterCombinator.OR -> parts.reduce { acc, op -> acc.or(op) }
            }
        }
    }
}

private fun predicateFor(column: Column<*>, filter: FieldFilter): Op<Boolean> = with(SqlExpressionBuilder) {
    when (filter.operator) {
        FilterOperator.EQ -> eqValue(column, filter.values.firstOrNull())
        FilterOperator.NEQ -> not(eqValue(column, filter.values.firstOrNull()))
        FilterOperator.CONTAINS -> likeString(column, "%${filter.values.firstOrNull() ?: ""}%")
        FilterOperator.STARTS_WITH -> likeString(column, "${filter.values.firstOrNull() ?: ""}%")
        FilterOperator.ENDS_WITH -> likeString(column, "%${filter.values.firstOrNull() ?: ""}")
        FilterOperator.IN -> inListValue(column, filter.values)
        FilterOperator.NOT_IN -> not(inListValue(column, filter.values))
        FilterOperator.BETWEEN -> betweenValues(column, filter.values)
        FilterOperator.GT -> compareGreater(column, filter.values.firstOrNull())
        FilterOperator.GTE -> compareGreaterEq(column, filter.values.firstOrNull())
        FilterOperator.LT -> compareLess(column, filter.values.firstOrNull())
        FilterOperator.LTE -> compareLessEq(column, filter.values.firstOrNull())
        FilterOperator.IS_NULL -> column.isNull()
        FilterOperator.IS_NOT_NULL -> column.isNotNull()
    }
}

private fun SqlExpressionBuilder.eqValue(column: Column<*>, raw: String?): Op<Boolean> {
    requireNotNull(raw) { "EQ requires a value" }
    return when (column.columnType) {
        is IntegerColumnType -> (column as Column<Int>).eq(raw.toInt())
        is LongColumnType -> (column as Column<Long>).eq(raw.toLong())
        is ShortColumnType -> (column as Column<Short>).eq(raw.toShort())
        is DoubleColumnType -> (column as Column<Double>).eq(raw.toDouble())
        is VarCharColumnType, is TextColumnType -> (column as Column<String>).eq(raw)
        is UUIDColumnType -> (column as Column<UUID>).eq(UUID.fromString(raw))
        is BooleanColumnType -> (column as Column<Boolean>).eq(raw.toBooleanStrict())
        is EnumerationNameColumnType<*> -> {
            val enumValue = enumValueOf(column, raw)
            @Suppress("UNCHECKED_CAST")
            (column as Column<Enum<*>>).eq(enumValue)
        }

        else -> error("Unsupported equality for column ${column.name}")
    }
}

private fun SqlExpressionBuilder.likeString(column: Column<*>, pattern: String): Op<Boolean> =
    when (column.columnType) {
        is VarCharColumnType, is TextColumnType -> (column as Column<String>).like(pattern)
        else -> error("LIKE is only supported for string columns: ${column.name}")
    }

private fun SqlExpressionBuilder.inListValue(column: Column<*>, raws: List<String>): Op<Boolean> {
    require(raws.isNotEmpty()) { "IN requires at least one value" }
    return when (column.columnType) {
        is IntegerColumnType -> (column as Column<Int>).inList(raws.map(String::toInt))
        is LongColumnType -> (column as Column<Long>).inList(raws.map(String::toLong))
        is ShortColumnType -> (column as Column<Short>).inList(raws.map(String::toShort))
        is DoubleColumnType -> (column as Column<Double>).inList(raws.map(String::toDouble))
        is VarCharColumnType, is TextColumnType -> (column as Column<String>).inList(raws)
        is UUIDColumnType -> (column as Column<UUID>).inList(raws.map(UUID::fromString))
        is BooleanColumnType -> (column as Column<Boolean>).inList(raws.map(String::toBooleanStrict))
        is EnumerationNameColumnType<*> -> {
            @Suppress("UNCHECKED_CAST")
            (column as Column<Enum<*>>).inList(raws.map { enumValueOf(column, it) })
        }

        else -> error("Unsupported IN for column ${column.name}")
    }
}

private fun SqlExpressionBuilder.betweenValues(column: Column<*>, raws: List<String>): Op<Boolean> {
    require(raws.size == 2) { "BETWEEN requires exactly two values" }
    val (from, to) = raws
    return when (column.columnType) {
        is IntegerColumnType -> (column as Column<Int>).between(from.toInt(), to.toInt())
        is LongColumnType -> (column as Column<Long>).between(from.toLong(), to.toLong())
        is ShortColumnType -> (column as Column<Short>).between(from.toShort(), to.toShort())
        is DoubleColumnType -> (column as Column<Double>).between(from.toDouble(), to.toDouble())
        is VarCharColumnType, is TextColumnType -> (column as Column<String>).between(from, to)
        else -> error("Unsupported BETWEEN for column ${column.name}")
    }
}

private fun SqlExpressionBuilder.compareGreater(column: Column<*>, raw: String?): Op<Boolean> {
    requireNotNull(raw) { "Comparison requires a value" }
    return when (column.columnType) {
        is IntegerColumnType -> (column as Column<Int>).greater(raw.toInt())
        is LongColumnType -> (column as Column<Long>).greater(raw.toLong())
        is ShortColumnType -> (column as Column<Short>).greater(raw.toShort())
        is DoubleColumnType -> (column as Column<Double>).greater(raw.toDouble())
        is VarCharColumnType, is TextColumnType -> (column as Column<String>).greater(raw)
        is UUIDColumnType -> (column as Column<UUID>).greater(UUID.fromString(raw))
        else -> error("Unsupported comparison for column ${column.name}")
    }
}

private fun SqlExpressionBuilder.compareGreaterEq(column: Column<*>, raw: String?): Op<Boolean> {
    requireNotNull(raw) { "Comparison requires a value" }
    return when (column.columnType) {
        is IntegerColumnType -> (column as Column<Int>).greaterEq(raw.toInt())
        is LongColumnType -> (column as Column<Long>).greaterEq(raw.toLong())
        is ShortColumnType -> (column as Column<Short>).greaterEq(raw.toShort())
        is DoubleColumnType -> (column as Column<Double>).greaterEq(raw.toDouble())
        is VarCharColumnType, is TextColumnType -> (column as Column<String>).greaterEq(raw)
        is UUIDColumnType -> (column as Column<UUID>).greaterEq(UUID.fromString(raw))
        else -> error("Unsupported comparison for column ${column.name}")
    }
}

private fun SqlExpressionBuilder.compareLess(column: Column<*>, raw: String?): Op<Boolean> {
    requireNotNull(raw) { "Comparison requires a value" }
    return when (column.columnType) {
        is IntegerColumnType -> (column as Column<Int>).less(raw.toInt())
        is LongColumnType -> (column as Column<Long>).less(raw.toLong())
        is ShortColumnType -> (column as Column<Short>).less(raw.toShort())
        is DoubleColumnType -> (column as Column<Double>).less(raw.toDouble())
        is VarCharColumnType, is TextColumnType -> (column as Column<String>).less(raw)
        is UUIDColumnType -> (column as Column<UUID>).less(UUID.fromString(raw))
        else -> error("Unsupported comparison for column ${column.name}")
    }
}

private fun SqlExpressionBuilder.compareLessEq(column: Column<*>, raw: String?): Op<Boolean> {
    requireNotNull(raw) { "Comparison requires a value" }
    return when (column.columnType) {
        is IntegerColumnType -> (column as Column<Int>).lessEq(raw.toInt())
        is LongColumnType -> (column as Column<Long>).lessEq(raw.toLong())
        is ShortColumnType -> (column as Column<Short>).lessEq(raw.toShort())
        is DoubleColumnType -> (column as Column<Double>).lessEq(raw.toDouble())
        is VarCharColumnType, is TextColumnType -> (column as Column<String>).lessEq(raw)
        is UUIDColumnType -> (column as Column<UUID>).lessEq(UUID.fromString(raw))
        else -> error("Unsupported comparison for column ${column.name}")
    }
}

@Suppress("UNCHECKED_CAST")
private fun enumValueOf(column: Column<*>, name: String): Enum<*> {
    val type = column.columnType as EnumerationNameColumnType<*>
    val constants = type.klass.java.enumConstants as Array<out Enum<*>>
    return constants.first { it.name == name }
}
