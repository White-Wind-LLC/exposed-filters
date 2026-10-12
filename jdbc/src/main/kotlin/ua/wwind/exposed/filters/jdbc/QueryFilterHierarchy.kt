package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Describes the tree a column filtered with `IN_HIERARCHY` points into.
 *
 * @property idColumn The node id column. The tree's table is `idColumn.table`.
 * @property parentColumn The nullable link from a node to its parent's [idColumn]; must be on the same table.
 */
public data class HierarchyInfo(
    public val idColumn: Column<*>,
    public val parentColumn: Column<*>,
)

/**
 * `IN_HIERARCHY`: [expr] equals one of [raws] or the id of one of their descendants. The anchor is the
 * plain `IN` predicate on the tree's id, so values are coerced and compared exactly as `IN` does — except
 * for [FilterOptions.normalizedStringFields]: it vouches for the field's column, not the tree's id column.
 * The field and its tree are checked before empty [raws] are turned into a predicate that matches nothing.
 */
context(mappersModule: ColumnMappersModule?, options: FilterOptions)
internal fun hierarchyPredicate(
    expr: ExpressionWithColumnType<*>,
    raws: List<String>,
    fieldName: String,
): Op<Boolean> {
    require(expr is Column<*>) { "IN_HIERARCHY requires a table column: '$fieldName'" }
    // An alias or subquery column is a clone without `referee`: resolve the tree on the column it
    // projects, but keep comparing against the clone, which is what the outer query can see.
    val hierarchy = resolveHierarchy(expr.originalColumn(), fieldName)
    require(hierarchy.parentColumn.table == hierarchy.idColumn.table) {
        "IN_HIERARCHY on field '$fieldName': parent column " +
            "${hierarchy.parentColumn.table.tableName}.${hierarchy.parentColumn.name} is not on the tree's table " +
            hierarchy.idColumn.table.tableName
    }
    if (raws.isEmpty()) return Op.FALSE
    val anchor = context(options.copy(normalizedStringFields = emptySet())) {
        inListValue(hierarchy.idColumn, raws, fieldName)
    }
    return HierarchyInOp(expr, hierarchy, anchor)
}

/**
 * The tree [column] points into: [FilterOptions.hierarchyResolver]'s answer when it has one; otherwise
 * the column it references — through Exposed's `referee`, then [FilterOptions.referenceResolver] — or the
 * column itself when it references nothing is the tree's id, and the tree's parent link is the single
 * column of that table physically referencing it back.
 */
context(options: FilterOptions)
internal fun resolveHierarchy(column: Column<*>, fieldName: String): HierarchyInfo {
    options.hierarchyResolver?.invoke(column)?.let { return it }
    val reference = resolveReference(column) ?: options.referenceResolver?.invoke(column)
    val idColumn = reference?.referencedIdColumn ?: column
    val tree = idColumn.table
    val parents = tree.columns.filter { resolveReference(it)?.referencedIdColumn == idColumn }
    return when (parents.size) {
        1 -> HierarchyInfo(idColumn, parents.single())
        0 -> error(
            "IN_HIERARCHY on field '$fieldName': no column of ${tree.tableName} references " +
                "${tree.tableName}.${idColumn.name}; declare the tree with FilterOptions.hierarchyResolver"
        )
        else -> error(
            "IN_HIERARCHY on field '$fieldName': ${tree.tableName} has several self-references " +
                "(${parents.joinToString { it.name }}); declare the tree with FilterOptions.hierarchyResolver"
        )
    }
}

/**
 * Renders `expr IN (WITH RECURSIVE ef_tree(id) AS (<anchor nodes> UNION <their children>) SELECT ef_tree.id
 * FROM ef_tree)`. Exposed has no CTE support, but every part is still an Exposed query, so identifiers are
 * quoted per dialect and the anchor's values are bound. `UNION` deduplicates; on PostgreSQL that also stops
 * on cyclic data, but H2 2.4 does not stop a recursive `UNION` on cycles and runs until the query times out.
 */
private class HierarchyInOp(
    private val expr: Column<*>,
    private val hierarchy: HierarchyInfo,
    private val anchor: Op<Boolean>,
) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        val tree = HierarchyCte(hierarchy.idColumn.columnType)
        val table = hierarchy.idColumn.table
        val child = table.alias("ef_child")
        val nodes = table.select(hierarchy.idColumn).where(anchor)
        val children = Join(
            child,
            tree,
            JoinType.INNER,
            onColumn = child[hierarchy.parentColumn],
            otherColumn = tree.id,
        ).select(child[hierarchy.idColumn])
        val tx = TransactionManager.current()
        queryBuilder {
            append(expr)
            append(" IN (WITH RECURSIVE ")
            append(tx.identity(tree))
            append("(")
            append(tx.identity(tree.id))
            append(") AS (")
            nodes.prepareSQL(this)
            append(" UNION ")
            children.prepareSQL(this)
            append(") ")
            tree.select(tree.id).prepareSQL(this)
            append(")")
        }
    }
}

/** The recursive CTE as a table: a single `id` column typed like the tree's id. */
private class HierarchyCte(idType: IColumnType<*>) : Table("ef_tree") {
    @Suppress("UNCHECKED_CAST")
    val id: Column<Any> = registerColumn("id", idType as IColumnType<Any>)
}
