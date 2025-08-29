@file:OptIn(kotlin.time.ExperimentalTime::class)

package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.EnumerationNameColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ShortColumnType
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UUIDColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterCombinator
import ua.wwind.exposed.filters.core.FilterGroup
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterNode
import ua.wwind.exposed.filters.core.FilterOperator
import ua.wwind.exposed.filters.core.FilterRequest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.time.Instant
import java.sql.Date as SqlDate
import java.sql.Timestamp as SqlTimestamp

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
        val parts = node.predicates.map { predicate ->
            predicateForField(columns, predicate)
        }
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

private fun predicateForField(rootColumns: Map<String, Column<*>>, filter: FieldFilter): Op<Boolean> {
    val field = filter.field
    val dotIndex = field.indexOf('.')
    if (dotIndex < 0) {
        val column = requireNotNull(rootColumns[field]) { "Unknown filter field: $field" }
        return predicateFor(column, filter)
    }

    val baseName = field.substring(0, dotIndex)
    val nestedName = field.substring(dotIndex + 1)
    require(nestedName.isNotEmpty()) { "Invalid nested field path: $field" }

    val baseColumn = checkNotNull(rootColumns[baseName]) { "Unknown filter field: $baseName" }
    val refInfo = resolveReference(baseColumn)
        ?: error("Field $baseName is not a reference; cannot use nested property $nestedName")

    val targetColumns = refInfo.referencedTable.propertyToColumnMap()
    val targetColumn =
        checkNotNull(targetColumns[nestedName]) { "Unknown nested field: $nestedName for reference $baseName" }

    // Build subquery: select referenced id from target table where target predicate holds
    val targetPredicate = predicateFor(targetColumn, filter)
    val subQuery = refInfo.referencedTable
        .selectAll()
        .andWhere {
            with(SqlExpressionBuilder) {
                @Suppress("UNCHECKED_CAST")
                ((refInfo.referencedIdColumn as Column<Any?>).eq(baseColumn as Column<Any?>)) and targetPredicate
            }
        }

    return exists(subQuery)
}

private data class ReferenceInfo(
    val referencedIdColumn: Column<*>,
    val referencedTable: Table
)

private fun resolveReference(column: Column<*>): ReferenceInfo? {
    // Try common Exposed internal names reflectively to locate the referenced column
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

    // Fallback: if EntityID column type, attempt to find table by naming convention is unreliable; so return null
    return null
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
    if (column.columnType is EntityIDColumnType<*>) {
        return eqEntityIdValue(column as Column<EntityID<*>>, raw)
    }
    if (isDateOnlyColumn(column)) {
        val value = parseDateForColumn(column, raw)
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Any>).eq(value)
    }
    if (isTimestampColumn(column)) {
        val value = parseTimestampForColumn(column, raw)
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Any>).eq(value)
    }
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
    if (column.columnType is EntityIDColumnType<*>) {
        return inListEntityIdValue(column, raws)
    }
    if (isDateOnlyColumn(column)) {
        val values: List<Any> = raws.map { parseDateForColumn(column, it) }
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Any>).inList(values)
    }
    if (isTimestampColumn(column)) {
        val values: List<Any> = raws.map { parseTimestampForColumn(column, it) }
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Any>).inList(values)
    }
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
    if (isDateOnlyColumn(column)) {
        val left = parseDateForColumn(column, from)
        val right = parseDateForColumn(column, to)
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Comparable<Any>>).between(left as Comparable<Any>, right as Comparable<Any>)
    }
    if (isTimestampColumn(column)) {
        val left = parseTimestampForColumn(column, from)
        val right = parseTimestampForColumn(column, to)
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Comparable<Any>>).between(left as Comparable<Any>, right as Comparable<Any>)
    }
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
    if (isDateOnlyColumn(column)) {
        val value = parseDateForColumn(column, raw)
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Comparable<Any>>).greater(value as Comparable<Any>)
    }
    if (isTimestampColumn(column)) {
        val value = parseTimestampForColumn(column, raw)
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Comparable<Any>>).greater(value as Comparable<Any>)
    }
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
    if (isDateOnlyColumn(column)) {
        val value = parseDateForColumn(column, raw)
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Comparable<Any>>).greaterEq(value as Comparable<Any>)
    }
    if (isTimestampColumn(column)) {
        val value = parseTimestampForColumn(column, raw)
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Comparable<Any>>).greaterEq(value as Comparable<Any>)
    }
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
    if (isDateOnlyColumn(column)) {
        val value = parseDateForColumn(column, raw)
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Comparable<Any>>).less(value as Comparable<Any>)
    }
    if (isTimestampColumn(column)) {
        val value = parseTimestampForColumn(column, raw)
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Comparable<Any>>).less(value as Comparable<Any>)
    }
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
    if (isDateOnlyColumn(column)) {
        val value = parseDateForColumn(column, raw)
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Comparable<Any>>).lessEq(value as Comparable<Any>)
    }
    if (isTimestampColumn(column)) {
        val value = parseTimestampForColumn(column, raw)
        @Suppress("UNCHECKED_CAST")
        return (column as Column<Comparable<Any>>).lessEq(value as Comparable<Any>)
    }
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

private fun SqlExpressionBuilder.eqEntityIdValue(column: Column<EntityID<*>>, raw: String): Op<Boolean> {
    return when (rawColumnTypeOf(column)) {
        is IntegerColumnType -> (column as Column<EntityID<Int>>).eq(raw.toInt())
        is LongColumnType -> (column as Column<EntityID<Long>>).eq(raw.toLong())
        is ShortColumnType -> (column as Column<EntityID<Short>>).eq(raw.toShort())
        is VarCharColumnType -> (column as Column<EntityID<String>>).eq(raw)
        is UUIDColumnType -> (column as Column<EntityID<UUID>>).eq(UUID.fromString(raw))
        else -> {
            error("Unsupported equality for column ${column.name}")
        }
    }
}

private fun SqlExpressionBuilder.inListEntityIdValue(column: Column<*>, raws: List<String>): Op<Boolean> {
    return when (rawColumnTypeOf(column)) {
        is IntegerColumnType -> (column as Column<EntityID<Int>>).inList(raws.map(String::toInt))
        is LongColumnType -> (column as Column<EntityID<Long>>).inList(raws.map(String::toLong))
        is ShortColumnType -> (column as Column<EntityID<Short>>).inList(raws.map(String::toShort))
        is VarCharColumnType -> (column as Column<EntityID<String>>).inList(raws)
        is UUIDColumnType -> (column as Column<EntityID<UUID>>).inList(raws.map(UUID::fromString))
        else -> {
            error("Unsupported IN for column ${column.name}")
        }
    }
}

private fun rawColumnTypeOf(column: Column<*>): IColumnType<*> {
    val ct = column.columnType
    val isEntityId = ct is EntityIDColumnType<*>
    if (!isEntityId) return ct
    val idColumn = ct.javaClass.methods
        .firstOrNull { it.name == "getIdColumn" && it.parameterCount == 0 }
        ?.invoke(ct) as? Column<*>
    if (idColumn == null) {
        error("Cannot access idColumn for EntityID column ${column.name}")
    }
    return idColumn.columnType
}

// --- Date support helpers ---

private fun isDateOnlyColumn(column: Column<*>): Boolean {
    val ct = column.columnType
    val typeName = ct.javaClass.name
    val simple = ct.javaClass.simpleName
    val looksLikeLocalDate =
        simple.contains("LocalDate", ignoreCase = true) && !simple.contains("Time", ignoreCase = true)
    val looksLikeSqlDate = simple == "DateColumnType" && !typeName.contains(
        "DateTime",
        ignoreCase = true
    ) && !typeName.contains("Timestamp", ignoreCase = true)
    return looksLikeLocalDate || looksLikeSqlDate
}

private fun parseDateForColumn(column: Column<*>, raw: String): Any {
    // Prefer kotlinx.datetime.LocalDate for kotlin-datetime columns
    if (usesKotlinxLocalDate(column)) {
        return parseKotlinxLocalDate(raw)
    }
    val javaLocalDate: LocalDate = try {
        LocalDate.parse(raw)
    } catch (ex: DateTimeParseException) {
        throw IllegalArgumentException("Invalid date format for ${column.name}: '$raw'. Expected ISO-8601 date (YYYY-MM-DD)")
    }
    // If the column is backed by java.sql.Date, convert accordingly
    if (usesSqlDate(column)) return SqlDate.valueOf(javaLocalDate)
    // Otherwise, assume java.time.LocalDate
    return javaLocalDate
}

private fun usesSqlDate(column: Column<*>): Boolean {
    val simple = column.columnType.javaClass.simpleName
    return simple == "DateColumnType"
}

private fun usesKotlinxLocalDate(column: Column<*>): Boolean {
    val type = column.columnType.javaClass
    val simple = type.simpleName
    val name = type.name
    return simple.contains("KotlinLocalDateColumnType", ignoreCase = true) || name.contains(
        "datetime",
        ignoreCase = true
    ) && simple.contains("LocalDate", ignoreCase = true)
}

private fun isTimestampColumn(column: Column<*>): Boolean {
    val ct = column.columnType
    val type = ct.javaClass
    val simple = type.simpleName
    val name = type.name
    val looksLikeLocalDateTime = simple.contains("LocalDateTime", ignoreCase = true)
    val looksLikeInstant = simple.contains("Instant", ignoreCase = true)
    val looksLikeTimestamp = simple == "TimestampColumnType" || name.contains("DateTime", ignoreCase = true)
    return looksLikeLocalDateTime || looksLikeInstant || looksLikeTimestamp
}

private fun parseTimestampForColumn(column: Column<*>, raw: String): Any {
    val ldt = parseLocalDateTimeFlexible(raw)
    return when {
        usesSqlTimestamp(column) -> SqlTimestamp.valueOf(ldt)
        usesInstant(column) -> Instant.fromEpochMilliseconds(ldt.toInstant(ZoneOffset.UTC).toEpochMilli())
        else -> ldt
    }
}

private fun parseLocalDateTimeFlexible(raw: String): LocalDateTime {
    val normalized = if (raw.contains(' ') && !raw.contains('T')) raw.replace(' ', 'T') else raw
    // Try LocalDateTime first (supports seconds and fractional seconds in ISO), else fall back to LocalDate
    val ldt = runCatching { LocalDateTime.parse(normalized) }.getOrNull()
    if (ldt != null) return ldt
    val ld = try {
        LocalDate.parse(normalized)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Invalid datetime format: '$raw'. Expected ISO-8601 date-time or date (YYYY-MM-DD[THH:MM[:SS]])")
    }
    return ld.atStartOfDay()
}

private fun usesSqlTimestamp(column: Column<*>): Boolean {
    val simple = column.columnType.javaClass.simpleName
    return simple == "TimestampColumnType" || simple.contains("DateTime", ignoreCase = true)
}

private fun usesInstant(column: Column<*>): Boolean {
    val simple = column.columnType.javaClass.simpleName
    return simple.contains("Instant", ignoreCase = true)
}

private fun parseKotlinxLocalDate(raw: String): Any {
    // Reflectively call kotlinx.datetime.LocalDate.parse(raw) to avoid compile-time dependency
    try {
        val clazz = Class.forName("kotlinx.datetime.LocalDate")
        val companionField = clazz.getDeclaredField("Companion")
        val companion = companionField.get(null)
        val method = companion.javaClass.methods.firstOrNull { it.name == "parse" && it.parameterCount == 1 }
            ?: throw IllegalArgumentException("kotlinx.datetime.LocalDate.Companion.parse not found")
        return method.invoke(companion, raw)
    } catch (ex: Exception) {
        throw IllegalArgumentException(
            "Invalid date format: '$raw'. Expected ISO-8601 date (YYYY-MM-DD)",
            ex
        )
    }
}
