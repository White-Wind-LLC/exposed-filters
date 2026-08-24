package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.CustomEnumerationColumnType
import org.jetbrains.exposed.v1.core.EnumerationColumnType
import org.jetbrains.exposed.v1.core.EnumerationNameColumnType
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/*
 * Enum column support.
 *
 * A filter value is a client's value, so it never carries the database representation: it is either
 * the enum constant name (exact match, case sensitive) or its ordinal, whichever storage form the
 * column happens to use. The name is tried first, so an enum with a constant literally named "2"
 * still resolves by name.
 *
 * Predicates therefore never build the stored value by hand. They resolve the raw string to an enum
 * constant and hand that to Exposed; the column type's own notNullValueToDB converts it - the
 * ordinal for EnumerationColumnType, the name for EnumerationNameColumnType, the result of the
 * user's toDb for CustomEnumerationColumnType. A custom transformation that stores, say, one-letter
 * codes still gets its 'P' without the client ever knowing about it.
 *
 * Resolving a name or ordinal needs the enum's constants. EnumerationNameColumnType and
 * EnumerationColumnType carry the enum class themselves; CustomEnumerationColumnType does not - it
 * keeps only the fromDb/toDb lambdas, which compile to invokedynamic lambdas with fully erased
 * signatures. For those the class is recovered from the Kotlin property that declares the column
 * (`val status: Column<Status>` -> Status), the same reflection propertyToColumnMap() already uses.
 */

/**
 * Resolves [raw] into an enum constant of the enum type behind [columnType].
 *
 * [expr] is the expression the filter applies to; it is what makes the enum class of a
 * [CustomEnumerationColumnType] reachable, so pass the column itself, not a derived expression.
 * For array columns, pass the array column as [expr] and the element type as [columnType].
 *
 * @throws IllegalArgumentException if the value is neither a known constant name nor a valid ordinal.
 * @throws IllegalStateException if the enum type cannot be determined at all.
 */
internal fun resolveEnumValue(
    expr: ExpressionWithColumnType<*>,
    columnType: IColumnType<*>,
    raw: String,
    fieldName: String
): Enum<*> {
    val constants = enumConstantsOf(expr, columnType)
        ?: error(
            "Cannot determine the enum type for field '$fieldName': the column is declared with " +
                    "customEnumeration(), which does not expose its enum class, and the property " +
                    "declaring it could not be found. Register a ColumnValueMapper for this column."
        )
    constants.firstOrNull { it.name == raw }?.let { return it }
    raw.toIntOrNull()?.let { ordinal -> constants.getOrNull(ordinal)?.let { return it } }
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

/** Enum constants behind [columnType], or null when the enum type cannot be determined. */
@Suppress("UNCHECKED_CAST")
private fun enumConstantsOf(
    expr: ExpressionWithColumnType<*>,
    columnType: IColumnType<*>
): Array<out Enum<*>>? =
    when (columnType) {
        is EnumerationNameColumnType<*> -> columnType.klass.java.enumConstants as Array<out Enum<*>>
        is EnumerationColumnType<*> -> columnType.klass.java.enumConstants as Array<out Enum<*>>
        is CustomEnumerationColumnType<*> -> declaredEnumClassOf(columnType, expr)?.enumConstants
        else -> null
    }

private val NO_ENUM_CLASS = Any()

/**
 * Memoized per column type. Each customEnumeration() call creates its own column type instance and
 * the type does not override equals/hashCode, so identity keys the cache correctly - and the lookup
 * it replaces is a full `memberProperties` scan, which is far too slow to repeat per filter value.
 */
private val declaredEnumClasses = ConcurrentHashMap<CustomEnumerationColumnType<*>, Any>()

/**
 * Recovers the enum class of a [CustomEnumerationColumnType] from the Kotlin property that declares
 * the column - `val status: Column<Status>` yields `Status`, whether the property's type is written
 * out or inferred. Returns null when [expr] is not a table column, or no matching property exists.
 */
@Suppress("UNCHECKED_CAST")
private fun declaredEnumClassOf(
    columnType: CustomEnumerationColumnType<*>,
    expr: ExpressionWithColumnType<*>
): Class<out Enum<*>>? {
    val cached = declaredEnumClasses.computeIfAbsent(columnType) {
        findDeclaredEnumClass(expr) ?: NO_ENUM_CLASS
    }
    return if (cached === NO_ENUM_CLASS) null else cached as Class<out Enum<*>>
}

private fun findDeclaredEnumClass(expr: ExpressionWithColumnType<*>): Class<out Enum<*>>? {
    val column = expr as? Column<*> ?: return null
    // An aliased column is a distinct instance owned by the alias; the properties live on the table
    // it delegates to, and column names survive aliasing.
    val table = column.table.let { if (it is Alias<*>) it.delegate else it }
    return table::class.memberProperties.firstNotNullOfOrNull { prop ->
        @Suppress("UNCHECKED_CAST")
        val typed = prop as? KProperty1<Any, *> ?: return@firstNotNullOfOrNull null
        // Some properties can be non-public on generated tables; make accessible defensively.
        typed.isAccessible = true
        val value = runCatching { typed.get(table) }.getOrNull()
        if (value is Column<*> && value.name == column.name) {
            prop.returnType.firstEnumArgument()
        } else {
            null
        }
    }
}

/**
 * First enum among a type's arguments, searched depth-first: `Column<Status>` gives `Status`, and
 * `Column<List<Status>>` - an array column of enums - gives `Status` one level deeper.
 */
@Suppress("UNCHECKED_CAST")
private fun KType.firstEnumArgument(): Class<out Enum<*>>? {
    arguments.forEach { argument ->
        val argumentType = argument.type ?: return@forEach
        val javaClass = (argumentType.classifier as? KClass<*>)?.java
        if (javaClass != null && javaClass.isEnum) return javaClass as Class<out Enum<*>>
        argumentType.firstEnumArgument()?.let { return it }
    }
    return null
}

private fun unknownEnumValueMessage(
    raw: String,
    fieldName: String,
    constants: Array<out Enum<*>>
): String =
    if (constants.isNotEmpty()) {
        "Unknown enum value '$raw' for field '$fieldName'. " +
                "Allowed: ${constants.joinToString(", ") { it.name }} " +
                "(or ordinal 0..${constants.lastIndex})"
    } else {
        "Unknown enum value '$raw' for field '$fieldName': the enum has no constants."
    }
