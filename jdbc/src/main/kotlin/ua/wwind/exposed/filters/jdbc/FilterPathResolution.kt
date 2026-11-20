package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JsonColumnMarker
import org.jetbrains.exposed.v1.core.QueryAlias
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.jdbc.Query
import ua.wwind.exposed.filters.jdbc.FilterPathException.Reason
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A filter field resolved against the source it filters: the root it starts at, every reference it
 * follows and the expression a predicate on it compares. A caller inspects it to check a path (read
 * access per table, allowed fields, depth) by the same rules filtering applies.
 *
 * @property field The filter field as written.
 * @property rootName The key of the expression map the field starts at.
 * @property rootExpression The expression under [rootName].
 * @property hops The references the path follows, in order; empty for a root field and for a JSON
 * path on it.
 * @property expression The expression a predicate compares; for a JSON path, the JSON column it reads.
 * @property jsonPath The JSON path read below [expression]; empty unless the path reaches a JSON column.
 */
public data class ResolvedFilterField(
    public val field: String,
    public val rootName: String,
    public val rootExpression: ExpressionWithColumnType<*>,
    public val hops: List<ResolvedHop>,
    public val expression: ExpressionWithColumnType<*>,
    public val jsonPath: List<String>,
)

/**
 * One reference a filter path follows: where it leaves the previous scope, the table it enters and
 * the field it reads there.
 *
 * @property segment The segment that leaves the previous scope, as written, e.g. `documentId[RECEIPT]`.
 * @property referenceColumn The table column holding the reference, behind any alias clone.
 * @property referenceExpression The reference column as the enclosing scope reads it: the alias clone
 * when the column is read through an alias.
 * @property target The target named for a reference with several targets; `null` otherwise.
 * @property discriminator The column whose value selects [target]; `null` when no target is named.
 * @property reference The target's id column and table.
 * @property occurrence The target table as this hop reads it: the table itself, or an alias of it when
 * a table of that name is already in scope on the path.
 * @property field The field read in the target.
 * @property projection What [FilterOptions.nestedFieldResolver] reads in place of the target's own
 * column; `null` when the column is read.
 * @property source What this hop reads from: the projection's source, or [occurrence].
 * @property idExpression The expression in [source] the reference is compared with.
 * @property expression What this hop reads: the projection's expression, or the target's column as
 * [occurrence] exposes it.
 * @property tables The tables this hop reads, aliases unwrapped: the target table and every table of
 * the projection's source.
 */
public data class ResolvedHop(
    public val segment: String,
    public val referenceColumn: Column<*>,
    public val referenceExpression: Column<*>,
    public val target: String?,
    public val discriminator: Column<*>?,
    public val reference: ReferenceInfo,
    public val occurrence: Table,
    public val field: String,
    public val projection: NestedFieldProjection?,
    public val source: ColumnSet,
    public val idExpression: ExpressionWithColumnType<*>,
    public val expression: ExpressionWithColumnType<*>,
    public val tables: List<Table>,
)

/**
 * Resolves [field] against the fields of this [ColumnSet] exactly as [applyFiltersOn] looks them up,
 * without building SQL or reading rows. The resolvers of [options] run as they do when filtering.
 *
 * @throws FilterPathException when the path cannot be resolved.
 * @throws IllegalArgumentException when [field] starts at a name several sources carry, or a
 * resolver of [options] is misconfigured.
 */
public fun ColumnSet.resolveFilterField(
    field: String,
    options: FilterOptions = DefaultFilterOptions,
): ResolvedFilterField {
    requireUnambiguousField(field)
    return toColumnMap().resolveFilterField(field, options)
}

/**
 * Resolves [field] against this expression map exactly as [applyFilters] looks it up, without
 * building SQL or reading rows. The resolvers of [options] run as they do when filtering.
 *
 * @throws FilterPathException when the path cannot be resolved.
 * @throws IllegalArgumentException when a resolver of [options] is misconfigured.
 */
public fun Map<String, ExpressionWithColumnType<*>>.resolveFilterField(
    field: String,
    options: FilterOptions = DefaultFilterOptions,
): ResolvedFilterField = context(options) { resolveField(this, field) }

/**
 * Walks [field] from its root through every reference it names. Each segment that leads further is
 * checked in order — a JSON column ends the walk, otherwise a column, the depth limit and its
 * reference — before the next segment is read in the reference's target.
 */
context(options: FilterOptions)
internal fun resolveField(
    expressions: Map<String, ExpressionWithColumnType<*>>,
    field: String,
): ResolvedFilterField {
    val split = splitField(field) { it in expressions }
    val rootExpression = expressions[split.baseName] ?: throw FilterPathException(
        field = field,
        segmentIndex = 0,
        segment = split.rootSegment,
        reason = Reason.UNKNOWN_FIELD,
        message = "Unknown filter field: ${split.rootSegment}",
    )
    val segments = split.path?.split('.').orEmpty()

    val hops = mutableListOf<ResolvedHop>()
    val scope = mutableSetOf<String>()
    (rootExpression as? Column<*>)?.let { scope += it.table.scopeName() }
    var current = rootExpression
    var at = PathSegment(split.rootSegment, split.baseName, split.rootTarget)
    for ((position, raw) in segments.withIndex()) {
        if (current.columnType is JsonColumnMarker) {
            if (at.target != null) {
                throw pathFailure(field, position, at, Reason.TARGET_NOT_SUPPORTED, "names a target of a JSON column")
            }
            val jsonPath = parseJsonPath(field, position + 1, segments.subList(position, segments.size))
            return ResolvedFilterField(field, split.baseName, rootExpression, hops, current, jsonPath)
        }
        val from = current as? Column<*>
            ?: throw pathFailure(field, position, at, Reason.NOT_A_COLUMN, "is a computed expression, not a column")
        val hopNumber = hops.size + 1
        val maxHops = options.maxReferenceHops
        if (maxHops != null && hopNumber > maxHops) {
            val problem = "would follow reference $hopNumber, past FilterOptions.maxReferenceHops ($maxHops)"
            throw pathFailure(field, position, at, Reason.DEPTH_EXCEEDED, problem)
        }
        val reference = followReference(field, position, at, from)
        val next = parsePathSegment(field, position + 1, raw)
        val hop = enterTarget(field, position, at, from, reference, next, hopNumber, scope)
        hops += hop
        scope += hop.source.scopeNames()
        current = hop.expression
        at = next
    }
    if (at.target != null) {
        throw pathFailure(field, segments.size, at, Reason.TARGET_WITHOUT_FIELD, "names a target but no field of it")
    }
    return ResolvedFilterField(field, split.baseName, rootExpression, hops, current, emptyList())
}

/** The table and id column the reference [from] holds points at, segment [at] leading through it. */
context(options: FilterOptions)
private fun followReference(field: String, position: Int, at: PathSegment, from: Column<*>): ReferenceInfo {
    if (at.target != null) {
        throw pathFailure(field, position, at, Reason.TARGET_NOT_SUPPORTED, "names a target of a plain reference")
    }
    // An alias or subquery column is a clone without `referee`: the reference is resolved on the
    // column it was made from.
    val original = from.originalColumn()
    return resolveReference(original)
        ?: options.referenceResolver?.invoke(original)
        ?: throw pathFailure(field, position, at, Reason.NOT_A_REFERENCE, "is not a reference to follow")
}

/**
 * Enters the target of [reference] and reads the segment [next] there. The target is aliased
 * `ef_<hopNumber>` when a table of its name is already in [scope], so it never shadows that one.
 */
context(options: FilterOptions)
private fun enterTarget(
    field: String,
    position: Int,
    at: PathSegment,
    from: Column<*>,
    reference: ReferenceInfo,
    next: PathSegment,
    hopNumber: Int,
    scope: Set<String>,
): ResolvedHop {
    val target = reference.referencedTable
    require(reference.referencedIdColumn.table == target) {
        "Filter field '$field': the reference at segment '${at.raw}' resolves to " +
            "${reference.referencedIdColumn.table.tableName}.${reference.referencedIdColumn.name}, " +
            "which is not a column of ${target.tableName}"
    }
    val alias = if (target.scopeName() in scope) target.alias("ef_$hopNumber") else null
    val occurrence: Table = alias ?: target
    val projection = options.nestedFieldResolver?.invoke(occurrence, next.name)
    val expression = projection?.expression
        ?: (target.fieldMap()[next.name] as? Column<*>)?.let { alias?.get(it) ?: it }
        ?: throw pathFailure(field, position + 1, next, Reason.UNKNOWN_FIELD, "is not a field of ${target.tableName}")

    // The id the reference is compared against must come from the hop's own source: a projection that
    // renames or replaces the target table has to say where the id went.
    val idExpression = projection?.idExpression ?: reference.referencedIdColumn.let { alias?.get(it) ?: it }.also {
        require(projection == null || it in projection.source.columns) {
            "Nested field $field: the projection's source does not expose ${it.table.tableName}.${it.name}; " +
                "set NestedFieldProjection.idExpression to the expression in the source that holds it"
        }
    }
    if (projection != null) {
        checkProjectionScope(field, at, projection, idExpression, expression, scope)
    }
    return ResolvedHop(
        segment = at.raw,
        referenceColumn = from.originalColumn(),
        referenceExpression = from,
        target = null,
        discriminator = null,
        reference = reference,
        occurrence = occurrence,
        field = next.name,
        projection = projection,
        source = projection?.source ?: occurrence,
        idExpression = idExpression,
        expression = expression,
        tables = (listOf(target) + projection?.source?.readTables().orEmpty()).distinct(),
    )
}

/**
 * Refuses a projection that would bind a reference to the wrong scope: a source that makes visible a
 * name already in [outside], or an expression reading a column of a table the source does not make
 * visible. Both render SQL that compares against another row, so the resolver is the one at fault.
 */
private fun checkProjectionScope(
    field: String,
    at: PathSegment,
    projection: NestedFieldProjection,
    idExpression: Expression<*>,
    expression: Expression<*>,
    outside: Set<String>,
) {
    val visible = projection.source.scopeNames()
    val shadowed = visible.intersect(outside)
    require(shadowed.isEmpty()) {
        "Filter field '$field': the projection at segment '${at.raw}' makes ${shadowed.joinToString()} visible, " +
            "which is already in scope outside it; join an alias of it in the source instead"
    }
    listOf("expression" to expression, "idExpression" to idExpression).forEach { (label, value) ->
        val foreign = value.referencedColumns().map { it.table.scopeName() }.filterNot { it in visible }.distinct()
        require(foreign.isEmpty()) {
            "Filter field '$field': the $label of the projection at segment '${at.raw}' reads " +
                "${foreign.joinToString()}, which its source does not make visible (${visible.joinToString()})"
        }
    }
}

/**
 * The columns this expression reads, found by walking its [Expression], [Iterable], [Array] and [Pair]
 * fields as [expressionKind] does. A [Column] is collected and not entered; an alias-only reference
 * reads an already computed value and a [org.jetbrains.exposed.v1.core.Query] is a scope of its own, so
 * neither is entered.
 */
internal fun Expression<*>.referencedColumns(): Set<Column<*>> =
    LinkedHashSet<Column<*>>().also { collectColumns(this, it, Collections.newSetFromMap(IdentityHashMap())) }

private fun collectColumns(value: Any?, into: MutableSet<Column<*>>, visited: MutableSet<Any>) {
    when (value) {
        is Column<*> -> into += value
        is Query -> Unit
        is Expression<*> -> if (visited.add(value) && !value.isAliasOnlyReference()) {
            value.instanceFieldValues().forEach { collectColumns(it, into, visited) }
        }
        is Iterable<*> -> value.forEach { collectColumns(it, into, visited) }
        is Array<*> -> value.forEach { collectColumns(it, into, visited) }
        is Pair<*, *> -> {
            collectColumns(value.first, into, visited)
            collectColumns(value.second, into, visited)
        }
    }
}

private fun pathFailure(
    field: String,
    position: Int,
    segment: PathSegment,
    reason: Reason,
    problem: String,
): FilterPathException = FilterPathException(
    field = field,
    segmentIndex = position,
    segment = segment.raw,
    reason = reason,
    message = "Filter field '$field': segment '${segment.raw}' at position $position $problem",
)

/** The name SQL refers to this table by within a query: an alias's name, or the table name. */
internal fun Table.scopeName(): String = (this as? Alias<*>)?.alias ?: tableName

/**
 * The names this source makes visible to the conditions of a query reading it: a table's or an
 * alias's name, a subquery's alias, and those of every part of a join.
 */
internal fun ColumnSet.scopeNames(): Set<String> = when (this) {
    is Table -> setOf(scopeName())
    is QueryAlias -> setOf(alias)
    is Join -> flattenSources()?.flatMapTo(LinkedHashSet()) { it.scopeNames() } ?: columnTableNames()
    else -> columnTableNames()
}

private fun ColumnSet.columnTableNames(): Set<String> = columns.mapTo(LinkedHashSet()) { it.table.scopeName() }

/**
 * The tables a query reading this source reads, aliases unwrapped: a table itself, the parts of a
 * join, and the tables a subquery's own source reads.
 */
internal fun ColumnSet.readTables(): List<Table> = when (this) {
    is Alias<*> -> delegate.readTables()
    is Table -> listOf(this)
    is QueryAlias -> query.set.source.readTables()
    is Join -> flattenSources()?.flatMap { it.readTables() } ?: columnTables()
    else -> columnTables()
}.distinct()

private fun ColumnSet.columnTables(): List<Table> = columns.map { it.table }.distinct().flatMap { it.readTables() }
