package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.ColumnType
import java.time.LocalDate
import java.sql.Date as SqlDate
import java.sql.Timestamp as SqlTimestamp

/**
 * Type inference for JSON scalar values extracted via JSONPath.
 */
internal enum class JsonInferredType {
    NUMBER,
    BOOLEAN,
    DATE,
    DATETIME,
    STRING
}

/**
 * Typed value with inferred JSON type.
 */
internal data class JsonTypedValue(
    val type: JsonInferredType,
    val value: Any
)

/**
 * Custom column type for casting to SQL DATE.
 */
internal object SqlDateCastColumnType : ColumnType<SqlDate>() {
    override fun sqlType(): String = "DATE"
    override fun valueFromDB(value: Any): SqlDate = when (value) {
        is SqlDate -> value
        is java.util.Date -> SqlDate(value.time)
        is String -> SqlDate.valueOf(LocalDate.parse(value))
        else -> error("Unexpected DATE value: $value")
    }
}

/**
 * Custom column type for casting to SQL TIMESTAMP.
 */
internal object SqlTimestampCastColumnType : ColumnType<SqlTimestamp>() {
    override fun sqlType(): String = "TIMESTAMP"
    override fun valueFromDB(value: Any): SqlTimestamp = when (value) {
        is SqlTimestamp -> value
        is java.util.Date -> SqlTimestamp(value.time)
        is String -> SqlTimestamp.valueOf(parseLocalDateTimeFlexible(value))
        else -> error("Unexpected TIMESTAMP value: $value")
    }
}