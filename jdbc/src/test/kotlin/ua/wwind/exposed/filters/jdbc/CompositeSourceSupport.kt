package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.FieldSet
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IExpressionAlias
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * A common table expression usable as an ordinary [Table] in joins and projections.
 *
 * Exposed 1.4 has no CTE support, so this is the smallest thing that renders one: the CTE is a table
 * named [tableName] whose columns mirror [definition]'s projection (a column keeps its name, an alias
 * uses its label). The column type instance is shared with the source column, so it must never be
 * mutated here: `nullable()` would flip the source table's own column and break its DDL. The `WITH`
 * clause itself is emitted by [WithQuery].
 */
internal class Cte(
    name: String,
    val definition: AbstractQuery<*>,
) : Table(name) {
    init {
        definition.set.fields.forEach { field ->
            val (columnName, type) = when (field) {
                is Column<*> -> field.name to field.columnType
                is IExpressionAlias<*> ->
                    field.alias to requireNotNull((field.delegate as? ExpressionWithColumnType<*>)?.columnType) {
                        "CTE field ${field.alias} has no column type"
                    }
                else -> error("A CTE field must be a column or an alias, got $field")
            }
            @Suppress("UNCHECKED_CAST")
            registerColumn<Any>(columnName, type as IColumnType<Any>)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> col(name: String): Column<T> = columns.single { it.name == name } as Column<T>
}

/** A [Query] that prefixes itself with `WITH <cte>(<columns>) AS (<definition>), …`. */
internal class WithQuery(
    private val ctes: List<Cte>,
    set: FieldSet,
    where: Op<Boolean>? = null,
) : Query(set, where) {
    override fun prepareSQL(builder: QueryBuilder): String {
        val tx = TransactionManager.current()
        builder.append("WITH ")
        ctes.forEachIndexed { index, cte ->
            if (index > 0) builder.append(", ")
            builder.append(tx.identity(cte) + "(")
            builder.append(cte.columns.joinToString(", ") { tx.identity(it) })
            builder.append(") AS (")
            cte.definition.prepareSQL(builder)
            builder.append(")")
        }
        builder.append(" ")
        return super.prepareSQL(builder)
    }
}

/** Re-renders this query prefixed with the given CTEs. */
internal fun Query.withCtes(vararg ctes: Cte): Query = WithQuery(ctes.toList(), set, where)

/**
 * Creates [table] as a session-scoped temporary table. It exists only on the current connection, so
 * the query that reads it must run in the same transaction.
 */
internal fun JdbcTransaction.createTemporary(table: Table) {
    val keyword = if (currentDialect is H2Dialect) "LOCAL TEMPORARY" else "TEMPORARY"
    SchemaUtils.createStatements(table).forEach { statement ->
        exec(statement.replaceFirst(Regex("^CREATE TABLE( IF NOT EXISTS)?"), "CREATE $keyword TABLE"))
    }
}
