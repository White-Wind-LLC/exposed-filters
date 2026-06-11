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
            val inferred = inferJsonTypedValue(raw)
            require(inferred.type == JsonInferredType.STRING) {
                "${filter.operator} is only supported for string JSON values: '$fieldName'"
            }
            val pattern = when (filter.operator) {
                FilterOperator.CONTAINS -> "%${inferred.value as String}%"
                FilterOperator.STARTS_WITH -> "${inferred.value as String}%"
                FilterOperator.ENDS_WITH -> "%${inferred.value as String}"
                else -> error("Unsupported operator")
            }
            context(options.copy(normalizedStringFields = emptySet())) {
                likeString(extracted, pattern, fieldName)
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
    val typed = inferJsonTypedValue(raw)
    return when (typed.type) {
        JsonInferredType.NUMBER -> {
            val expr = extracted.castTo(DoubleColumnType())
            expr.eq(typed.value as Double)
        }

        JsonInferredType.BOOLEAN -> {
            val expr = extracted.castTo(BooleanColumnType())
            expr.eq(typed.value as Boolean)
        }

        JsonInferredType.DATE -> {
            val expr = extracted.castTo(SqlDateCastColumnType)
            expr.eq(typed.value as SqlDate)
        }

        JsonInferredType.DATETIME -> {
            val expr = extracted.castTo(SqlTimestampCastColumnType)
            expr.eq(typed.value as SqlTimestamp)
        }

        JsonInferredType.STRING -> {
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
    val typedValues = inferJsonTypedValues(raws, fieldName, "IN")
    val family = typedValues.first().type
    return when (family) {
        JsonInferredType.NUMBER -> {
            val expr = extracted.castTo(DoubleColumnType())
            val values = typedValues.map { it.value as Double }
            expr.inList(values)
        }

        JsonInferredType.BOOLEAN -> {
            val expr = extracted.castTo(BooleanColumnType())
            val values = typedValues.map { it.value as Boolean }
            expr.inList(values)
        }

        JsonInferredType.DATE -> {
            val expr = extracted.castTo(SqlDateCastColumnType)
            val values = typedValues.map { it.value as SqlDate }
            expr.inList(values)
        }

        JsonInferredType.DATETIME -> {
            val expr = extracted.castTo(SqlTimestampCastColumnType)
            val values = typedValues.map { it.value as SqlTimestamp }
            expr.inList(values)
        }

        JsonInferredType.STRING -> {
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
    val typedValues = inferJsonTypedValues(raws, fieldName, "BETWEEN")
    val left = typedValues[0]
    val right = typedValues[1]

    return when (left.type) {
        JsonInferredType.NUMBER -> {
            val expr = extracted.castTo(DoubleColumnType())
            expr.between(left.value as Double, right.value as Double)
        }

        JsonInferredType.DATE -> {
            val expr = extracted.castTo(SqlDateCastColumnType)
            expr.between(left.value as SqlDate, right.value as SqlDate)
        }

        JsonInferredType.DATETIME -> {
            val expr = extracted.castTo(SqlTimestampCastColumnType)
            expr.between(left.value as SqlTimestamp, right.value as SqlTimestamp)
        }

        JsonInferredType.STRING -> {
            val leftValue = left.value as String
            val rightValue = right.value as String
            if (options.caseSensitiveStrings) extracted.between(leftValue, rightValue)
            else extracted.lowerCase().between(leftValue.lowercase(), rightValue.lowercase())
        }
        JsonInferredType.BOOLEAN -> error("BETWEEN is not supported for boolean JSON values: '$fieldName'")
    }
}

context(options: FilterOptions)
private fun jsonCompareTyped(
    extracted: ExpressionWithColumnType<String>,
    raw: String,
    fieldName: String,
    comparator: (ExpressionWithColumnType<Comparable<Any>>, Comparable<Any>) -> Op<Boolean>
): Op<Boolean> {
    val typed = inferJsonTypedValue(raw)
    return when (typed.type) {
        JsonInferredType.NUMBER -> {
            val expr = extracted.castTo(DoubleColumnType())
            @Suppress("UNCHECKED_CAST")
            comparator(expr as ExpressionWithColumnType<Comparable<Any>>, typed.value as Comparable<Any>)
        }

        JsonInferredType.DATE -> {
            val expr = extracted.castTo(SqlDateCastColumnType)
            @Suppress("UNCHECKED_CAST")
            comparator(expr as ExpressionWithColumnType<Comparable<Any>>, typed.value as Comparable<Any>)
        }

        JsonInferredType.DATETIME -> {
            val expr = extracted.castTo(SqlTimestampCastColumnType)
            @Suppress("UNCHECKED_CAST")
            comparator(expr as ExpressionWithColumnType<Comparable<Any>>, typed.value as Comparable<Any>)
        }

        JsonInferredType.STRING -> {
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

        JsonInferredType.BOOLEAN -> error("Comparison operator is not supported for boolean JSON values: '$fieldName'")
    }
}

private fun inferJsonTypedValues(
    raws: List<String>,
    fieldName: String,
    operator: String
): List<JsonTypedValue> {
    val typedValues = raws.map { inferJsonTypedValue(it) }
    val firstType = typedValues.firstOrNull()?.type ?: return typedValues
    require(typedValues.all { it.type == firstType }) {
        "All values for $operator on '$fieldName' must have the same inferred type."
    }
    return typedValues
}

private fun inferJsonTypedValue(raw: String): JsonTypedValue {
    if (raw == "true" || raw == "false") {
        return JsonTypedValue(JsonInferredType.BOOLEAN, raw.toBooleanStrict())
    }

    runCatching { raw.toDouble() }.getOrNull()?.let {
        return JsonTypedValue(JsonInferredType.NUMBER, it)
    }

    if (raw.contains('T') || raw.contains(' ')) {
        val dt = runCatching { parseLocalDateTimeFlexible(raw) }.getOrNull()
        if (dt != null) {
            return JsonTypedValue(JsonInferredType.DATETIME, SqlTimestamp.valueOf(dt))
        }
    }

    runCatching { LocalDate.parse(raw) }.getOrNull()?.let {
        return JsonTypedValue(JsonInferredType.DATE, SqlDate.valueOf(it))
    }

    return JsonTypedValue(JsonInferredType.STRING, raw)
}