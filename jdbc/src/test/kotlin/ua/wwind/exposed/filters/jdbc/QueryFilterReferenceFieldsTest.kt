@file:OptIn(ExperimentalUuidApi::class)

package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterCombinator
import ua.wwind.exposed.filters.core.FilterGroup
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterOperator
import ua.wwind.exposed.filters.core.FilterRequest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private object RefLotsTable : Table("ref_lots") {
    val id: Column<Uuid> = uuid("id")
    val name: Column<String> = varchar("name", 100)
    override val primaryKey = PrimaryKey(id)
}

private object RefBarcodesTable : Table("ref_barcodes") {
    val id: Column<Int> = integer("id").autoIncrement()
    val barcode: Column<String> = varchar("barcode", 50)

    /** Physical foreign key: Exposed exposes a `referee`, so nested paths resolve out of the box. */
    val lotRefId: Column<Uuid?> = optReference("lot_ref_id", RefLotsTable.id)

    /** Logical foreign key: a plain column a module boundary forbids declaring as a real reference. */
    val lotId: Column<Uuid?> = uuid("lot_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

private val LOT_A: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000a1")
private val LOT_B: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000b2")

class QueryFilterReferenceFieldsTest {

    @BeforeEach
    fun setUp() {
        Database.connect(
            url = "jdbc:h2:mem:test_ref_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(RefLotsTable, RefBarcodesTable)
            RefLotsTable.insert {
                it[id] = LOT_A
                it[name] = "Alpha"
            }
            RefLotsTable.insert {
                it[id] = LOT_B
                it[name] = "Beta"
            }
            insertBarcode("BC-ALPHA", LOT_A)
            insertBarcode("BC-BETA", LOT_B)
            insertBarcode("BC-NONE", null)
        }
    }

    private fun insertBarcode(
        code: String,
        lot: Uuid?,
    ) {
        RefBarcodesTable.insert {
            it[barcode] = code
            it[lotRefId] = lot
            it[lotId] = lot
        }
    }

    @AfterEach
    fun cleanUp() {
        transaction { SchemaUtils.drop(RefBarcodesTable, RefLotsTable) }
    }

    private fun leaf(
        field: String,
        value: String,
    ) = FilterRequest(FilterLeaf(listOf(FieldFilter(field, FilterOperator.EQ, listOf(value)))))

    private val manualLotReference = FilterOptions(
        referenceResolver = { column ->
            if (column === RefBarcodesTable.lotId) ReferenceInfo(RefLotsTable.id, RefLotsTable) else null
        },
    )

    @Test
    fun `a physical reference resolves a nested property without any resolver`() {
        transaction {
            val rows = RefBarcodesTable
                .selectAll()
                .applyFiltersOn(RefBarcodesTable, leaf("lotRefId.name", "Alpha"))
                .map { it[RefBarcodesTable.barcode] }
            assertEquals(listOf("BC-ALPHA"), rows)
        }
    }

    @Test
    fun `a plain column rejects a nested property when no resolver is configured`() {
        transaction {
            val failure = assertThrows(IllegalStateException::class.java) {
                RefBarcodesTable
                    .selectAll()
                    .applyFiltersOn(RefBarcodesTable, leaf("lotId.name", "Alpha"))
                    .toList()
            }
            assertTrue(
                failure.message.orEmpty().contains("is not a reference"),
                "expected the unresolved-reference message, got: ${failure.message}",
            )
        }
    }

    @Test
    fun `a resolver makes a plain column filterable by a nested property`() {
        transaction {
            val rows = RefBarcodesTable
                .selectAll()
                .applyFiltersOn(RefBarcodesTable, leaf("lotId.name", "Alpha"), options = manualLotReference)
                .map { it[RefBarcodesTable.barcode] }
            assertEquals(listOf("BC-ALPHA"), rows)
        }
    }

    @Test
    fun `a resolved nested property keeps OR semantics inside a group`() {
        val filter = FilterRequest(
            FilterGroup(
                FilterCombinator.OR,
                listOf(
                    FilterLeaf(listOf(FieldFilter("lotId.name", FilterOperator.EQ, listOf("Alpha")))),
                    FilterLeaf(listOf(FieldFilter("barcode", FilterOperator.EQ, listOf("BC-NONE")))),
                ),
            ),
        )
        transaction {
            val rows = RefBarcodesTable
                .selectAll()
                .applyFiltersOn(RefBarcodesTable, filter, options = manualLotReference)
                .map { it[RefBarcodesTable.barcode] }
                .sorted()
            assertEquals(listOf("BC-ALPHA", "BC-NONE"), rows)
        }
    }

    @Test
    fun `the resolver is not consulted for a column Exposed can resolve itself`() {
        var consulted = false
        val options = FilterOptions(
            referenceResolver = { _ ->
                consulted = true
                null
            },
        )
        transaction {
            RefBarcodesTable
                .selectAll()
                .applyFiltersOn(RefBarcodesTable, leaf("lotRefId.name", "Alpha"), options = options)
                .toList()
        }
        assertFalse(consulted, "a physical reference must resolve without falling back to the resolver")
    }

    @Test
    fun `a resolver returning null leaves the unresolved-reference error in place`() {
        val options = FilterOptions(referenceResolver = { null })
        transaction {
            val failure = assertThrows(IllegalStateException::class.java) {
                RefBarcodesTable
                    .selectAll()
                    .applyFiltersOn(RefBarcodesTable, leaf("lotId.name", "Alpha"), options = options)
                    .toList()
            }
            assertTrue(failure.message.orEmpty().contains("is not a reference"), failure.message)
        }
    }
}
