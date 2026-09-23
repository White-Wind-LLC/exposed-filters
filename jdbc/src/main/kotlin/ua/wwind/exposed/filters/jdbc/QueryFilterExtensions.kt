@file:OptIn(kotlin.time.ExperimentalTime::class)

package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import ua.wwind.exposed.filters.core.FilterGroup
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterNode
import ua.wwind.exposed.filters.core.FilterRequest
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

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
 *
 * A SQL name exposed by more than one source of a Join (e.g. `name` on both joined tables) is
 * ambiguous: filtering on it throws [IllegalArgumentException] instead of picking one of the columns.
 * Use [applyFilters] with an explicit mapping to disambiguate.
 */
public fun Query.applyFiltersOn(
    columnSet: ColumnSet,
    filterRequest: FilterRequest?,
    options: FilterOptions = DefaultFilterOptions,
): Query {
    if (filterRequest == null) return this
    val root = filterRequest.root
    columnSet.requireUnambiguousFields(root)
    val columns = columnSet.toColumnMap()
    val predicate = context(null as ColumnMappersModule?, options) { nodeToPredicate(root, columns) } ?: return this
    return andWhere { predicate }
}

/**
 * Same as [applyFiltersOn] but allows passing a [mappersModule] to handle predicates
 * for custom column types when standard mappings do not apply.
 */
public fun Query.applyFiltersOn(
    columnSet: ColumnSet,
    filterRequest: FilterRequest?,
    mappersModule: ColumnMappersModule,
    options: FilterOptions = DefaultFilterOptions,
): Query {
    if (filterRequest == null) return this
    val root = filterRequest.root
    columnSet.requireUnambiguousFields(root)
    val columns = columnSet.toColumnMap()
    val predicate = context(mappersModule, options) { nodeToPredicate(root, columns) } ?: return this
    return andWhere { predicate }
}

/**
 * Applies filters using a custom expression mapping.
 * This is the most flexible variant; you control exactly how filter field names
 * map to columns or expressions.
 *
 * Supports both [Column] and [ExpressionWithColumnType] (e.g., `coalesce()`, `concat()`, aliased columns).
 *
 * **Note:** Nested field filters (e.g., `user.name`) are only supported when the expression
 * is a [Column] with a foreign key reference.
 */
public fun Query.applyFilters(
    expressions: Map<String, ExpressionWithColumnType<*>>,
    filterRequest: FilterRequest?,
    options: FilterOptions = DefaultFilterOptions,
): Query {
    if (filterRequest == null) return this
    val root = filterRequest.root
    val predicate = context(null as ColumnMappersModule?, options) { nodeToPredicate(root, expressions) } ?: return this
    return andWhere { predicate }
}

/**
 * Same as [applyFilters] but allows passing a [mappersModule].
 */
public fun Query.applyFilters(
    expressions: Map<String, ExpressionWithColumnType<*>>,
    filterRequest: FilterRequest?,
    mappersModule: ColumnMappersModule,
    options: FilterOptions = DefaultFilterOptions,
): Query {
    if (filterRequest == null) return this
    val root = filterRequest.root
    val predicate = context(mappersModule, options) { nodeToPredicate(root, expressions) } ?: return this
    return andWhere { predicate }
}

/**
 * Converts a [ColumnSet] to a map of field names to expressions.
 * - For [Table]: uses Kotlin property names (camelCase)
 * - For other types (Join, Alias, etc.): uses SQL column names
 */
internal fun ColumnSet.toColumnMap(): Map<String, ExpressionWithColumnType<*>> = when (this) {
    is Table -> this.propertyToColumnMap()
    else -> this.columns.associateBy { it.name }
}

/**
 * Fails when the filter references a SQL name that more than one column of this [ColumnSet] carries.
 * [toColumnMap] keys such a set by SQL name, so without this check the last duplicate would silently
 * win and the filter would hit a column the caller may not have meant. Only referenced names are
 * checked: a join whose sources share a name nobody filters on (typically the join key) stays usable.
 */
internal fun ColumnSet.requireUnambiguousFields(root: FilterNode) {
    if (this is Table) return
    val ambiguous = columns.distinct().groupBy { it.name }.filterValues { it.size > 1 }
    if (ambiguous.isEmpty()) return
    for (field in root.referencedFields()) {
        val baseName = field.substringBefore('.')
        val candidates = ambiguous[baseName] ?: continue
        throw IllegalArgumentException(
            "Ambiguous filter field: '$baseName' is exposed by " +
                candidates.joinToString { "'${it.table.sourceName()}'" } +
                ". Map the field to one column explicitly with applyFilters(Map<String, ExpressionWithColumnType<*>>, ...)."
        )
    }
}

private fun FilterNode.referencedFields(): Sequence<String> = when (this) {
    is FilterLeaf -> predicates.asSequence().map { it.field }
    is FilterGroup -> children.asSequence().flatMap { it.referencedFields() }
}

private fun Table.sourceName(): String = (this as? Alias<*>)?.alias ?: tableName

public fun Table.propertyToColumnMap(): Map<String, ExpressionWithColumnType<*>> =
    this::class.memberProperties
        .mapNotNull { prop ->
            @Suppress("UNCHECKED_CAST")
            val p = prop as? KProperty1<Any, *> ?: return@mapNotNull null
            // Some properties can be non-public on generated tables; make accessible defensively.
            p.isAccessible = true
            val value = runCatching { p.get(this) }.getOrNull()
            if (value is Column<*>) {
                prop.name to value
            } else {
                null
            }
        }
        .toMap()
