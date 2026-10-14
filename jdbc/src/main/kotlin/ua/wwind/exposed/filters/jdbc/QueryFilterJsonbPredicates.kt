package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.json.extract
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterOperator
import java.time.LocalDate
import java.sql.Date as SqlDate
import java.sql.Timestamp as SqlTimestamp

/**
 * Builds a predicate for JSON/JSONB field paths (e.g., payload.address.city).
 */
context(mappersModule: ColumnMappersModule?, options: FilterOptions)
internal fun jsonPathPredicateFor(
    jsonExpr: ExpressionWithColumnType<*>,
    pathSegments: List<String>,
    filter: FieldFilter,
): Op<Boolean> {
    val fieldName = filter.field
    val extracted = jsonExpr.extract<String>(*pathSegments.toTypedArray(), toScalar = true)

    if ((filter.operator == FilterOperator.IN || filter.operator == FilterOperator.BETWEEN) &&
        filter.values.isEmpty()
    ) {
        return Op.FALSE
    }
    if (filter.operator == FilterOperator.NOT_IN && filter.values.isEmpty()) {
        return Op.TRUE
    }

    return when (filter.operator) {
        FilterOperator.IS_NULL -> extracted.isNull()
        FilterOperator.IS_NOT_NULL -> extracted.isNotNull()
        FilterOperator.CONTAINS,
        FilterOperator.STARTS_WITH,
        FilterOperator.ENDS_WITH -> {
            val raw = requireNotNull(filter.values.firstOrNull()) { "${filter.operator} requires a value" }
            val inferred = jsonTypedValue(raw, fieldName)
            require(inferred.type == JsonValueType.STRING) {
                "${filter.operator} is only supported for string JSON values: '$fieldName'"
            }
            context(options.copy(normalizedStringFields = emptySet())) {
                likeString(extracted, filter.operator, inferred.value as String, fieldName)
            }
        }

        FilterOperator.EQ -> jsonEqTyped(
            extracted,
            requireNotNull(filter.values.firstOrNull()) { "EQ requires a value" },
            fieldName
        )

        FilterOperator.NEQ -> not(
            jsonEqTyped(
                extracted,
                requireNotNull(filter.values.firstOrNull()) { "NEQ requires a value" },
                fieldName
            )
        )

        FilterOperator.IN -> jsonInTyped(extracted, filter.values, fieldName)
        FilterOperator.NOT_IN -> not(jsonInTyped(extracted, filter.values, fieldName))
        FilterOperator.BETWEEN -> jsonBetweenTyped(extracted, filter.values, fieldName)
        FilterOperator.IN_HIERARCHY -> error("IN_HIERARCHY is not supported for JSON field '$fieldName'")
        FilterOperator.GT -> jsonCompareTyped(
            extracted,
            requireNotNull(filter.values.firstOrNull()) { "GT requires a value" },
            fieldName
        ) { expr, value ->
            expr.greater(value)
        }

        FilterOperator.GTE -> jsonCompareTyped(
            extracted,
            requireNotNull(filter.values.firstOrNull()) { "GTE requires a value" },
            fieldName
        ) { expr, value ->
            expr.greaterEq(value)
        }

        FilterOperator.LT -> jsonCompareTyped(
            extracted,
            requireNotNull(filter.values.firstOrNull()) { "LT requires a value" },
            fieldName
        ) { expr, value ->
            expr.less(value)
        }

        FilterOperator.LTE -> jsonCompareTyped(
            extracted,
            requireNotNull(filter.values.firstOrNull()) { "LTE requires a value" },
            fieldName
        ) { expr, value ->
            expr.lessEq(value)
        }
    }
}

context(options: FilterOptions)
private fun jsonEqTyped(
    extracted: ExpressionWithColumnType<String>,
    raw: String,
    fieldName: String
): Op<Boolean> {
    val typed = jsonTypedValue(raw, fieldName)
    return when (typed.type) {
        JsonValueType.NUMBER -> {
            val expr = extracted.castTo(DoubleColumnType())
            expr.eq(typed.value as Double)
        }

        JsonValueType.BOOLEAN -> {
            val expr = extracted.castTo(BooleanColumnType())
            expr.eq(typed.value as Boolean)
        }

        JsonValueType.DATE -> {
            val expr = extracted.castTo(SqlDateCastColumnType)
            expr.eq(typed.value as SqlDate)
        }

        JsonValueType.DATETIME -> {
            val expr = extracted.castTo(SqlTimestampCastColumnType)
            expr.eq(typed.value as SqlTimestamp)
        }

        JsonValueType.STRING -> {
            val value = typed.value as String
            if (options.caseSensitiveStrings) extracted.eq(value)
            else extracted.lowerCase().eq(value.lowercase())
        }
    }
}

context(options: FilterOptions)
private fun jsonInTyped(
    extracted: ExpressionWithColumnType<String>,
    raws: List<String>,
    fieldName: String
): Op<Boolean> {
    if (raws.isEmpty()) return Op.FALSE
    val typedValues = jsonTypedValues(raws, fieldName, "IN")
    val family = typedValues.first().type
    return when (family) {
        JsonValueType.NUMBER -> {
            val expr = extracted.castTo(DoubleColumnType())
            val values = typedValues.map { it.value as Double }
            expr.inList(values)
        }

        JsonValueType.BOOLEAN -> {
            val expr = extracted.castTo(BooleanColumnType())
            val values = typedValues.map { it.value as Boolean }
            expr.inList(values)
        }

        JsonValueType.DATE -> {
            val expr = extracted.castTo(SqlDateCastColumnType)
            val values = typedValues.map { it.value as SqlDate }
            expr.inList(values)
        }

        JsonValueType.DATETIME -> {
            val expr = extracted.castTo(SqlTimestampCastColumnType)
            val values = typedValues.map { it.value as SqlTimestamp }
            expr.inList(values)
        }

        JsonValueType.STRING -> {
            val values = typedValues.map { it.value as String }
            if (options.caseSensitiveStrings) extracted.inList(values)
            else extracted.lowerCase().inList(values.map(String::lowercase))
        }
    }
}

context(options: FilterOptions)
private fun jsonBetweenTyped(
    extracted: ExpressionWithColumnType<String>,
    raws: List<String>,
    fieldName: String
): Op<Boolean> {
    require(raws.size == 2) { "BETWEEN requires exactly two values" }
    val typedValues = jsonTypedValues(raws, fieldName, "BETWEEN")
    val left = typedValues[0]
    val right = typedValues[1]

    return when (left.type) {
        JsonValueType.NUMBER -> {
            val expr = extracted.castTo(DoubleColumnType())
            expr.between(left.value as Double, right.value as Double)
        }

        JsonValueType.DATE -> {
            val expr = extracted.castTo(SqlDateCastColumnType)
            expr.between(left.value as SqlDate, right.value as SqlDate)
        }

        JsonValueType.DATETIME -> {
            val expr = extracted.castTo(SqlTimestampCastColumnType)
            expr.between(left.value as SqlTimestamp, right.value as SqlTimestamp)
        }

        JsonValueType.STRING -> {
            val leftValue = left.value as String
            val rightValue = right.value as String
            if (options.caseSensitiveStrings) extracted.between(leftValue, rightValue)
            else extracted.lowerCase().between(leftValue.lowercase(), rightValue.lowercase())
        }
        JsonValueType.BOOLEAN -> error("BETWEEN is not supported for boolean JSON values: '$fieldName'")
    }
}

context(options: FilterOptions)
private fun jsonCompareTyped(
    extracted: ExpressionWithColumnType<String>,
    raw: String,
    fieldName: String,
    comparator: (ExpressionWithColumnType<Comparable<Any>>, Comparable<Any>) -> Op<Boolean>
): Op<Boolean> {
    val typed = jsonTypedValue(raw, fieldName)
    return when (typed.type) {
        JsonValueType.NUMBER -> {
            val expr = extracted.castTo(DoubleColumnType())
            @Suppress("UNCHECKED_CAST")
            comparator(expr as ExpressionWithColumnType<Comparable<Any>>, typed.value as Comparable<Any>)
        }

        JsonValueType.DATE -> {
            val expr = extracted.castTo(SqlDateCastColumnType)
            @Suppress("UNCHECKED_CAST")
            comparator(expr as ExpressionWithColumnType<Comparable<Any>>, typed.value as Comparable<Any>)
        }

        JsonValueType.DATETIME -> {
            val expr = extracted.castTo(SqlTimestampCastColumnType)
            @Suppress("UNCHECKED_CAST")
            comparator(expr as ExpressionWithColumnType<Comparable<Any>>, typed.value as Comparable<Any>)
        }

        JsonValueType.STRING -> {
            val value = typed.value as String
            if (options.caseSensitiveStrings) {
                @Suppress("UNCHECKED_CAST")
                comparator(extracted as ExpressionWithColumnType<Comparable<Any>>, value as Comparable<Any>)
            } else {
                @Suppress("UNCHECKED_CAST")
                comparator(
                    extracted.lowerCase() as ExpressionWithColumnType<Comparable<Any>>,
                    value.lowercase() as Comparable<Any>
                )
            }
        }

        JsonValueType.BOOLEAN -> error("Comparison operator is not supported for boolean JSON values: '$fieldName'")
    }
}

/**
 * Parses a filter value as the type [FilterOptions.jsonPathType] declares for [fieldName], or infers
 * the type from the value when nothing is declared.
 */
context(options: FilterOptions)
private fun jsonTypedValue(raw: String, fieldName: String): JsonTypedValue {
    val declared = options.jsonPathType?.invoke(fieldName) ?: return inferJsonTypedValue(raw)
    return parseJsonTypedValue(raw, declared, fieldName)
}

context(options: FilterOptions)
private fun jsonTypedValues(
    raws: List<String>,
    fieldName: String,
    operator: String
): List<JsonTypedValue> {
    val declared = options.jsonPathType?.invoke(fieldName)
    if (declared != null) return raws.map { parseJsonTypedValue(it, declared, fieldName) }
    val typedValues = raws.map { inferJsonTypedValue(it) }
    val firstType = typedValues.firstOrNull()?.type ?: return typedValues
    require(typedValues.all { it.type == firstType }) {
        "All values for $operator on '$fieldName' must have the same inferred type."
    }
    return typedValues
}

private fun parseJsonTypedValue(raw: String, type: JsonValueType, fieldName: String): JsonTypedValue {
    val value: Any? = when (type) {
        JsonValueType.STRING -> raw
        JsonValueType.NUMBER -> raw.toDoubleOrNull()
        JsonValueType.BOOLEAN -> raw.toBooleanStrictOrNull()
        JsonValueType.DATE -> runCatching { SqlDate.valueOf(LocalDate.parse(raw)) }.getOrNull()
        JsonValueType.DATETIME -> runCatching { SqlTimestamp.valueOf(parseLocalDateTimeFlexible(raw)) }.getOrNull()
    }
    requireNotNull(value) { "Value '$raw' for JSON field '$fieldName' is not a valid $type" }
    return JsonTypedValue(type, value)
}

private fun inferJsonTypedValue(raw: String): JsonTypedValue {
    if (raw == "true" || raw == "false") {
        return JsonTypedValue(JsonValueType.BOOLEAN, raw.toBooleanStrict())
    }

    runCatching { raw.toDouble() }.getOrNull()?.let {
        return JsonTypedValue(JsonValueType.NUMBER, it)
    }

    if (raw.contains('T') || raw.contains(' ')) {
        val dt = runCatching { parseLocalDateTimeFlexible(raw) }.getOrNull()
        if (dt != null) {
            return JsonTypedValue(JsonValueType.DATETIME, SqlTimestamp.valueOf(dt))
        }
    }

    runCatching { LocalDate.parse(raw) }.getOrNull()?.let {
        return JsonTypedValue(JsonValueType.DATE, SqlDate.valueOf(it))
    }

    return JsonTypedValue(JsonValueType.STRING, raw)
}