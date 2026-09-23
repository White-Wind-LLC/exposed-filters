@file:OptIn(ExperimentalUuidApi::class)

package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.union
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterOperator
import ua.wwind.exposed.filters.core.FilterRequest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal object CsProducts : Table("cs_products") {
    val id: Column<Uuid> = uuid("id")
    val name: Column<String> = varchar("name", 100)
    override val primaryKey = PrimaryKey(id)
}

internal object CsTranslations : Table("cs_product_translations") {
    val productId: Column<Uuid> = reference("product_id", CsProducts.id)
    val language: Column<String> = varchar("language", 8)
    val name: Column<String?> = varchar("name", 100).nullable()
    override val primaryKey = PrimaryKey(productId, language)
}

internal object CsBalances : Table("cs_balances") {
    val id: Column<Int> = integer("id").autoIncrement()
    val sku: Column<String> = varchar("sku", 50)
    val productId: Column<Uuid> = reference("product_id", CsProducts.id)
    val qty: Column<Int> = integer("qty")
    override val primaryKey = PrimaryKey(id)
}

/** Only ever created as a temporary table, see [createTemporary]. */
internal object CsReservations : Table("cs_reservations") {
    val sku: Column<String> = varchar("sku", 50)
    val reserved: Column<Int> = integer("reserved")
}

internal val PRODUCT_A: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000a1")
internal val PRODUCT_B: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000b2")
internal val PRODUCT_C: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000c3")

/**
 * Filters applied to a result assembled from anything other than plain tables: CTEs, subqueries,
 * temporary tables, unions, and joins between them.
 *
 * Every test states the behaviour the library should have — a filter adds its condition to the
 * result, whatever the result was assembled from, and knows nothing about how its sources are
 * joined. A test the library does not pass yet is `@Disabled` with the gap that blocks it
 * (`GAP-n`); closing a gap means removing the annotation, not rewriting the expectation.
 *
 * The data set:
 *
 * | product | name  | uk name | balances (sku: qty)   | reservations (temp) |
 * |---------|-------|---------|-----------------------|---------------------|
 * | A       | Alpha | Альфа   | BAL-A1: 10, BAL-A2: 3 | BAL-A1: 4           |
 * | B       | Beta  | —       | BAL-B1: 7             | BAL-B1: 7           |
 * | C       | Gamma | Гамма   | BAL-C1: 1             | —                   |
 */
abstract class CompositeSourceFilterContract {

    protected abstract fun connect()

    @BeforeEach
    fun setUp() {
        connect()
        transaction {
            SchemaUtils.create(CsProducts, CsTranslations, CsBalances)
            product(PRODUCT_A, "Alpha", uk = "Альфа")
            product(PRODUCT_B, "Beta", uk = null)
            product(PRODUCT_C, "Gamma", uk = "Гамма")
            balance("BAL-A1", PRODUCT_A, 10)
            balance("BAL-A2", PRODUCT_A, 3)
            balance("BAL-B1", PRODUCT_B, 7)
            balance("BAL-C1", PRODUCT_C, 1)
        }
    }

    @AfterEach
    fun tearDown() {
        transaction { SchemaUtils.drop(CsBalances, CsTranslations, CsProducts) }
    }

    private fun product(id: Uuid, name: String, uk: String?) {
        CsProducts.insert {
            it[CsProducts.id] = id
            it[CsProducts.name] = name
        }
        if (uk != null) {
            CsTranslations.insert {
                it[productId] = id
                it[language] = "uk"
                it[CsTranslations.name] = uk
            }
        }
    }

    private fun balance(sku: String, product: Uuid, qty: Int) {
        CsBalances.insert {
            it[CsBalances.sku] = sku
            it[productId] = product
            it[CsBalances.qty] = qty
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.reservations() {
        createTemporary(CsReservations)
        CsReservations.insert {
            it[sku] = "BAL-A1"
            it[reserved] = 4
        }
        CsReservations.insert {
            it[sku] = "BAL-B1"
            it[reserved] = 7
        }
    }

    private fun where(vararg filters: FieldFilter) = FilterRequest(FilterLeaf(filters.toList()))

    private fun field(name: String, operator: FilterOperator, vararg values: String) =
        FieldFilter(name, operator, values.toList())

    /** `id`, `display_name`: the product name as a Ukrainian user sees it. */
    private fun displayNames(): Query {
        val displayName = coalesce(CsTranslations.name, CsProducts.name).alias("display_name")
        return CsProducts
            .leftJoin(
                otherTable = CsTranslations,
                additionalConstraint = {
                    (CsTranslations.productId eq CsProducts.id) and (CsTranslations.language eq "uk")
                },
            )
            .select(CsProducts.id, displayName)
    }

    /** `product_id`, `total`: stock per product. */
    private fun stockTotals(): Query = CsBalances
        .select(CsBalances.productId, CsBalances.qty.sum().alias("total"))
        .groupBy(CsBalances.productId)

    /** `sku`, `product_name`, `qty`. */
    private fun balanceView(): Query = CsBalances
        .innerJoin(CsProducts, { CsBalances.productId }, { CsProducts.id })
        .select(CsBalances.sku, CsProducts.name.alias("product_name"), CsBalances.qty)

    @Test
    fun `table joined to a temporary table`() {
        transaction {
            reservations()
            val join = CsBalances.innerJoin(CsReservations, { CsBalances.sku }, { CsReservations.sku })

            val skus = join.select(CsBalances.sku)
                .applyFiltersOn(join, where(field("reserved", FilterOperator.GTE, "5")))
                .map { it[CsBalances.sku] }

            assertEquals(listOf("BAL-B1"), skus)
        }
    }

    @Disabled("GAP-1 (#8): computed fields of a subquery are not filterable, QueryAlias.columns holds only plain columns")
    @Test
    fun `subquery joined to subquery, filtered on computed fields of both`() {
        transaction {
            val names = displayNames().alias("dn")
            val stock = stockTotals().alias("stock")
            val join = stock.innerJoin(names, { stock[CsBalances.productId] }, { names[CsProducts.id] })

            val ids = join.select(stock[CsBalances.productId])
                .applyFiltersOn(
                    join,
                    where(field("display_name", FilterOperator.CONTAINS, "льф"), field("total", FilterOperator.GT, "5")),
                )
                .map { it[stock[CsBalances.productId]] }

            assertEquals(listOf(PRODUCT_A), ids)
        }
    }

    @Test
    fun `cte joined to cte`() {
        transaction {
            val names = Cte("cs_names", displayNames())
            val stock = Cte("cs_stock", stockTotals())
            val productId = stock.col<Uuid>("product_id")
            val join = stock.innerJoin(names, { productId }, { names.col<Uuid>("id") })

            val ids = join.select(productId).withCtes(names, stock)
                .applyFiltersOn(
                    join,
                    where(field("display_name", FilterOperator.CONTAINS, "льф"), field("total", FilterOperator.GT, "5")),
                )
                .map { it[productId] }

            assertEquals(listOf(PRODUCT_A), ids)
        }
    }

    @Disabled("GAP-1 (#8): computed fields of a subquery are not filterable, QueryAlias.columns holds only plain columns")
    @Test
    fun `cte joined to subquery`() {
        transaction {
            val names = Cte("cs_names", displayNames())
            val stock = stockTotals().alias("stock")
            val productId = stock[CsBalances.productId]
            val join = stock.innerJoin(names, { productId }, { names.col<Uuid>("id") })

            val ids = join.select(productId).withCtes(names)
                .applyFiltersOn(
                    join,
                    where(field("display_name", FilterOperator.CONTAINS, "льф"), field("total", FilterOperator.GT, "5")),
                )
                .map { it[productId] }

            assertEquals(listOf(PRODUCT_A), ids)
        }
    }

    @Test
    fun `temporary table joined to cte`() {
        transaction {
            reservations()
            val view = Cte("cs_balance_view", balanceView())
            val sku = view.col<String>("sku")
            val join = view.innerJoin(CsReservations, { sku }, { CsReservations.sku })

            val skus = join.select(sku).withCtes(view)
                .applyFiltersOn(
                    join,
                    where(field("product_name", FilterOperator.EQ, "Alpha"), field("reserved", FilterOperator.GT, "0")),
                )
                .map { it[sku] }

            assertEquals(listOf("BAL-A1"), skus)
        }
    }

    @Disabled("GAP-4 (#11): a Table resolves fields by Kotlin property only, columns registered at runtime are invisible")
    @Test
    fun `a single cte as the whole source`() {
        transaction {
            val names = Cte("cs_names", displayNames())
            val id = names.col<Uuid>("id")

            val ids = names.select(id).withCtes(names)
                .applyFiltersOn(names, where(field("display_name", FilterOperator.CONTAINS, "амм")))
                .map { it[id] }

            assertEquals(listOf(PRODUCT_C), ids)
        }
    }

    @Test
    fun `union as a source`() {
        transaction {
            reservations()
            val union = CsBalances.select(CsBalances.sku, CsBalances.qty)
                .union(CsReservations.select(CsReservations.sku, CsReservations.reserved))
                .alias("u")

            val skus = union.selectAll()
                .applyFiltersOn(union, where(field("qty", FilterOperator.GTE, "7")))
                .map { it[union[CsBalances.sku]] }
                .sorted()

            assertEquals(listOf("BAL-A1", "BAL-B1"), skus)
        }
    }

    @Disabled("GAP-1 (#8): computed fields of a subquery are not filterable, QueryAlias.columns holds only plain columns")
    @Test
    fun `the assembled result filtered as a whole, including a computed field`() {
        transaction {
            reservations()
            val view = Cte("cs_balance_view", balanceView())
            val sku = view.col<String>("sku")
            val available = (view.col<Int>("qty") - CsReservations.reserved).alias("available")
            val result = view.innerJoin(CsReservations, { sku }, { CsReservations.sku })
                .select(sku, view.col<String>("product_name"), available)
                .withCtes(view)
                .alias("r")

            val skus = result.selectAll()
                .applyFiltersOn(result, where(field("available", FilterOperator.GT, "3")))
                .map { it[result[sku]] }

            assertEquals(listOf("BAL-A1"), skus)
        }
    }

    @Test
    fun `the same column name from two sources is ambiguous, not silently resolved`() {
        transaction {
            val translations = CsTranslations.selectAll().where { CsTranslations.language eq "uk" }.alias("tr")
            val join = CsProducts.innerJoin(translations, { CsProducts.id }, { translations[CsTranslations.productId] })

            // Both sides expose `name`. Resolving it to either one filters the wrong column for someone.
            assertThrows(IllegalArgumentException::class.java) {
                join.select(CsProducts.id)
                    .applyFiltersOn(join, where(field("name", FilterOperator.EQ, "Альфа")))
                    .toList()
            }
        }
    }

    @Test
    fun `an ambiguous field is reported with the sources that expose it`() {
        transaction {
            val translations = CsTranslations.selectAll().where { CsTranslations.language eq "uk" }.alias("tr")
            val join = CsProducts.innerJoin(translations, { CsProducts.id }, { translations[CsTranslations.productId] })

            val error = assertThrows(IllegalArgumentException::class.java) {
                join.select(CsProducts.id)
                    .applyFiltersOn(join, where(field("name", FilterOperator.EQ, "Альфа")))
            }

            val message = error.message.orEmpty()
            assertTrue("'name'" in message, message)
            assertTrue("'cs_products'" in message, message)
            assertTrue("'tr'" in message, message)
        }
    }

    @Test
    fun `a join that shares a column name still filters on its unambiguous fields`() {
        transaction {
            val translations = CsTranslations.selectAll().alias("tr")
            val join = CsProducts.innerJoin(translations, { CsProducts.id }, { translations[CsTranslations.productId] })

            val ids = join.select(CsProducts.id)
                .applyFiltersOn(join, where(field("language", FilterOperator.EQ, "uk")))
                .map { it[CsProducts.id] }
                .sortedBy { it.toString() }

            assertEquals(listOf(PRODUCT_A, PRODUCT_C), ids)
        }
    }

    @Disabled("GAP-3 (#10): predicates always go to WHERE, an aggregate needs HAVING or an outer query")
    @Test
    fun `a filter on an aggregate in the projection`() {
        transaction {
            val total = CsBalances.qty.sum()

            val ids = CsBalances.select(CsBalances.productId, total)
                .groupBy(CsBalances.productId)
                .applyFilters(mapOf("total" to total), where(field("total", FilterOperator.GT, "5")))
                .map { it[CsBalances.productId] }
                .sortedBy { it.toString() }

            assertEquals(listOf(PRODUCT_A, PRODUCT_B), ids)
        }
    }

    @Disabled("GAP-5 (#12): a subquery column is a clone without referee, so a reference path cannot resolve")
    @Test
    fun `a reference path through a subquery column`() {
        transaction {
            val stock = stockTotals().alias("stock")
            val productId = stock[CsBalances.productId]

            val ids = stock.selectAll()
                .applyFiltersOn(stock, where(field("product_id.name", FilterOperator.EQ, "Alpha")))
                .map { it[productId] }

            assertEquals(listOf(PRODUCT_A), ids)
        }
    }

    @Test
    fun `a nested field resolver reading from a cte alone`() {
        transaction {
            val names = Cte("cs_names", displayNames())
            val options = FilterOptions(
                nestedFieldResolver = { table, nestedField ->
                    if (table === CsProducts && nestedField == "name") {
                        NestedFieldProjection(
                            source = names,
                            expression = names.col<String>("display_name"),
                            idExpression = names.col<Uuid>("id"),
                        )
                    } else {
                        null
                    }
                },
            )

            val skus = CsBalances.select(CsBalances.sku).withCtes(names)
                .applyFiltersOn(CsBalances, where(field("productId.name", FilterOperator.EQ, "Альфа")), options = options)
                .map { it[CsBalances.sku] }
                .sorted()

            assertEquals(listOf("BAL-A1", "BAL-A2"), skus)
        }
    }
}
