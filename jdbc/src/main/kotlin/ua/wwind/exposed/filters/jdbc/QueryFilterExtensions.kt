@file:OptIn(kotlin.time.ExperimentalTime::class)

package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IExpressionAlias
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.QueryAlias
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
 * - A computed field of a subquery ([QueryAlias]), such as `qty.sum().alias("total")`, is matched
 *   against its **alias label** (`total`), whether the subquery is the whole source or part of a Join
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
 * - For other types (Join, Alias, etc.): uses SQL column names, plus the alias label of every
 *   computed field a subquery exposes
 */
internal fun ColumnSet.toColumnMap(): Map<String, ExpressionWithColumnType<*>> = when (this) {
    is Table -> this.propertyToColumnMap()
    else -> sourceFields().associate { it.name to it.expression }
}

/**
 * Fails when the filter references a name that more than one field of this [ColumnSet] carries.
 * [toColumnMap] keys such a set by name, so without this check the last duplicate would silently
 * win and the filter would hit a field the caller may not have meant. Only referenced names are
 * checked: a join whose sources share a name nobody filters on (typically the join key) stays usable.
 */
internal fun ColumnSet.requireUnambiguousFields(root: FilterNode) {
    if (this is Table) return
    val ambiguous = sourceFields().groupBy { it.name }.filterValues { it.size > 1 }
    if (ambiguous.isEmpty()) return
    for (field in root.referencedFields()) {
        val baseName = field.substringBefore('.')
        val candidates = ambiguous[baseName] ?: continue
        throw IllegalArgumentException(
            "Ambiguous filter field: '$baseName' is exposed by " +
                candidates.joinToString { "'${it.source}'" } +
                ". Map the field to one column explicitly with applyFilters(Map<String, ExpressionWithColumnType<*>>, ...)."
        )
    }
}

/** A field a non-[Table] [ColumnSet] exposes to filters, and the name of the source it comes from. */
private class SourceField(val name: String, val expression: ExpressionWithColumnType<*>, val source: String)

/**
 * Every field this [ColumnSet] exposes by name: its columns by SQL name, and the computed fields of
 * each subquery by alias label. Exposed keeps those out of [ColumnSet.columns] (a [QueryAlias] lists
 * only plain columns there), and its [ColumnSet.fields] carry no label to address them by, so a
 * [Join] is walked source by source instead.
 */
private fun ColumnSet.sourceFields(): List<SourceField> {
    val sources = flattenSources()
        ?: return columns.distinct().map { SourceField(it.name, it, it.table.sourceName()) }
    val columnFields = sources.flatMap { it.columns }.distinct()
        .map { SourceField(it.name, it, it.table.sourceName()) }
    return columnFields + sources.filterIsInstance<QueryAlias>().flatMap { it.computedFields() }
}

/**
 * The sources this [ColumnSet] is assembled from, joins flattened. `null` when a [Join] cannot be
 * taken apart, which leaves the caller with plain columns only.
 */
private fun ColumnSet.flattenSources(): List<ColumnSet>? = when (this) {
    is Join -> {
        val parts = joinedParts() ?: return null
        (listOf(table) + parts).flatMap { it.flattenSources() ?: return null }
    }
    else -> listOf(this)
}

/**
 * The column sets joined to [Join.table]. Exposed keeps them in the internal `joinParts` list, so they
 * are read reflectively, the same way [resolveReference] reads `referee`.
 */
private fun Join.joinedParts(): List<ColumnSet>? = runCatching {
    val parts = Join::class.java.getDeclaredField("joinParts").apply { isAccessible = true }.get(this) as List<*>
    parts.map { part ->
        val field = requireNotNull(part).javaClass.getDeclaredField("joinPart").apply { isAccessible = true }
        field.get(part) as ColumnSet
    }
}.getOrNull()

/**
 * The aliased expressions of this subquery's projection, keyed by alias label. Each is read as
 * `alias.label` (via [QueryAlias.get]), so it cannot collide with a same-named alias of the outer
 * query. An expression without a column type is skipped: a filter value could not be converted for it.
 */
private fun QueryAlias.computedFields(): List<SourceField> =
    query.set.fields.filterIsInstance<IExpressionAlias<*>>().mapNotNull { computed ->
        @Suppress("UNCHECKED_CAST")
        val expression = this[computed as Expression<Any?>] as? ExpressionWithColumnType<*> ?: return@mapNotNull null
        SourceField(computed.alias, expression, alias)
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
