package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
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

/** A category tree: a physical self-reference, so the hierarchy is auto-detected. */
internal object HCategories : Table("h_categories") {
    val id: Column<Int> = integer("id").autoIncrement()
    val name: Column<String> = varchar("name", 50)
    val parentId: Column<Int?> = reference("parent_id", id).nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object HProducts : Table("h_products") {
    val id: Column<Int> = integer("id")
    val name: Column<String> = varchar("name", 50)
    val categoryId: Column<Int?> = reference("category_id", HCategories.id).nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object HOrders : Table("h_orders") {
    val id: Column<Int> = integer("id")
    val productId: Column<Int> = reference("product_id", HProducts.id)
    override val primaryKey = PrimaryKey(id)
}

/** Two self-references: auto-detection cannot pick one. */
internal object HNodes : Table("h_nodes") {
    val id: Column<Int> = integer("id")
    val name: Column<String> = varchar("name", 50)
    val parentId: Column<Int?> = reference("parent_id", id).nullable()
    val mergedIntoId: Column<Int?> = reference("merged_into_id", id).nullable()
    override val primaryKey = PrimaryKey(id)
}

/** A logical-only tree: `parentId` carries no foreign key, so only a resolver can describe it. */
internal object HFolders : Table("h_folders") {
    val id: Column<Int> = integer("id")
    val name: Column<String> = varchar("name", 50)
    val parentId: Column<Int?> = integer("parent_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** A tree keyed by a string code. */
internal object HCodes : Table("h_codes") {
    val code: Column<String> = varchar("code", 20)
    val parentCode: Column<String?> = reference("parent_code", code).nullable()
    override val primaryKey = PrimaryKey(code)
}

/** Items keyed into [HCodes]; their own codes are stored lowercase, so `code` may be a normalized field. */
internal object HCodeItems : Table("h_code_items") {
    val id: Column<Int> = integer("id")
    val code: Column<String> = reference("code", HCodes.code)
    override val primaryKey = PrimaryKey(id)
}

/** A logical-only foreign key into [HCategories]: only `referenceResolver` knows where `categoryId` points. */
internal object HListings : Table("h_listings") {
    val id: Column<Int> = integer("id")
    val name: Column<String> = varchar("name", 50)
    val categoryId: Column<Int?> = integer("category_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** A DAO-style tree with `EntityID` ids. */
internal object ECategories : IntIdTable("h_e_categories") {
    val name: Column<String> = varchar("name", 50)
    val parentId: Column<EntityID<Int>?> = optReference("parent_id", ECategories)
}

/** Never created: only used to check errors raised while the predicate is built. */
internal object HTagged : Table("h_tagged") {
    val id: Column<Int> = integer("id")
    val tags: Column<List<Int>> = array<Int>("tags")
    val payload: Column<String> = json("payload", { it }, { it })
}

/**
 * `IN_HIERARCHY` against a real database. Category tree:
 *
 * ```
 * 1 Electronics            6 Books
 * ├─ 2 Computers           └─ 7 Fiction
 * │  └─ 3 Laptops
 * │     └─ 4 Gaming
 * └─ 5 Phones
 * ```
 */
abstract class QueryFilterHierarchyContract {

    protected abstract fun connect()

    /**
     * Whether a recursive `UNION` stops on cyclic data. PostgreSQL deduplicates against the rows already
     * produced and stops; H2 2.4 does not and runs until the query times out.
     */
    protected open val stopsOnCycles: Boolean = true

    private val allTables = arrayOf(HOrders, HProducts, HListings, HCategories, HNodes, HFolders, HCodeItems, HCodes, ECategories)

    @BeforeEach
    fun setUp() {
        connect()
        transaction {
            SchemaUtils.drop(*allTables)
            SchemaUtils.create(*allTables)
            listOf(
                Triple(1, "Electronics", null), Triple(2, "Computers", 1), Triple(3, "Laptops", 2),
                Triple(4, "Gaming", 3), Triple(5, "Phones", 1), Triple(6, "Books", null), Triple(7, "Fiction", 6),
            ).forEach { (id, name, parent) ->
                HCategories.insert {
                    it[HCategories.id] = id
                    it[HCategories.name] = name
                    it[HCategories.parentId] = parent
                }
            }
            listOf(
                Triple(10, "Gaming X", 4), Triple(11, "Ultrabook", 3), Triple(12, "Desktop", 2),
                Triple(13, "Smartphone", 5), Triple(14, "Novel", 7), Triple(15, "Gift card", null),
            ).forEach { (id, name, category) ->
                HProducts.insert {
                    it[HProducts.id] = id
                    it[HProducts.name] = name
                    it[HProducts.categoryId] = category
                }
            }
            listOf(100 to 10, 101 to 14, 102 to 13).forEach { (id, product) ->
                HOrders.insert {
                    it[HOrders.id] = id
                    it[HOrders.productId] = product
                }
            }
            // 1 ← 2 by parent; 3 is merged into 1 but has no parent.
            listOf(Triple(1, null, null), Triple(2, 1, null), Triple(3, null, 1)).forEach { (id, parent, merged) ->
                HNodes.insert {
                    it[HNodes.id] = id
                    it[HNodes.name] = "node$id"
                    it[HNodes.parentId] = parent
                    it[HNodes.mergedIntoId] = merged
                }
            }
            // 1 → 2 → 3 → 1 is a cycle; 4 ← 5 is a plain branch.
            listOf(Triple(1, "a", 3), Triple(2, "b", 1), Triple(3, "c", 2), Triple(4, "d", null), Triple(5, "e", 4))
                .forEach { (id, name, parent) ->
                    HFolders.insert {
                        it[HFolders.id] = id
                        it[HFolders.name] = name
                        it[HFolders.parentId] = parent
                    }
                }
            // "ROOT" is stored upper case; items only point at the lowercase "leaf" below it.
            listOf("elec" to null, "comp" to "elec", "book" to null, "ROOT" to null, "leaf" to "ROOT")
                .forEach { (code, parent) ->
                    HCodes.insert {
                        it[HCodes.code] = code
                        it[HCodes.parentCode] = parent
                    }
                }
            HCodeItems.insert {
                it[HCodeItems.id] = 1
                it[HCodeItems.code] = "leaf"
            }
            listOf(Triple(20, "Laptop ad", 3), Triple(21, "Novel ad", 7)).forEach { (id, name, category) ->
                HListings.insert {
                    it[HListings.id] = id
                    it[HListings.name] = name
                    it[HListings.categoryId] = category
                }
            }
            val root = ECategories.insertAndGetId { it[name] = "root" }
            ECategories.insert {
                it[name] = "child"
                it[parentId] = root
            }
            ECategories.insert { it[name] = "other" }
        }
    }

    @AfterEach
    fun tearDown() {
        transaction { SchemaUtils.drop(*allTables) }
    }

    protected fun hier(field: String, vararg ids: Any): FilterRequest =
        FilterRequest(FilterLeaf(listOf(FieldFilter(field, FilterOperator.IN_HIERARCHY, ids.map(Any::toString)))))

    protected fun productNames(filter: FilterRequest, options: FilterOptions = FilterOptions()): Set<String> =
        transaction {
            HProducts.selectAll().applyFiltersOn(HProducts, filter, options).map { it[HProducts.name] }.toSet()
        }

    protected fun categoryNames(filter: FilterRequest, options: FilterOptions = FilterOptions()): Set<String> =
        transaction {
            HCategories.selectAll().applyFiltersOn(HCategories, filter, options).map { it[HCategories.name] }.toSet()
        }

    // ----- Matching -----

    @Test
    fun `a foreign key into the tree matches the node and its descendants`() {
        assertEquals(setOf("Gaming X", "Ultrabook", "Desktop"), productNames(hier("categoryId", 2)))
    }

    @Test
    fun `the tree's own id matches the node and its descendants`() {
        assertEquals(setOf("Computers", "Laptops", "Gaming"), categoryNames(hier("id", 2)))
    }

    @Test
    fun `the parent column matches strict descendants`() {
        assertEquals(setOf("Laptops", "Gaming"), categoryNames(hier("parentId", 2)))
    }

    @Test
    fun `several roots are combined`() {
        assertEquals(setOf("Smartphone", "Novel"), productNames(hier("categoryId", 5, 6)))
    }

    @Test
    fun `a leaf matches only itself`() {
        assertEquals(setOf("Gaming X"), productNames(hier("categoryId", 4)))
    }

    @Test
    fun `an id that does not exist matches nothing`() {
        assertEquals(emptySet<String>(), productNames(hier("categoryId", 999)))
    }

    @Test
    fun `empty values match nothing`() {
        assertEquals(emptySet<String>(), productNames(hier("categoryId")))
    }

    @Test
    fun `overlapping roots do not duplicate rows`() {
        val rows = transaction {
            HProducts.selectAll().applyFiltersOn(HProducts, hier("categoryId", 1, 3)).map { it[HProducts.name] }
        }
        assertEquals(listOf("Desktop", "Gaming X", "Smartphone", "Ultrabook"), rows.sorted())
    }

    // ----- Composition -----

    @Test
    fun `works inside an OR group`() {
        val filter = FilterRequest(
            FilterGroup(
                FilterCombinator.OR,
                listOf(
                    FilterLeaf(listOf(FieldFilter("categoryId", FilterOperator.IN_HIERARCHY, listOf("5")))),
                    FilterLeaf(listOf(FieldFilter("name", FilterOperator.EQ, listOf("Novel")))),
                ),
            ),
        )
        assertEquals(setOf("Smartphone", "Novel"), productNames(filter))
    }

    @Test
    fun `works inside a NOT group and drops rows with a null value`() {
        val filter = FilterRequest(
            FilterGroup(
                FilterCombinator.NOT,
                listOf(FilterLeaf(listOf(FieldFilter("categoryId", FilterOperator.IN_HIERARCHY, listOf("1"))))),
            ),
        )
        // "Gift card" has no category: NOT (NULL IN …) is NULL, so it drops out, as with NOT_IN.
        assertEquals(setOf("Novel"), productNames(filter))
    }

    @Test
    fun `two hierarchy predicates in one query apply independently`() {
        val both = FilterRequest(
            FilterLeaf(
                listOf(
                    FieldFilter("id", FilterOperator.IN_HIERARCHY, listOf("1")),
                    FieldFilter("parentId", FilterOperator.IN_HIERARCHY, listOf("2")),
                ),
            ),
        )
        // id in subtree(1) = {1,2,3,4,5}; strict descendants of 2 = {3,4}. Each subquery declares its own
        // `ef_tree`, so the intersection is {3,4}.
        assertEquals(setOf("Laptops", "Gaming"), categoryNames(both))
    }

    @Test
    fun `works on a nested reference path`() {
        val orders = transaction {
            HOrders.selectAll()
                .applyFiltersOn(HOrders, hier("productId.categoryId", 1))
                .map { it[HOrders.id] }
                .toSet()
        }
        assertEquals(setOf(100, 102), orders)
    }

    @Test
    fun `works on a table alias column`() {
        val names = transaction {
            val alias = HProducts.alias("hp")
            alias.selectAll()
                .applyFiltersOn(alias, hier("category_id", 3))
                .map { it[alias[HProducts.name]] }
                .toSet()
        }
        assertEquals(setOf("Gaming X", "Ultrabook"), names)
    }

    // ----- Id types -----

    @Test
    fun `scalar operators work on an auto-increment id`() {
        // `autoIncrement()` wraps the column type in `AutoIncColumnType`; values must still be coerced.
        fun names(op: FilterOperator, vararg values: String) =
            categoryNames(FilterRequest(FilterLeaf(listOf(FieldFilter("id", op, values.toList())))))
        assertEquals(setOf("Computers"), names(FilterOperator.EQ, "2"))
        assertEquals(setOf("Computers", "Phones"), names(FilterOperator.IN, "2", "5"))
        assertEquals(setOf("Books", "Fiction"), names(FilterOperator.GT, "5"))
        assertEquals(setOf("Laptops", "Gaming"), names(FilterOperator.BETWEEN, "3", "4"))
    }

    @Test
    fun `plain IN works on an auto-increment EntityID id`() {
        // The hierarchy anchor is an `IN` on the tree's id, so `IN` itself must handle `IntIdTable.id`.
        val names = transaction {
            val rootId = ECategories.selectAll().single { it[ECategories.name] == "root" }[ECategories.id].value
            val filter = FilterRequest(FilterLeaf(listOf(FieldFilter("id", FilterOperator.IN, listOf("$rootId")))))
            ECategories.selectAll().applyFiltersOn(ECategories, filter).map { it[ECategories.name] }.toSet()
        }
        assertEquals(setOf("root"), names)
    }

    @Test
    fun `works with EntityID ids`() {
        val names = transaction {
            val rootId = ECategories.selectAll().single { it[ECategories.name] == "root" }[ECategories.id].value
            ECategories.selectAll()
                .applyFiltersOn(ECategories, hier("id", rootId))
                .map { it[ECategories.name] }
                .toSet()
        }
        assertEquals(setOf("root", "child"), names)
    }

    @Test
    fun `string ids match case-insensitively like IN`() {
        val codes = transaction {
            HCodes.selectAll().applyFiltersOn(HCodes, hier("code", "ELEC")).map { it[HCodes.code] }.toSet()
        }
        assertEquals(setOf("elec", "comp"), codes)
    }

    @Test
    fun `a normalized field does not normalize the tree's id column`() {
        // The anchor compares the tree's codes, not the field's: "ROOT" must still match "root".
        val options = FilterOptions(normalizedStringFields = setOf("code"))
        val ids = transaction {
            HCodeItems.selectAll().applyFiltersOn(HCodeItems, hier("code", "root"), options).map { it[HCodeItems.id] }
        }
        assertEquals(listOf(1), ids)
    }

    @Test
    fun `a value of the wrong type fails like IN`() {
        assertThrows(NumberFormatException::class.java) { productNames(hier("categoryId", "abc")) }
    }

    // ----- SQL shape -----

    @Test
    fun `renders a recursive CTE with bound anchor values`() {
        val sql = transaction {
            HProducts.selectAll().applyFiltersOn(HProducts, hier("categoryId", 2, 5)).prepareSQL(this, prepared = true)
        }
        assertTrue(sql.contains("IN (WITH RECURSIVE"), sql)
        assertTrue(sql.contains(" UNION "), sql)
        assertFalse(sql.contains(" UNION ALL "), sql)
        assertFalse(sql.contains("2, 5") || sql.contains("(2,5)"), "anchor values must be bound: $sql")
        assertTrue(sql.contains("?"), sql)
    }

    // ----- Resolver -----

    private val folderTree = FilterOptions(
        hierarchyResolver = { column ->
            if (column == HFolders.id) HierarchyInfo(HFolders.id, HFolders.parentId) else null
        },
    )

    private fun folderNames(filter: FilterRequest, options: FilterOptions): Set<String> = transaction {
        HFolders.selectAll().applyFiltersOn(HFolders, filter, options).map { it[HFolders.name] }.toSet()
    }

    @Test
    fun `the resolver describes a logical-only tree`() {
        assertEquals(setOf("d", "e"), folderNames(hier("id", 4), folderTree))
    }

    @Test
    fun `cyclic data terminates`() {
        assumeTrue(stopsOnCycles, "this database does not stop a recursive UNION on cycles")
        assertEquals(setOf("a", "b", "c"), folderNames(hier("id", 1), folderTree))
    }

    @Test
    fun `the resolver overrides auto-detection`() {
        val byMerge = FilterOptions(
            hierarchyResolver = { column ->
                if (column == HNodes.id) HierarchyInfo(HNodes.id, HNodes.mergedIntoId) else null
            },
        )
        val names = transaction {
            HNodes.selectAll().applyFiltersOn(HNodes, hier("id", 1), byMerge).map { it[HNodes.name] }.toSet()
        }
        assertEquals(setOf("node1", "node3"), names)
    }

    @Test
    fun `the resolver returning null falls back to auto-detection`() {
        val nothing = FilterOptions(hierarchyResolver = { null })
        assertEquals(setOf("Gaming X", "Ultrabook", "Desktop"), productNames(hier("categoryId", 2), nothing))
    }

    @Test
    fun `the resolver receives the original column behind an alias`() {
        val seen = mutableListOf<Column<*>>()
        val recording = FilterOptions(hierarchyResolver = { column -> seen += column; null })
        transaction {
            val alias = HProducts.alias("hp")
            alias.selectAll().applyFiltersOn(alias, hier("category_id", 2), recording).toList()
        }
        assertEquals(listOf<Column<*>>(HProducts.categoryId), seen)
    }

    @Test
    fun `auto-detection follows a logical foreign key from referenceResolver`() {
        val logical = FilterOptions(
            referenceResolver = { column ->
                if (column == HListings.categoryId) ReferenceInfo(HCategories.id, HCategories) else null
            },
        )
        val names = transaction {
            HListings.selectAll()
                .applyFiltersOn(HListings, hier("categoryId", 1), logical)
                .map { it[HListings.name] }
                .toSet()
        }
        assertEquals(setOf("Laptop ad"), names)
    }

    @Test
    fun `a parent column on another table is rejected`() {
        val wrong = FilterOptions(
            hierarchyResolver = { HierarchyInfo(HCategories.id, HProducts.categoryId) },
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            productNames(hier("categoryId", 1), wrong)
        }
        assertTrue(error.message!!.contains("is not on the tree's table h_categories"), error.message)
    }

    // ----- Errors -----

    @Test
    fun `a column outside any tree fails with a hint`() {
        val error = assertThrows(IllegalStateException::class.java) { productNames(hier("name", "x")) }
        assertTrue(error.message!!.contains("IN_HIERARCHY on field 'name'"), error.message)
        assertTrue(error.message!!.contains("FilterOptions.hierarchyResolver"), error.message)
    }

    @Test
    fun `several self-references are ambiguous`() {
        val error = assertThrows(IllegalStateException::class.java) {
            transaction { HNodes.selectAll().applyFiltersOn(HNodes, hier("id", 1)).toList() }
        }
        assertTrue(error.message!!.contains("several self-references (parent_id, merged_into_id)"), error.message)
    }

    @Test
    fun `a logical-only parent is not auto-detected`() {
        val error = assertThrows(IllegalStateException::class.java) {
            transaction { HFolders.selectAll().applyFiltersOn(HFolders, hier("id", 4)).toList() }
        }
        assertTrue(error.message!!.contains("no column of h_folders references h_folders.id"), error.message)
    }

    @Test
    fun `a computed expression is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            transaction {
                HProducts.selectAll().applyFilters(mapOf("next" to (HProducts.id + 1)), hier("next", 1)).toList()
            }
        }
        assertTrue(error.message!!.contains("IN_HIERARCHY requires a table column: 'next'"), error.message)
    }

    @Test
    fun `a JSON path is rejected`() {
        val error = assertThrows(IllegalStateException::class.java) {
            transaction { HTagged.selectAll().applyFiltersOn(HTagged, hier("payload.a", 1)) }
        }
        assertTrue(error.message!!.contains("IN_HIERARCHY is not supported for JSON field"), error.message)
    }

    @Test
    fun `an array column is rejected`() {
        val error = assertThrows(IllegalStateException::class.java) {
            transaction { HTagged.selectAll().applyFiltersOn(HTagged, hier("tags", 1)) }
        }
        assertTrue(error.message!!.contains("IN_HIERARCHY is not supported for array field 'tags'"), error.message)
        assertFalse(error.message!!.contains("Use IN or NOT_IN"), error.message)
    }

    @Test
    fun `empty values are rejected on an array column`() {
        val error = assertThrows(IllegalStateException::class.java) {
            transaction { HTagged.selectAll().applyFiltersOn(HTagged, hier("tags")) }
        }
        assertTrue(error.message!!.contains("IN_HIERARCHY is not supported for array field 'tags'"), error.message)
    }

    @Test
    fun `empty values are rejected on a JSON path`() {
        val error = assertThrows(IllegalStateException::class.java) {
            transaction { HTagged.selectAll().applyFiltersOn(HTagged, hier("payload.a")) }
        }
        assertTrue(error.message!!.contains("IN_HIERARCHY is not supported for JSON field"), error.message)
    }

    @Test
    fun `empty values are rejected on a computed expression`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            transaction {
                HProducts.selectAll().applyFilters(mapOf("next" to (HProducts.id + 1)), hier("next")).toList()
            }
        }
        assertTrue(error.message!!.contains("IN_HIERARCHY requires a table column: 'next'"), error.message)
    }

    @Test
    fun `empty values are rejected on a column outside any tree`() {
        val error = assertThrows(IllegalStateException::class.java) { productNames(hier("name")) }
        assertTrue(error.message!!.contains("IN_HIERARCHY on field 'name'"), error.message)
    }
}
