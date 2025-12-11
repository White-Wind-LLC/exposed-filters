@file:OptIn(kotlin.time.ExperimentalTime::class)

package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.EnumerationNameColumnType
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ShortColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UUIDColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
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

/**
 * Applies filters to a query using columns from the given [ColumnSet].
 *
 * - For [Table]: filter field names are matched against **Kotlin property names** (e.g., `warehouseId`)
 * - For other [ColumnSet] types (Join, Alias, etc.): filter field names are matched against
 *   **SQL column names** (e.g., `warehouse_id`)
 *
 * Example with Table:
 * ```
 * UserTable.selectAll()
 *     .applyFiltersOn(UserTable, filter)  // field: "name", "age" (property names)
 * ```
 *
 * Example with Join:
 * ```
 * val join = UserTable innerJoin ProductTable
 * join.selectAll()
 *     .applyFiltersOn(join, filter)  // field: "name", "title", "warehouse_id" (SQL names)
 * ```
 */
public fun Query.applyFiltersOn(columnSet: ColumnSet, filterRequest: FilterRequest?): Query {
    if (filterRequest == null) return this
    val root = filterRequest.root
    val columns = columnSet.toColumnMap()
    val predicate = context(null as ColumnMappersModule?) { nodeToPredicate(root, columns) } ?: return this
    return andWhere { predicate }
}

/**
 * Same as [applyFiltersOn] but allows passing a [mappersModule] to handle predicates
 * for custom column types when standard mappings do not apply.
 */
public fun Query.applyFiltersOn(
    columnSet: ColumnSet,
    filterRequest: FilterRequest?,
    mappersModule: ColumnMappersModule
): Query {
    if (filterRequest == null) return this
    val root = filterRequest.root
    val columns = columnSet.toColumnMap()
    val predicate = context(mappersModule) { nodeToPredicate(root, columns) } ?: return this
    return andWhere { predicate }
}

/**
 * Applies filters using a custom expression mapping.
 * This is the most flexible variant — you control exactly how filter field names
 * map to columns or expressions.
 *
 * Supports both [Column] and [ExpressionWithColumnType] (e.g., `coalesce()`, `concat()`, aliased columns).
 *
 * **Note:** Nested field filters (e.g., `user.name`) are only supported when the expression
 * is a [Column] with a foreign key reference.
 *
 * Example:
 * ```
 * val expressions = mapOf(
 *     "userName" to UserTable.name,
 *     "productTitle" to ProductTable.title,
 *     "fullName" to concat(UserTable.firstName, stringLiteral(" "), UserTable.lastName),
 *     "status" to coalesce(UserTable.status, stringLiteral("unknown"))
 * )
 * query.applyFilters(expressions, filter)
 * ```
 */
public fun Query.applyFilters(
    expressions: Map<String, ExpressionWithColumnType<*>>,
    filterRequest: FilterRequest?
): Query {
    if (filterRequest == null) return this
    val root = filterRequest.root
    val predicate = context(null as ColumnMappersModule?) { nodeToPredicate(root, expressions) } ?: return this
    return andWhere { predicate }
}

/**
 * Same as [applyFilters] but allows passing a [mappersModule].
 */
public fun Query.applyFilters(
    expressions: Map<String, ExpressionWithColumnType<*>>,
    filterRequest: FilterRequest?,
    mappersModule: ColumnMappersModule
): Query {
    if (filterRequest == null) return this
    val root = filterRequest.root
    val predicate = context(mappersModule) { nodeToPredicate(root, expressions) } ?: return this
    return andWhere { predicate }
}

/**
 * Converts a [ColumnSet] to a map of field names to expressions.
 * - For [Table]: uses Kotlin property names (camelCase)
 * - For other types (Join, Alias, etc.): uses SQL column names
 */
private fun ColumnSet.toColumnMap(): Map<String, ExpressionWithColumnType<*>> = when (this) {
    is Table -> this.propertyToColumnMap()
    else -> this.columns.associateBy { it.name }
}

public fun Table.propertyToColumnMap(): Map<String, ExpressionWithColumnType<*>> =
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

context(mappersModule: ColumnMappersModule?)
private fun nodeToPredicate(
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

context(mappersModule: ColumnMappersModule?)
private fun predicateForField(
    expressions: Map<String, ExpressionWithColumnType<*>>,
    filter: FieldFilter,
): Op<Boolean> {
    val field = filter.field
    val dotIndex = field.indexOf('.')
    if (dotIndex < 0) {
        val expr = requireNotNull(expressions[field]) { "Unknown filter field: $field" }
        return predicateFor(expr, filter, field)
    }

    // Nested field support (only for Column types)
    val baseName = field.substring(0, dotIndex)
    val nestedName = field.substring(dotIndex + 1)
    require(nestedName.isNotEmpty()) { "Invalid nested field path: $field" }

    val baseExpr = checkNotNull(expressions[baseName]) { "Unknown filter field: $baseName" }
    require(baseExpr is Column<*>) {
        "Nested field filters (e.g., '$field') are only supported for Column types, " +
                "not for computed expressions."
    }

    val refInfo = resolveReference(baseExpr)
        ?: error("Field $baseName is not a reference; cannot use nested property $nestedName")

    val targetColumns = refInfo.referencedTable.propertyToColumnMap()
    val targetColumn =
        checkNotNull(targetColumns[nestedName]) { "Unknown nested field: $nestedName for reference $baseName" }

    // Build subquery: select referenced id from target table where target predicate holds
    val targetPredicate = predicateFor(targetColumn, filter, field)
    val subQuery = refInfo.referencedTable
        .selectAll()
        .andWhere {
            @Suppress("UNCHECKED_CAST")
            ((refInfo.referencedIdColumn as Column<Any?>).eq(baseExpr as Column<Any?>)) and targetPredicate
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

context(mappersModule: ColumnMappersModule?)
private fun predicateFor(
    expr: ExpressionWithColumnType<*>,
    filter: FieldFilter,
    fieldName: String,
): Op<Boolean> {
    // If an operator that expects an array of values receives an empty array,
    // the result must be an empty dataset. We encode it as a constant FALSE predicate.
    if ((filter.operator == FilterOperator.IN ||
                filter.operator == FilterOperator.BETWEEN) &&
        filter.values.isEmpty()
    ) {
        return Op.FALSE
    }
    if (filter.operator == FilterOperator.NOT_IN && filter.values.isEmpty()) {
        return Op.TRUE
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

context(mappersModule: ColumnMappersModule?)
private fun eqValue(
    expr: ExpressionWithColumnType<*>,
    raw: String?,
    fieldName: String,
): Op<Boolean> {
    requireNotNull(raw) { "EQ requires a value" }

    // First, try custom mappers
    if (mappersModule != null) {
        val customMapped = mappersModule.tryMap(expr.columnType, raw)
        if (customMapped != null) {
            @Suppress("UNCHECKED_CAST")
            return (expr as ExpressionWithColumnType<Any>).eq(customMapped)
        }
    }

    // Handle EntityID columns (only applicable for Column type)
    if (expr is Column<*> && expr.columnType is EntityIDColumnType<*>) {
        @Suppress("UNCHECKED_CAST")
        return eqEntityIdValue(expr as Column<EntityID<*>>, raw, fieldName)
    }

    // Then, try built-in mappers
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
    return when (expr.columnType) {
        is IntegerColumnType -> (expr as ExpressionWithColumnType<Int>).eq(raw.toInt())
        is LongColumnType -> (expr as ExpressionWithColumnType<Long>).eq(raw.toLong())
        is ShortColumnType -> (expr as ExpressionWithColumnType<Short>).eq(raw.toShort())
        is DoubleColumnType -> (expr as ExpressionWithColumnType<Double>).eq(raw.toDouble())
        is VarCharColumnType, is TextColumnType -> (expr as ExpressionWithColumnType<String>).eq(raw)
        is UUIDColumnType -> (expr as ExpressionWithColumnType<UUID>).eq(UUID.fromString(raw))
        is BooleanColumnType -> (expr as ExpressionWithColumnType<Boolean>).eq(raw.toBooleanStrict())
        is EnumerationNameColumnType<*> -> {
            val enumValue = enumValueOf(expr, raw)
            @Suppress("UNCHECKED_CAST")
            (expr as ExpressionWithColumnType<Enum<*>>).eq(enumValue)
        }

        else -> error("Unsupported equality for field '$fieldName'")
    }
}

private fun likeString(
    expr: ExpressionWithColumnType<*>,
    pattern: String,
    fieldName: String,
): Op<Boolean> = when (expr.columnType) {
    is VarCharColumnType, is TextColumnType -> (expr as ExpressionWithColumnType<String>).like(pattern)
    else -> error("LIKE is only supported for string fields: '$fieldName'")
}

context(mappersModule: ColumnMappersModule?)
private fun inListValue(
    expr: ExpressionWithColumnType<*>,
    raws: List<String>,
    fieldName: String,
): Op<Boolean> {
    if (raws.isEmpty()) return Op.FALSE

    // First, try custom mappers
    if (mappersModule != null) {
        val customMapped = raws.mapNotNull { raw ->
            mappersModule.tryMap(expr.columnType, raw)
        }
        if (customMapped.size == raws.size) {
            @Suppress("UNCHECKED_CAST")
            return (expr as ExpressionWithColumnType<Any>).inList(customMapped)
        }
    }

    // Handle EntityID columns (only applicable for Column type)
    if (expr is Column<*> && expr.columnType is EntityIDColumnType<*>) {
        return inListEntityIdValue(expr, raws, fieldName)
    }

    // Then, try built-in mappers
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
        is UUIDColumnType -> (expr as ExpressionWithColumnType<UUID>).inList(raws.map(UUID::fromString))
        is BooleanColumnType -> (expr as ExpressionWithColumnType<Boolean>).inList(raws.map(String::toBooleanStrict))
        is EnumerationNameColumnType<*> -> {
            @Suppress("UNCHECKED_CAST")
            (expr as ExpressionWithColumnType<Enum<*>>).inList(raws.map { enumValueOf(expr, it) })
        }

        else -> error("Unsupported IN for field '$fieldName'")
    }
}

context(mappersModule: ColumnMappersModule?)
private fun betweenValues(
    expr: ExpressionWithColumnType<*>,
    raws: List<String>,
    fieldName: String,
): Op<Boolean> {
    if (raws.isEmpty()) return Op.FALSE
    require(raws.size == 2) { "BETWEEN requires exactly two values" }
    val (from, to) = raws

    // First, try custom mappers
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

    // Then, try built-in mappers
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

context(mappersModule: ColumnMappersModule?)
private fun compareGreater(
    expr: ExpressionWithColumnType<*>,
    raw: String?,
    fieldName: String,
): Op<Boolean> {
    requireNotNull(raw) { "Comparison requires a value" }

    // First, try custom mappers
    if (mappersModule != null) {
        val mapped = mappersModule.tryMap(expr.columnType, raw)
        if (mapped != null) {
            require(mapped is Comparable<*>) { "Comparison requires comparable value for field '$fieldName'" }
            @Suppress("UNCHECKED_CAST")
            return (expr as ExpressionWithColumnType<Comparable<Any>>).greater(mapped as Comparable<Any>)
        }
    }

    // Then, try built-in mappers
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
        is VarCharColumnType, is TextColumnType -> (expr as ExpressionWithColumnType<String>).greater(raw)
        is UUIDColumnType -> (expr as ExpressionWithColumnType<UUID>).greater(UUID.fromString(raw))
        else -> error("Unsupported comparison for field '$fieldName'")
    }
}

context(mappersModule: ColumnMappersModule?)
private fun compareGreaterEq(
    expr: ExpressionWithColumnType<*>,
    raw: String?,
    fieldName: String,
): Op<Boolean> {
    requireNotNull(raw) { "Comparison requires a value" }

    // First, try custom mappers
    if (mappersModule != null) {
        val mapped = mappersModule.tryMap(expr.columnType, raw)
        if (mapped != null) {
            require(mapped is Comparable<*>) { "Comparison requires comparable value for field '$fieldName'" }
            @Suppress("UNCHECKED_CAST")
            return (expr as ExpressionWithColumnType<Comparable<Any>>).greaterEq(mapped as Comparable<Any>)
        }
    }

    // Then, try built-in mappers
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
        is VarCharColumnType, is TextColumnType -> (expr as ExpressionWithColumnType<String>).greaterEq(raw)
        is UUIDColumnType -> (expr as ExpressionWithColumnType<UUID>).greaterEq(UUID.fromString(raw))
        else -> error("Unsupported comparison for field '$fieldName'")
    }
}

context(mappersModule: ColumnMappersModule?)
private fun compareLess(
    expr: ExpressionWithColumnType<*>,
    raw: String?,
    fieldName: String,
): Op<Boolean> {
    requireNotNull(raw) { "Comparison requires a value" }

    // First, try custom mappers
    if (mappersModule != null) {
        val mapped = mappersModule.tryMap(expr.columnType, raw)
        if (mapped != null) {
            require(mapped is Comparable<*>) { "Comparison requires comparable value for field '$fieldName'" }
            @Suppress("UNCHECKED_CAST")
            return (expr as ExpressionWithColumnType<Comparable<Any>>).less(mapped as Comparable<Any>)
        }
    }

    // Then, try built-in mappers
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
        is VarCharColumnType, is TextColumnType -> (expr as ExpressionWithColumnType<String>).less(raw)
        is UUIDColumnType -> (expr as ExpressionWithColumnType<UUID>).less(UUID.fromString(raw))
        else -> error("Unsupported comparison for field '$fieldName'")
    }
}

context(mappersModule: ColumnMappersModule?)
private fun compareLessEq(
    expr: ExpressionWithColumnType<*>,
    raw: String?,
    fieldName: String,
): Op<Boolean> {
    requireNotNull(raw) { "Comparison requires a value" }

    // First, try custom mappers
    if (mappersModule != null) {
        val mapped = mappersModule.tryMap(expr.columnType, raw)
        if (mapped != null) {
            require(mapped is Comparable<*>) { "Comparison requires comparable value for field '$fieldName'" }
            @Suppress("UNCHECKED_CAST")
            return (expr as ExpressionWithColumnType<Comparable<Any>>).lessEq(mapped as Comparable<Any>)
        }
    }

    // Then, try built-in mappers
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
        is VarCharColumnType, is TextColumnType -> (expr as ExpressionWithColumnType<String>).lessEq(raw)
        is UUIDColumnType -> (expr as ExpressionWithColumnType<UUID>).lessEq(UUID.fromString(raw))
        else -> error("Unsupported comparison for field '$fieldName'")
    }
}

@Suppress("UNCHECKED_CAST")
private fun enumValueOf(expr: ExpressionWithColumnType<*>, name: String): Enum<*> {
    val type = expr.columnType as EnumerationNameColumnType<*>
    val constants = type.klass.java.enumConstants as Array<out Enum<*>>
    return constants.first { it.name == name }
}

// --- EntityID helpers (Column-specific) ---

context(mappersModule: ColumnMappersModule?)
private fun eqEntityIdValue(
    column: Column<EntityID<*>>,
    raw: String,
    fieldName: String,
): Op<Boolean> {
    val rawColumnType = rawColumnTypeOf(column, fieldName)
    if (mappersModule != null) {
        @Suppress("UNCHECKED_CAST")
        val customMapped = mappersModule.tryMap(rawColumnType as IColumnType<Any>, raw)
        if (customMapped != null) {
            @Suppress("UNCHECKED_CAST")
            return (column as Column<EntityID<Any>>).eq(customMapped)
        }
    }
    return when (rawColumnType) {
        is IntegerColumnType -> (column as Column<EntityID<Int>>).eq(raw.toInt())
        is LongColumnType -> (column as Column<EntityID<Long>>).eq(raw.toLong())
        is ShortColumnType -> (column as Column<EntityID<Short>>).eq(raw.toShort())
        is VarCharColumnType -> (column as Column<EntityID<String>>).eq(raw)
        is UUIDColumnType -> (column as Column<EntityID<UUID>>).eq(UUID.fromString(raw))
        else -> error("Unsupported equality for field '$fieldName'")
    }
}

context(mappersModule: ColumnMappersModule?)
private fun inListEntityIdValue(
    column: Column<*>,
    raws: List<String>,
    fieldName: String,
): Op<Boolean> {
    val rawColumnType = rawColumnTypeOf(column, fieldName)
    if (mappersModule != null) {
        @Suppress("UNCHECKED_CAST")
        val customMapped = raws.mapNotNull { raw ->
            mappersModule.tryMap(rawColumnType as IColumnType<Any>, raw)
        }
        if (customMapped.size == raws.size) {
            @Suppress("UNCHECKED_CAST")
            return (column as Column<EntityID<Any>>).inList(customMapped)
        }
    }
    return when (rawColumnType) {
        is IntegerColumnType -> (column as Column<EntityID<Int>>).inList(raws.map(String::toInt))
        is LongColumnType -> (column as Column<EntityID<Long>>).inList(raws.map(String::toLong))
        is ShortColumnType -> (column as Column<EntityID<Short>>).inList(raws.map(String::toShort))
        is VarCharColumnType -> (column as Column<EntityID<String>>).inList(raws)
        is UUIDColumnType -> (column as Column<EntityID<UUID>>).inList(raws.map(UUID::fromString))
        else -> error("Unsupported IN for field '$fieldName'")
    }
}

private fun rawColumnTypeOf(column: Column<*>, fieldName: String): IColumnType<*> {
    val ct = column.columnType
    val isEntityId = ct is EntityIDColumnType<*>
    if (!isEntityId) return ct
    val idColumn = ct.javaClass.methods
        .firstOrNull { it.name == "getIdColumn" && it.parameterCount == 0 }
        ?.invoke(ct) as? Column<*>
    if (idColumn == null) {
        error("Cannot access idColumn for EntityID field '$fieldName'")
    }
    return idColumn.columnType
}

// --- Date/Time support helpers ---

private fun isDateOnlyExpr(expr: ExpressionWithColumnType<*>): Boolean {
    val ct = expr.columnType
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

private fun parseDateForExpr(expr: ExpressionWithColumnType<*>, raw: String, fieldName: String): Any {
    // Prefer kotlinx.datetime.LocalDate for kotlin-datetime columns
    if (usesKotlinxLocalDateExpr(expr)) {
        return parseKotlinxLocalDate(raw)
    }
    val javaLocalDate: LocalDate = try {
        LocalDate.parse(raw)
    } catch (ex: DateTimeParseException) {
        throw IllegalArgumentException("Invalid date format for '$fieldName': '$raw'. Expected ISO-8601 date (YYYY-MM-DD)")
    }
    // If the expression is backed by java.sql.Date, convert accordingly
    if (usesSqlDateExpr(expr)) return SqlDate.valueOf(javaLocalDate)
    // Otherwise, assume java.time.LocalDate
    return javaLocalDate
}

private fun usesSqlDateExpr(expr: ExpressionWithColumnType<*>): Boolean {
    val simple = expr.columnType.javaClass.simpleName
    return simple == "DateColumnType"
}

private fun usesKotlinxLocalDateExpr(expr: ExpressionWithColumnType<*>): Boolean {
    val type = expr.columnType.javaClass
    val simple = type.simpleName
    val name = type.name
    return simple.contains("KotlinLocalDateColumnType", ignoreCase = true) || name.contains(
        "datetime",
        ignoreCase = true
    ) && simple.contains("LocalDate", ignoreCase = true)
}

private fun isTimestampExpr(expr: ExpressionWithColumnType<*>): Boolean {
    val ct = expr.columnType
    val type = ct.javaClass
    val simple = type.simpleName
    val name = type.name
    val looksLikeLocalDateTime = simple.contains("LocalDateTime", ignoreCase = true)
    val looksLikeInstant = simple.contains("Instant", ignoreCase = true)
    val looksLikeTimestamp = simple == "TimestampColumnType" || name.contains("DateTime", ignoreCase = true)
    return looksLikeLocalDateTime || looksLikeInstant || looksLikeTimestamp
}

private fun parseTimestampForExpr(expr: ExpressionWithColumnType<*>, raw: String): Any {
    val ldt = parseLocalDateTimeFlexible(raw)
    return when {
        usesSqlTimestampExpr(expr) -> SqlTimestamp.valueOf(ldt)
        usesInstantExpr(expr) -> Instant.fromEpochMilliseconds(ldt.toInstant(ZoneOffset.UTC).toEpochMilli())
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

private fun usesSqlTimestampExpr(expr: ExpressionWithColumnType<*>): Boolean {
    val simple = expr.columnType.javaClass.simpleName
    return simple == "TimestampColumnType" || simple.contains("DateTime", ignoreCase = true)
}

private fun usesInstantExpr(expr: ExpressionWithColumnType<*>): Boolean {
    val simple = expr.columnType.javaClass.simpleName
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
