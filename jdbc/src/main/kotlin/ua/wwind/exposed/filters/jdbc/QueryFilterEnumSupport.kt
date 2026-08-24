package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.CustomEnumerationColumnType
import org.jetbrains.exposed.v1.core.EnumerationColumnType
import org.jetbrains.exposed.v1.core.EnumerationNameColumnType
import org.jetbrains.exposed.v1.core.IColumnType

/*
 * Enum column support.
 *
 * Exposed stores an enum in one of three shapes: its `name` ([EnumerationNameColumnType]), its
 * `ordinal` ([EnumerationColumnType]), or whatever a user-supplied transformation produces
 * ([CustomEnumerationColumnType]). Predicates never need to know which: handing Exposed the enum
 * constant is enough, because the column type's own `notNullValueToDB` converts it to the stored
 * representation. So the only job here is resolving a raw filter string into the right constant.
 *
 * A value is accepted either as the constant name (exact match, case sensitive) or as its ordinal.
 * The name is tried first, so an enum with a constant literally named `"2"` still resolves by name.
 *
 * CustomEnumerationColumnType is the exception: it keeps no reference to the enum class, only the
 * `fromDb`/`toDb` lambdas, and those compile to `invokedynamic` lambdas whose signatures are fully
 * erased - so the constants cannot be enumerated. Such a column accepts exactly what its own
 * `fromDb` accepts (names for a name-based transformation, numbers for an ordinal-based one). To
 * accept both forms there, register a ColumnValueMapper; custom mappers are tried first.
 */

/**
 * Resolves [raw] into an enum constant of the enum type behind [columnType].
 *
 * @throws IllegalArgumentException if the value is neither a known constant name nor a valid ordinal.
 */
internal fun resolveEnumValue(
    columnType: IColumnType<*>,
    raw: String,
    fieldName: String
): Enum<*> {
    val constants = enumConstantsOf(columnType)
    if (constants != null) {
        constants.firstOrNull { it.name == raw }?.let { return it }
        val ordinal = raw.toIntOrNull()
        if (ordinal != null) {
            constants.getOrNull(ordinal)?.let { return it }
        }
    }
    // A custom transformation may accept representations that are neither the name nor the ordinal
    // (lower-cased labels, database-specific codes); ask it before giving up.
    if (columnType is CustomEnumerationColumnType<*>) {
        customEnumValueOf(columnType, raw)?.let { return it }
    }
    throw IllegalArgumentException(unknownEnumValueMessage(raw, fieldName, constants))
}

/**
 * Fails a comparison operator on an enum column that is not stored by ordinal.
 *
 * Only ordinal storage makes SQL ordering agree with the enum's declaration order; comparing names
 * or custom labels would silently order by the stored representation instead.
 */
internal fun enumComparisonNotSupported(
    operator: String,
    fieldName: String
): Nothing =
    error(
        "$operator is not supported for field '$fieldName': this column does not store the enum by " +
                "ordinal, so the database would compare the stored representation rather than the " +
                "enum's declaration order. Declare the column with enumeration() for ordinal storage, " +
                "or filter with IN and explicit values."
    )

/**
 * Enum constants behind [columnType], or null when the enum class is not reachable from the column
 * type - which is the case for [CustomEnumerationColumnType] and for non-enum columns.
 */
@Suppress("UNCHECKED_CAST")
private fun enumConstantsOf(columnType: IColumnType<*>): Array<out Enum<*>>? =
    when (columnType) {
        is EnumerationNameColumnType<*> -> columnType.klass.java.enumConstants as Array<out Enum<*>>
        is EnumerationColumnType<*> -> columnType.klass.java.enumConstants as Array<out Enum<*>>
        else -> null
    }

/**
 * Runs the column's own `fromDb` transformation over [raw], first as a string and then, when [raw]
 * is numeric, as an int. Returns null if the transformation rejects both.
 */
@Suppress("UNCHECKED_CAST")
private fun customEnumValueOf(
    columnType: CustomEnumerationColumnType<*>,
    raw: String
): Enum<*>? {
    val fromDb = columnType.fromDb as (Any) -> Enum<*>
    runCatching { fromDb(raw) }.getOrNull()?.let { return it }
    val ordinal = raw.toIntOrNull() ?: return null
    return runCatching { fromDb(ordinal) }.getOrNull()
}

private fun unknownEnumValueMessage(
    raw: String,
    fieldName: String,
    constants: Array<out Enum<*>>?
): String =
    if (constants != null && constants.isNotEmpty()) {
        "Unknown enum value '$raw' for field '$fieldName'. " +
                "Allowed: ${constants.joinToString(", ") { it.name }} " +
                "(or ordinal 0..${constants.lastIndex})"
    } else {
        "Unknown enum value '$raw' for field '$fieldName': " +
                "the column's custom enum transformation did not accept it."
    }
