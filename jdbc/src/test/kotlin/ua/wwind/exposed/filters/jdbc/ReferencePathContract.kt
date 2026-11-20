package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.exceptions.UnsupportedByDialectException
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.json.json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterCombinator
import ua.wwind.exposed.filters.core.FilterGroup
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterOperator
import ua.wwind.exposed.filters.core.FilterRequest

internal object RpRegions : Table("rp_regions") {
    val id: Column<Int> = integer("id")
    val name: Column<String> = varchar("name", 50)
    override val primaryKey = PrimaryKey(id)
}

/** A translation beside [RpRegions], the source a projection reads a localized name from. */
internal object RpRegionTranslations : Table("rp_region_translations") {
    val regionId: Column<Int> = reference("region_id", RpRegions.id)
    val language: Column<String> = varchar("language", 8)
    val name: Column<String?> = varchar("name", 50).nullable()
    override val primaryKey = PrimaryKey(regionId, language)
}

internal object RpCustomers : Table("rp_customers") {
    val id: Column<Int> = integer("id")
    val name: Column<String> = varchar("name", 50)
    val regionId: Column<Int?> = reference("region_id", RpRegions.id).nullable()

    /** A logical reference to an order: no foreign key, only `referenceResolver` knows it. */
    val lastOrderId: Column<Int?> = integer("last_order_id").nullable()
    val profile: Column<String?> = json<String>("profile", { it }, { it }).nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object RpOrders : Table("rp_orders") {
    val id: Column<Int> = integer("id")
    val number: Column<String> = varchar("number", 20)
    val customerId: Column<Int?> = reference("customer_id", RpCustomers.id).nullable()
    override val primaryKey = PrimaryKey(id)
}

/** A tree: a physical self-reference. */
internal object RpCategories : Table("rp_categories") {
    val id: Column<Int> = integer("id")
    val name: Column<String> = varchar("name", 50)
    val code: Column<String> = varchar("code", 10)
    val parentId: Column<Int?> = reference("parent_id", id).nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object RpProducts : Table("rp_products") {
    val id: Column<Int> = integer("id")
    val name: Column<String> = varchar("name", 50)
    val categoryId: Column<Int?> = reference("category_id", RpCategories.id).nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object RpLines : Table("rp_lines") {
    val id: Column<Int> = integer("id")
    val orderId: Column<Int> = reference("order_id", RpOrders.id)
    val productId: Column<Int> = reference("product_id", RpProducts.id)
    override val primaryKey = PrimaryKey(id)
}

/**
 * Filter paths through several references against a real database.
 *
 * ```
 * regions      1 North (uk «Північ»), 2 South
 * customers    1 Acme (region 1, last order 11, {"tier":"gold","o'neil":"yes"})
 *              2 Globex (region 2, last order 10, {"tier":"silver"})
 *              3 Initech (no region, no last order, no profile)
 * orders       10 → 1, 11 → 2, 12 → 3, 13 → no customer
 * categories   1 Root ─ 2 Child ─ 3 Grandchild;   4 Other
 * products     1 P1 (category 3), 2 P2 (category 4), 3 P3 (no category)
 * lines        100 (order 10, product 1), 101 (11, 2), 102 (12, 3), 103 (13, 1)
 * ```
 */
abstract class ReferencePathContract {

    protected abstract fun connect()

    /** Whether the database renders JSON extraction; H2 does not. */
    protected open val rendersJson: Boolean = true

    private val allTables =
        arrayOf(RpLines, RpProducts, RpCategories, RpOrders, RpCustomers, RpRegionTranslations, RpRegions)

    /** No depth limit, and the logical reference `lastOrderId` resolved. */
    private val deep = FilterOptions(
        maxReferenceHops = null,
        referenceResolver = { column ->
            if (column == RpCustomers.lastOrderId) ReferenceInfo(RpOrders.id, RpOrders) else null
        },
    )

    @BeforeEach
    fun setUp() {
        connect()
        transaction {
            SchemaUtils.drop(*allTables)
            SchemaUtils.create(*allTables)
            insertData()
        }
    }

    @AfterEach
    fun tearDown() {
        transaction { SchemaUtils.drop(*allTables) }
    }

    private fun insertData() {
        listOf(1 to "North", 2 to "South").forEach { (id, name) ->
            RpRegions.insert {
                it[RpRegions.id] = id
                it[RpRegions.name] = name
            }
        }
        RpRegionTranslations.insert {
            it[regionId] = 1
            it[language] = "uk"
            it[name] = "Північ"
        }
        insertCustomer(1, "Acme", region = 1, lastOrder = 11, profile = """{"tier":"gold","o'neil":"yes"}""")
        insertCustomer(2, "Globex", region = 2, lastOrder = 10, profile = """{"tier":"silver"}""")
        insertCustomer(3, "Initech", region = null, lastOrder = null, profile = null)
        listOf(10 to 1, 11 to 2, 12 to 3, 13 to null).forEach { (id, customer) ->
            RpOrders.insert {
                it[RpOrders.id] = id
                it[number] = "SO-$id"
                it[customerId] = customer
            }
        }
        listOf(Triple(1, "Root", null), Triple(2, "Child", 1), Triple(3, "Grandchild", 2), Triple(4, "Other", null))
            .forEach { (id, name, parent) ->
                RpCategories.insert {
                    it[RpCategories.id] = id
                    it[RpCategories.name] = name
                    it[code] = name.take(1)
                    it[parentId] = parent
                }
            }
        listOf(Triple(1, "P1", 3), Triple(2, "P2", 4), Triple(3, "P3", null)).forEach { (id, name, category) ->
            RpProducts.insert {
                it[RpProducts.id] = id
                it[RpProducts.name] = name
                it[categoryId] = category
            }
        }
        listOf(Triple(100, 10, 1), Triple(101, 11, 2), Triple(102, 12, 3), Triple(103, 13, 1))
            .forEach { (id, order, product) ->
                RpLines.insert {
                    it[RpLines.id] = id
                    it[orderId] = order
                    it[productId] = product
                }
            }
    }

    private fun insertCustomer(id: Int, name: String, region: Int?, lastOrder: Int?, profile: String?) {
        RpCustomers.insert {
            it[RpCustomers.id] = id
            it[RpCustomers.name] = name
            it[regionId] = region
            it[lastOrderId] = lastOrder
            it[RpCustomers.profile] = profile
        }
    }

    private fun eq(field: String, value: String): FilterRequest =
        FilterRequest(FilterLeaf(listOf(FieldFilter(field, FilterOperator.EQ, listOf(value)))))

    private fun filtered(table: Table, filter: FilterRequest, options: FilterOptions): Query =
        table.selectAll().applyFiltersOn(table, filter, options)

    private fun ids(table: Table, id: Column<Int>, filter: FilterRequest, options: FilterOptions = deep): List<Int> =
        transaction { filtered(table, filter, options).map { it[id] }.sorted() }

    private fun renderedSql(table: Table, filter: FilterRequest, options: FilterOptions = deep): String =
        transaction { filtered(table, filter, options).prepareSQL(QueryBuilder(false)) }

    private fun lineIds(filter: FilterRequest, options: FilterOptions = deep): List<Int> =
        ids(RpLines, RpLines.id, filter, options)

    private fun categoryIds(filter: FilterRequest, options: FilterOptions = deep): List<Int> =
        ids(RpCategories, RpCategories.id, filter, options)

    private fun String.existsCount(): Int = Regex("EXISTS").findAll(this).count()

    // ----- Depth -----

    @Test
    fun `two hops`() {
        val filter = eq("orderId.customerId.name", "Globex")
        assertEquals(listOf(101), lineIds(filter))
        assertEquals(2, renderedSql(RpLines, filter).existsCount())
    }

    @Test
    fun `three hops`() {
        val filter = eq("orderId.customerId.regionId.name", "North")
        assertEquals(listOf(100), lineIds(filter))
        assertEquals(3, renderedSql(RpLines, filter).existsCount())
    }

    @Test
    fun `depth default`() {
        val failure = assertThrows(FilterPathException::class.java) {
            lineIds(eq("orderId.customerId.name", "Acme"), FilterOptions())
        }
        assertEquals(FilterPathException.Reason.DEPTH_EXCEEDED, failure.reason)
    }

    // ----- Aliases -----

    @Test
    fun `one-hop self reference is aliased and correct`() {
        val filter = eq("parentId.name", "Root")
        assertEquals(listOf(2), categoryIds(filter))
        val sql = renderedSql(RpCategories, filter)
        assertTrue(sql.contains("ef_1"), sql)
    }

    @Test
    fun `two-hop self reference`() {
        val filter = eq("parentId.parentId.name", "Root")
        assertEquals(listOf(3), categoryIds(filter))
        val sql = renderedSql(RpCategories, filter)
        assertTrue(sql.contains("ef_1") && sql.contains("ef_2"), sql)
    }

    @Test
    fun `hop back to the root table`() {
        assertEquals(listOf(10), ids(RpOrders, RpOrders.id, eq("customerId.lastOrderId.number", "SO-11")))
    }

    @Test
    fun `logical reference mid-path`() {
        assertEquals(listOf(101), lineIds(eq("orderId.customerId.lastOrderId.number", "SO-10")))
    }

    @Test
    fun `a deep path over an aliased root aliases only the inner revisit`() {
        val c = RpCategories.alias("c")
        val columns = mapOf("id" to c[RpCategories.id], "parentId" to c[RpCategories.parentId])
        transaction {
            val query = c.selectAll().applyFilters(columns, eq("parentId.parentId.name", "Root"), deep)
            assertEquals(listOf(3), query.map { it[c[RpCategories.id]] }.sorted())
            val sql = query.prepareSQL(QueryBuilder(false))
            assertTrue(sql.contains("ef_2"), sql)
            assertFalse(sql.contains("ef_1"), sql)
        }
    }

    @Test
    fun `two deep paths in one OR group`() {
        val filter = FilterRequest(
            FilterGroup(
                FilterCombinator.OR,
                listOf(
                    FilterLeaf(listOf(FieldFilter("parentId.name", FilterOperator.EQ, listOf("Root")))),
                    FilterLeaf(listOf(FieldFilter("parentId.parentId.name", FilterOperator.EQ, listOf("Root")))),
                ),
            ),
        )
        assertEquals(listOf(2, 3), categoryIds(filter))
    }

    @Test
    fun `a join source keyed by SQL names walks from the joined column's table`() {
        val join = RpLines innerJoin RpOrders
        val ids = transaction {
            join.selectAll()
                .applyFiltersOn(join, eq("customer_id.name", "Acme"), deep)
                .map { it[RpLines.id] }
                .sorted()
        }
        assertEquals(listOf(100), ids)
    }

    // ----- Projections -----

    @Test
    fun `projection on the last hop`() {
        val translated = deep.copy(
            nestedFieldResolver = { table, field ->
                if (table === RpRegions && field == "name") {
                    NestedFieldProjection(
                        source = RpRegions.leftJoin(
                            otherTable = RpRegionTranslations,
                            additionalConstraint = {
                                (RpRegionTranslations.regionId eq RpRegions.id) and
                                    (RpRegionTranslations.language eq "uk")
                            },
                        ),
                        expression = coalesce(RpRegionTranslations.name, RpRegions.name),
                    )
                } else {
                    null
                }
            },
        )
        assertEquals(listOf(100), lineIds(eq("orderId.customerId.regionId.name", "Північ"), translated))
    }

    @Test
    fun `projection on a middle hop`() {
        val o2 = RpOrders.alias("o2")
        val options = deep.copy(
            nestedFieldResolver = { table, field ->
                if (table === RpOrders && field == "customerId") {
                    NestedFieldProjection(
                        source = o2,
                        expression = o2[RpOrders.customerId],
                        idExpression = o2[RpOrders.id],
                    )
                } else {
                    null
                }
            },
        )
        assertEquals(listOf(100), lineIds(eq("orderId.customerId.name", "Acme"), options))
    }

    @Test
    fun `projection for an aliased occurrence`() {
        val options = deep.copy(
            nestedFieldResolver = { table, field ->
                if (table is Alias<*> && table.delegate === RpCategories && field == "name") {
                    NestedFieldProjection(source = table, expression = table[RpCategories.code])
                } else {
                    null
                }
            },
        )
        assertEquals(listOf(2), categoryIds(eq("parentId.name", "R"), options))
    }

    // ----- JSON -----

    @Test
    fun `hops then json`() {
        val gold = eq("orderId.customerId.profile.tier", "gold")
        if (rendersJson) {
            assertEquals(listOf(100), lineIds(gold))
        } else {
            // The path reaches the JSON column; only rendering the extraction fails, as at the root.
            assertThrows(UnsupportedByDialectException::class.java) { lineIds(gold) }
        }
    }

    @Test
    fun `quote in a json segment is bound`() {
        assumeTrue(rendersJson)
        assertEquals(listOf(1), ids(RpCustomers, RpCustomers.id, eq("profile.o'neil", "yes")))
        assertEquals(listOf(10), ids(RpOrders, RpOrders.id, eq("customerId.profile.o'neil", "yes")))
        transaction {
            listOf(RpCustomers to "profile.o'neil", RpOrders to "customerId.profile.o'neil").forEach { (table, field) ->
                val builder = QueryBuilder(true)
                val sql = filtered(table, eq(field, "yes"), deep).prepareSQL(builder)
                assertFalse(sql.contains("neil"), "the segment must not be spliced into the SQL text: $sql")
                assertTrue(builder.args.any { it.second == "o'neil" }, "the segment must be bound: ${builder.args}")
            }
        }
    }

    @Test
    fun `field-as-written options get the whole path`() {
        val recorded = mutableListOf<String>()
        val recording = deep.copy(jsonPathType = { field -> null.also { recorded += field } })
        filtered(RpLines, eq("orderId.customerId.profile.tier", "gold"), recording)
        assertEquals(listOf("orderId.customerId.profile.tier"), recorded.distinct())

        val acme = eq("orderId.customerId.name", "Acme")
        val name = transaction { QueryBuilder(false).also { RpCustomers.name.toQueryBuilder(it) }.toString() }
        val byPath = renderedSql(RpLines, acme, deep.copy(normalizedStringFields = setOf("orderId.customerId.name")))
        assertFalse(byPath.contains("LOWER("), byPath)
        assertTrue(byPath.contains("$name = "), byPath)
        val byLastSegment = renderedSql(RpLines, acme, deep.copy(normalizedStringFields = setOf("name")))
        assertTrue(byLastSegment.contains("LOWER($name)"), byLastSegment)
    }

    // ----- Operators and NULL links -----

    @Test
    fun `hops then IN_HIERARCHY`() {
        val filter = FilterRequest(
            FilterLeaf(listOf(FieldFilter("productId.categoryId.parentId", FilterOperator.IN_HIERARCHY, listOf("1")))),
        )
        assertEquals(listOf(100, 103), lineIds(filter))
    }

    @Test
    fun `empty link`() {
        val acme = FieldFilter("orderId.customerId.name", FilterOperator.EQ, listOf("Acme"))
        assertEquals(listOf(100), lineIds(FilterRequest(FilterLeaf(listOf(acme)))))
        val notAcme = FilterRequest(FilterGroup(FilterCombinator.NOT, listOf(FilterLeaf(listOf(acme)))))
        assertEquals(listOf(101, 102, 103), lineIds(notAcme))
    }

    // ----- SQL -----

    @Test
    fun `one-hop SQL unchanged`() {
        transaction {
            val library = filtered(RpLines, eq("orderId.number", "SO-10"), FilterOptions())
            val manual = RpLines.selectAll().where {
                exists(
                    RpOrders.selectAll().where {
                        (RpOrders.id eq RpLines.orderId) and (RpOrders.number.lowerCase() eq "so-10")
                    },
                )
            }
            assertEquals(manual.prepareSQL(QueryBuilder(false)), library.prepareSQL(QueryBuilder(false)))
        }
    }
}
