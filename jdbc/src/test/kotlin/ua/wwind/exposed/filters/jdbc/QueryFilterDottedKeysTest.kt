package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterOperator
import ua.wwind.exposed.filters.core.FilterRequest

private object DkProductsTable : Table("dk_products") {
    val id: Column<Int> = integer("id")
    val title: Column<String> = varchar("title", 100)
    val category: Column<String> = varchar("category", 50)
    override val primaryKey = PrimaryKey(id)
}

private object DkOrdersTable : Table("dk_orders") {
    val id: Column<Int> = integer("id")
    val productId: Column<Int> = reference("product_id", DkProductsTable.id)
    val qty: Column<Int> = integer("qty")
    override val primaryKey = PrimaryKey(id)
}

/** Explicit map keys of a join, namespaced the way a caller exposes joined fields under stable names. */
class QueryFilterDottedKeysTest {

    @BeforeEach
    fun setUp() {
        Database.connect(
            url = "jdbc:h2:mem:dotted_keys_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(DkProductsTable, DkOrdersTable)
            insertProduct(1, "Hammer", "tools")
            insertProduct(2, "Apple", "food")
            insertOrder(10, productId = 1, qty = 3)
            insertOrder(11, productId = 1, qty = 4)
            insertOrder(12, productId = 2, qty = 5)
        }
    }

    private fun insertProduct(id: Int, title: String, category: String) {
        DkProductsTable.insert {
            it[DkProductsTable.id] = id
            it[DkProductsTable.title] = title
            it[DkProductsTable.category] = category
        }
    }

    private fun insertOrder(id: Int, productId: Int, qty: Int) {
        DkOrdersTable.insert {
            it[DkOrdersTable.id] = id
            it[DkOrdersTable.productId] = productId
            it[DkOrdersTable.qty] = qty
        }
    }

    @AfterEach
    fun cleanUp() {
        transaction { SchemaUtils.drop(DkOrdersTable, DkProductsTable) }
    }

    private fun leaf(field: String, operator: FilterOperator, value: String) =
        FilterRequest(FilterLeaf(listOf(FieldFilter(field, operator, listOf(value)))))

    private val join = DkOrdersTable.join(DkProductsTable, JoinType.INNER, DkOrdersTable.productId, DkProductsTable.id)

    private val namespaced: Map<String, ExpressionWithColumnType<*>> = mapOf(
        "order.id" to DkOrdersTable.id,
        "productId" to DkOrdersTable.productId,
        "product.title" to DkProductsTable.title,
        "product.category" to DkProductsTable.category,
    )

    private fun orderIds(filter: FilterRequest, columns: Map<String, ExpressionWithColumnType<*>> = namespaced) =
        transaction {
            join.selectAll()
                .applyFilters(columns, filter)
                .map { it[DkOrdersTable.id] }
                .sorted()
        }

    @Test
    fun `a dotted key resolves as a whole key`() {
        assertEquals(listOf(10, 11), orderIds(leaf("product.category", FilterOperator.EQ, "tools")))
    }

    @Test
    fun `the longest key wins over a shorter key the field also starts with`() {
        // "product" would read the title; the whole key "product.category" must be used instead.
        val columns = namespaced + ("product" to DkProductsTable.title)
        assertEquals(listOf(12), orderIds(leaf("product.category", FilterOperator.EQ, "food"), columns))
    }

    @Test
    fun `a reference key without dots still resolves a nested property`() {
        assertEquals(listOf(12), orderIds(leaf("productId.title", FilterOperator.EQ, "Apple")))
    }

    @Test
    fun `a field no key is a prefix of reports its first segment as unknown`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            orderIds(leaf("missing.field", FilterOperator.EQ, "x"))
        }
        assertEquals("Unknown filter field: missing", failure.message)
    }

    @Test
    fun `a dotted aggregate key goes to HAVING`() {
        val totalQty = DkOrdersTable.qty.sum()
        val columns = mapOf("product.category" to DkProductsTable.category, "stock.total" to totalQty)
        val categories = transaction {
            join.select(DkProductsTable.category, totalQty)
                .groupBy(DkProductsTable.category)
                .applyFilters(columns, leaf("stock.total", FilterOperator.GT, "6"))
                .map { it[DkProductsTable.category] }
        }
        assertEquals(listOf("tools"), categories)
    }

    @Test
    fun `a dotted computed field two joined subqueries share is ambiguous`() {
        val left = DkOrdersTable
            .select(DkOrdersTable.productId, DkOrdersTable.qty.sum().alias("stats.total"))
            .groupBy(DkOrdersTable.productId)
            .alias("l")
        val right = DkOrdersTable
            .select(DkOrdersTable.productId, DkOrdersTable.qty.sum().alias("stats.total"))
            .groupBy(DkOrdersTable.productId)
            .alias("r")
        val subqueries = left.join(right, JoinType.INNER, left[DkOrdersTable.productId], right[DkOrdersTable.productId])
        transaction {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                subqueries.selectAll()
                    .applyFiltersOn(subqueries, leaf("stats.total", FilterOperator.GT, "1"))
                    .toList()
            }
            assertTrue(
                failure.message.orEmpty().startsWith("Ambiguous filter field: 'stats.total'"),
                "expected the ambiguity message, got: ${failure.message}",
            )
        }
    }
}
