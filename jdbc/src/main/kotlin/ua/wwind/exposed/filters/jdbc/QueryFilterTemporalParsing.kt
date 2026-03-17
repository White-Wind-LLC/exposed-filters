package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.time.Instant
import java.sql.Date as SqlDate
import java.sql.Timestamp as SqlTimestamp

/**
 * Checks if the expression uses a date-only column type.
 */
internal fun isDateOnlyExpr(expr: ExpressionWithColumnType<*>): Boolean {
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

/**
 * Parses a date string for the given expression type.
 */
internal fun parseDateForExpr(
    expr: ExpressionWithColumnType<*>,
    raw: String,
    fieldName: String
): Any {
    // Prefer kotlinx.datetime.LocalDate for kotlin-datetime columns.
    if (usesKotlinxLocalDateExpr(expr)) {
        return parseKotlinxLocalDate(raw)
    }
    val javaLocalDate: LocalDate = try {
        LocalDate.parse(raw)
    } catch (ex: DateTimeParseException) {
        throw IllegalArgumentException("Invalid date format for '$fieldName': '$raw'. Expected ISO-8601 date (YYYY-MM-DD)")
    }
    // If the expression is backed by java.sql.Date, convert accordingly.
    if (usesSqlDateExpr(expr)) return SqlDate.valueOf(javaLocalDate)
    // Otherwise, assume java.time.LocalDate.
    return javaLocalDate
}

/**
 * Checks if expression uses java.sql.Date (DateColumnType).
 */
internal fun usesSqlDateExpr(expr: ExpressionWithColumnType<*>): Boolean {
    val simple = expr.columnType.javaClass.simpleName
    return simple == "DateColumnType"
}

/**
 * Checks if expression uses kotlinx.datetime.LocalDate.
 */
internal fun usesKotlinxLocalDateExpr(expr: ExpressionWithColumnType<*>): Boolean {
    val type = expr.columnType.javaClass
    val simple = type.simpleName
    val name = type.name
    val looksLikeLocalDateColumnType = simple.contains("KotlinLocalDateColumnType", ignoreCase = true)
    val looksLikeLocalDateType = name.contains("datetime", ignoreCase = true) &&
            simple.contains("LocalDate", ignoreCase = true)
    return looksLikeLocalDateColumnType || looksLikeLocalDateType
}

/**
 * Checks if the expression uses a timestamp column type.
 */
internal fun isTimestampExpr(expr: ExpressionWithColumnType<*>): Boolean {
    val ct = expr.columnType
    val type = ct.javaClass
    val simple = type.simpleName
    val name = type.name
    val looksLikeLocalDateTime = simple.contains("LocalDateTime", ignoreCase = true)
    val looksLikeInstant = simple.contains("Instant", ignoreCase = true)
    val looksLikeTimestamp = simple == "TimestampColumnType" || name.contains("DateTime", ignoreCase = true)
    return looksLikeLocalDateTime || looksLikeInstant || looksLikeTimestamp
}

/**
 * Parses a timestamp string for the given expression type.
 */
internal fun parseTimestampForExpr(
    expr: ExpressionWithColumnType<*>,
    raw: String
): Any {
    val ldt = parseLocalDateTimeFlexible(raw)
    return when {
        usesSqlTimestampExpr(expr) -> SqlTimestamp.valueOf(ldt)
        usesInstantExpr(expr) -> Instant.fromEpochMilliseconds(ldt.toInstant(ZoneOffset.UTC).toEpochMilli())
        else -> ldt
    }
}

/**
 * Parses a datetime string flexibly (supports various ISO-8601 formats).
 */
internal fun parseLocalDateTimeFlexible(raw: String): LocalDateTime {
    val normalized = if (raw.contains(' ') && !raw.contains('T')) raw.replace(' ', 'T') else raw
    // Try LocalDateTime first, else fall back to LocalDate.
    val ldt = runCatching { LocalDateTime.parse(normalized) }.getOrNull()
    if (ldt != null) return ldt
    val ld = try {
        LocalDate.parse(normalized)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Invalid datetime format: '$raw'. Expected ISO-8601 date-time or date (YYYY-MM-DD[THH:MM[:SS]])")
    }
    return ld.atStartOfDay()
}

/**
 * Checks if expression uses SQL Timestamp column type.
 */
internal fun usesSqlTimestampExpr(expr: ExpressionWithColumnType<*>): Boolean {
    val simple = expr.columnType.javaClass.simpleName
    return simple == "TimestampColumnType" || simple.contains("DateTime", ignoreCase = true)
}

/**
 * Checks if expression uses Instant column type.
 */
internal fun usesInstantExpr(expr: ExpressionWithColumnType<*>): Boolean {
    val simple = expr.columnType.javaClass.simpleName
    return simple.contains("Instant", ignoreCase = true)
}

/**
 * Parses a kotlinx.datetime.LocalDate from a string using reflection.
 */
internal fun parseKotlinxLocalDate(raw: String): Any {
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