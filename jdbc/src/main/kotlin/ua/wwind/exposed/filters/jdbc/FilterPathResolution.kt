package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.AbstractQuery
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
import org.jetbrains.exposed.v1.jdbc.SetOperation
import ua.wwind.exposed.filters.jdbc.FilterPathException.Reason
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A filter field resolved by the rules filtering applies, so a caller can check every hop of it.
 *
 * @property expression What a predicate compares; for a JSON path, the JSON column [jsonPath] is read from.
 */
public class ResolvedFilterField internal constructor(
    public val field: String,
    public val rootName: String,
    public val rootExpression: ExpressionWithColumnType<*>,
    public val hops: List<ResolvedHop>,
    public val expression: ExpressionWithColumnType<*>,
    public val jsonPath: List<String>,
) {
    override fun toString(): String =
        "ResolvedFilterField(field=$field, rootName=$rootName, rootExpression=${rootExpression.describe()}, " +
            "hops=$hops, expression=${expression.describe()}, jsonPath=$jsonPath)"
}

/**
 * One reference a filter path follows.
 *
 * @property referenceExpression [referenceColumn] as the enclosing scope reads it, an alias clone included.
 * @property occurrence The target table, or its `ef_<n>` alias when a table of that name is already in scope.
 * @property tables Every table the hop's subquery reads, except those read only by a scalar subquery.
 */
public class ResolvedHop internal constructor(
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
) {
    override fun toString(): String =
        "ResolvedHop(segment=$segment, target=$target, reference=${reference.referencedIdColumn.describe()}, " +
            "occurrence=${occurrence.describe()}, field=$field, projection=${projection?.expression?.describe()}, " +
            "source=${source.describe()}, idExpression=${idExpression.describe()}, " +
            "expression=${expression.describe()}, tables=${tables.map { it.describe() }})"
}

private fun Expression<*>.describe(): String = if (this is Column<*>) "${table.qualifier()}.$name" else toString()

private fun ColumnSet.describe(): String = when (this) {
    is Alias<*> -> "${delegate.describe()} $alias"
    is Table -> tableName
    is QueryAlias -> alias
    else -> scopeNames().joinToString(prefix = "[", postfix = "]")
}

/**
 * Resolves [field] as [applyFiltersOn] does, without building SQL.
 *
 * @throws FilterPathException when the path cannot be resolved.
 * @throws IllegalArgumentException when the root name is ambiguous or a resolver is misconfigured.
 */
public fun ColumnSet.resolveFilterField(
    field: String,
    options: FilterOptions = DefaultFilterOptions,
): ResolvedFilterField {
    requireUnambiguousField(field)
    return toColumnMap().resolveFilterField(field, options)
}

/**
 * Resolves [field] as [applyFilters] does, without building SQL.
 *
 * @throws FilterPathException when the path cannot be resolved.
 * @throws IllegalArgumentException when a resolver is misconfigured.
 */
public fun Map<String, ExpressionWithColumnType<*>>.resolveFilterField(
    field: String,
    options: FilterOptions = DefaultFilterOptions,
): ResolvedFilterField = context(options) { resolveField(this, field) }

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
        val followed = followReference(field, position, at, from)
        val next = parsePathSegment(field, position + 1, raw)
        val hop = enterTarget(field, position, at, from, followed, next, hopNumber, scope)
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

private class FollowedReference(val reference: ReferenceInfo, val discriminator: Column<*>?)

/** A segment naming a target follows only the polymorphic resolver; one naming none, `referee` first. */
context(options: FilterOptions)
private fun followReference(field: String, position: Int, at: PathSegment, from: Column<*>): FollowedReference {
    // An alias or subquery column is a clone without `referee`.
    val original = from.originalColumn()
    val target = at.target
    if (target == null) {
        val reference = resolveReference(original) ?: options.referenceResolver?.invoke(original)
        if (reference != null) return FollowedReference(reference, discriminator = null)
        val polymorphic = options.polymorphicReferenceResolver?.invoke(original)
        if (polymorphic != null) {
            requireDiscriminatorBeside(field, at, polymorphic, original)
            val problem = "has several targets; name one of ${polymorphic.targets.keys.joinToString()}"
            throw pathFailure(field, position, at, Reason.TARGET_REQUIRED, problem)
        }
        throw pathFailure(field, position, at, Reason.NOT_A_REFERENCE, "is not a reference to follow")
    }
    val polymorphic = options.polymorphicReferenceResolver?.invoke(original) ?: throw pathFailure(
        field = field,
        position = position,
        segment = at,
        reason = Reason.TARGET_NOT_SUPPORTED,
        problem = "names a target, but its column has no choice of targets",
    )
    requireDiscriminatorBeside(field, at, polymorphic, original)
    val discriminator = polymorphic.discriminator
    require(from.scopedDiscriminator(discriminator) != null) {
        "Filter field '$field': the reference at segment '${at.raw}' is read through ${from.table.scopeName()}, " +
            "which does not carry its discriminator ${discriminator.table.tableName}.${discriminator.name}; " +
            "read the reference through a source that selects the discriminator"
    }
    val reference = polymorphic.targets[target] ?: throw pathFailure(
        field = field,
        position = position,
        segment = at,
        reason = Reason.UNKNOWN_TARGET,
        problem = "names a target its reference does not know; known: ${polymorphic.targets.keys.joinToString()}",
    )
    return FollowedReference(reference, discriminator)
}

private fun requireDiscriminatorBeside(
    field: String,
    at: PathSegment,
    polymorphic: PolymorphicReferenceInfo,
    reference: Column<*>,
) {
    val discriminator = polymorphic.discriminator
    require(discriminator.table == reference.table) {
        "Filter field '$field': the discriminator of the reference at segment '${at.raw}' is " +
            "${discriminator.table.tableName}.${discriminator.name}, " +
            "which is not a column of ${reference.table.tableName}"
    }
}

/** [discriminator] as this column's scope reads it, through nested aliases; `null` when an alias lacks it. */
internal fun Column<*>.scopedDiscriminator(discriminator: Column<*>): Column<*>? =
    table.columns.firstOrNull { it.originalColumn() == discriminator } ?: discriminator.takeIf { table !is Alias<*> }

/** The target is aliased `ef_<hopNumber>` when a table of its name is already in [scope]. */
context(options: FilterOptions)
private fun enterTarget(
    field: String,
    position: Int,
    at: PathSegment,
    from: Column<*>,
    followed: FollowedReference,
    next: PathSegment,
    hopNumber: Int,
    scope: Set<String>,
): ResolvedHop {
    val reference = followed.reference
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

    // A projection that renames or replaces the target must say where its id is.
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
        target = at.target,
        discriminator = followed.discriminator,
        reference = reference,
        occurrence = occurrence,
        field = next.name,
        projection = projection,
        source = projection?.source ?: occurrence,
        idExpression = idExpression,
        expression = expression,
        tables = (target.readTables() + projection?.source?.readTables().orEmpty()).distinct(),
    )
}

/** Refuses a projection whose source or expressions would bind to a scope other than its own. */
private fun checkProjectionScope(
    field: String,
    at: PathSegment,
    projection: NestedFieldProjection,
    idExpression: Expression<*>,
    expression: Expression<*>,
    outside: Set<String>,
) {
    val shadowed = projection.source.scopeNames().intersect(outside)
    require(shadowed.isEmpty()) {
        "Filter field '$field': the projection at segment '${at.raw}' makes ${shadowed.joinToString()} visible, " +
            "which is already in scope outside it; join an alias of it in the source instead"
    }
    // A column is rendered with its schema, so only the same qualifier binds it to the source.
    val visible = projection.source.scopeNames { it.qualifier() }
    listOf("expression" to expression, "idExpression" to idExpression).forEach { (label, value) ->
        val foreign = value.referencedColumns().map { it.table.qualifier() }.filterNot { it in visible }.distinct()
        require(foreign.isEmpty()) {
            "Filter field '$field': the $label of the projection at segment '${at.raw}' reads " +
                "${foreign.joinToString()}, which its source does not make visible (${visible.joinToString()})"
        }
    }
}

/**
 * The columns this expression reads. Neither an alias-only reference nor an [AbstractQuery] is entered:
 * both read another scope, and iterating a query would run it.
 */
internal fun Expression<*>.referencedColumns(): Set<Column<*>> =
    LinkedHashSet<Column<*>>().also { collectColumns(this, it, Collections.newSetFromMap(IdentityHashMap())) }

private fun collectColumns(value: Any?, into: MutableSet<Column<*>>, visited: MutableSet<Any>) {
    when (value) {
        is Column<*> -> into += value
        is AbstractQuery<*> -> Unit
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

/** The name a query's conditions see: an unaliased `FROM archive.orders` is `orders` and hides an outer one. */
internal fun Table.scopeName(): String =
    (this as? Alias<*>)?.alias ?: if (schemaName == null) tableName else tableName.substringAfterLast('.')

/** The qualifier a column of this table is rendered with, schema included. */
internal fun Table.qualifier(): String = (this as? Alias<*>)?.alias ?: tableName

/** The names this source makes visible to a query's conditions. */
internal fun ColumnSet.scopeNames(name: (Table) -> String = { it.scopeName() }): Set<String> = when (this) {
    is Table -> setOf(name(this))
    is QueryAlias -> setOf(alias)
    is Join -> flattenSources()?.flatMapTo(LinkedHashSet()) { it.scopeNames(name) } ?: columnTableNames(name)
    else -> columnTableNames(name)
}

private fun ColumnSet.columnTableNames(name: (Table) -> String): Set<String> =
    columns.mapTo(LinkedHashSet()) { name(it.table) }

/** The tables a query over this source reads, aliases unwrapped; scalar subqueries are not followed. */
internal fun ColumnSet.readTables(): List<Table> = when (this) {
    is Alias<*> -> delegate.readTables()
    is Table -> listOf(this)
    is QueryAlias -> query.readTables()
    is Join -> flattenSources()?.flatMap { it.readTables() } ?: columnTables()
    else -> columnTables()
}.distinct()

/** A set operation's own source is its first statement's only, so both statements are read. */
internal fun AbstractQuery<*>.readTables(): List<Table> = when (this) {
    is SetOperation -> (firstStatement.readTables() + secondStatement.readTables()).distinct()
    else -> set.source.readTables()
}

private fun ColumnSet.columnTables(): List<Table> = columns.map { it.table }.distinct().flatMap { it.readTables() }
