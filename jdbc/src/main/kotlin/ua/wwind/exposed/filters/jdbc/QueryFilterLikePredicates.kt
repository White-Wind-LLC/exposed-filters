package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.ComplexExpression
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import ua.wwind.exposed.filters.core.FilterOperator

/**
 * LIKE predicate for string columns: `CONTAINS`, `STARTS_WITH` or `ENDS_WITH` [value], matched literally.
 * `%`, `_` and the escape character in [value] are escaped, so they never act as wildcards.
 */
context(options: FilterOptions)
internal fun likeString(
    expr: ExpressionWithColumnType<*>,
    operator: FilterOperator,
    value: String,
    fieldName: String
): Op<Boolean> = when (expr.columnType.withoutAutoInc()) {
    is VarCharColumnType, is TextColumnType -> {
        @Suppress("UNCHECKED_CAST")
        val stringExpr = expr as ExpressionWithColumnType<String>
        when {
            options.caseSensitiveStrings -> LiteralLikeOp(stringExpr, operator, value)
            options.usesNormalizedComparison(fieldName) -> LiteralLikeOp(stringExpr, operator, value.lowercase())
            else -> LiteralLikeOp(stringExpr.lowerCase(), operator, value.lowercase())
        }
    }
    else -> error("LIKE is only supported for string fields: '$fieldName'")
}

/**
 * Renders `expr LIKE <escaped value with wildcards> ESCAPE '\'`. The pattern is built while rendering, not
 * when the filter is applied: [LikePattern.ofLiteral] escapes the special characters of the current dialect,
 * which needs a transaction, and a query may be filtered outside one and executed later.
 */
private class LiteralLikeOp(
    private val expr: Expression<String>,
    private val operator: FilterOperator,
    private val value: String,
) : Op<Boolean>(), ComplexExpression {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        val literal = LikePattern.ofLiteral(value)
        val anyText = LikePattern("%", literal.escapeChar)
        val pattern = when (operator) {
            FilterOperator.CONTAINS -> anyText + literal + anyText
            FilterOperator.STARTS_WITH -> literal + anyText
            FilterOperator.ENDS_WITH -> anyText + literal
            else -> error("LIKE is not used for $operator")
        }
        expr.like(pattern).toQueryBuilder(queryBuilder)
    }
}
