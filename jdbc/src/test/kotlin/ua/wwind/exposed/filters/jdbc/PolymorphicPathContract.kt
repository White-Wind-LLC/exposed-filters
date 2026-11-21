package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterCombinator
import ua.wwind.exposed.filters.core.FilterGroup
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterOperator
import ua.wwind.exposed.filters.core.FilterRequest

internal enum class DocKind { RECEIPT, SHIPMENT }

internal object RpReceipts : Table("rp_receipts") {
    val id: Column<Int> = integer("id")
    val number: Column<String> = varchar("number", 20)
    val customerId: Column<Int> = reference("customer_id", RpCustomers.id)
    override val primaryKey = PrimaryKey(id)
}

internal object RpShipments : Table("rp_shipments") {
    val id: Column<Int> = integer("id")
    val number: Column<String> = varchar("number", 20)
    override val primaryKey = PrimaryKey(id)
}

/**
 * A container holding a document of one of several kinds, stored twice: the kind as an enum beside
 * `documentId`, and as free text beside `documentRef`. Neither id has a foreign key: the kind decides
 * the table it points into.
 */
internal object RpContainers : Table("rp_containers") {
    val id: Column<Int> = integer("id")
    val code: Column<String> = varchar("code", 20)
    val documentType: Column<DocKind?> = enumerationByName("document_type", 20, DocKind::class).nullable()
    val documentId: Column<Int?> = integer("document_id").nullable()
    val documentKind: Column<String?> = varchar("document_kind", 20).nullable()
    val documentRef: Column<Int?> = integer("document_ref").nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object RpMoves : Table("rp_moves") {
    val id: Column<Int> = integer("id")
    val containerId: Column<Int> = reference("container_id", RpContainers.id)
    override val primaryKey = PrimaryKey(id)
}

/** The targets of the two document references of [RpContainers]. */
internal val rpDocumentReferences: (Column<*>) -> PolymorphicReferenceInfo? = { column ->
    when (column) {
        RpContainers.documentId -> PolymorphicReferenceInfo(
            discriminator = RpContainers.documentType,
            targets = mapOf(
                "RECEIPT" to ReferenceInfo(RpReceipts.id, RpReceipts),
                "SHIPMENT" to ReferenceInfo(RpShipments.id, RpShipments),
            ),
        )

        RpContainers.documentRef -> PolymorphicReferenceInfo(
            discriminator = RpContainers.documentKind,
            targets = mapOf("RECEIPT" to ReferenceInfo(RpReceipts.id, RpReceipts)),
        )

        else -> null
    }
}

/**
 * Filter paths through a reference with several targets against a real database.
 *
 * ```
 * customers    1 Acme
 * receipts     1 R-1 (customer 1)
 * shipments    1 S-1
 * containers   1 (RECEIPT, document 1, kind "receipt", ref 1)
 *              2 (SHIPMENT, document 1, kind "RECEIPT", ref 1)
 *              3 (nothing)
 * moves        50 → container 1, 51 → container 2
 * ```
 */
abstract class PolymorphicPathContract {

    protected abstract fun connect()

    private val allTables = arrayOf(RpMoves, RpContainers, RpShipments, RpReceipts, RpCustomers, RpRegions)

    private val polymorphic = FilterOptions(polymorphicReferenceResolver = rpDocumentReferences)

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
        RpCustomers.insert {
            it[id] = 1
            it[name] = "Acme"
        }
        RpReceipts.insert {
            it[id] = 1
            it[number] = "R-1"
            it[customerId] = 1
        }
        RpShipments.insert {
            it[id] = 1
            it[number] = "S-1"
        }
        insertContainer(1, DocKind.RECEIPT, document = 1, kind = "receipt", ref = 1)
        insertContainer(2, DocKind.SHIPMENT, document = 1, kind = "RECEIPT", ref = 1)
        insertContainer(3, type = null, document = null, kind = null, ref = null)
        listOf(50 to 1, 51 to 2).forEach { (id, container) ->
            RpMoves.insert {
                it[RpMoves.id] = id
                it[containerId] = container
            }
        }
    }

    private fun insertContainer(id: Int, type: DocKind?, document: Int?, kind: String?, ref: Int?) {
        RpContainers.insert {
            it[RpContainers.id] = id
            it[code] = "C-$id"
            it[documentType] = type
            it[documentId] = document
            it[documentKind] = kind
            it[documentRef] = ref
        }
    }

    private fun eq(field: String, value: String): FilterRequest =
        FilterRequest(FilterLeaf(listOf(FieldFilter(field, FilterOperator.EQ, listOf(value)))))

    private fun ids(table: Table, id: Column<Int>, filter: FilterRequest, options: FilterOptions): List<Int> =
        transaction { table.selectAll().applyFiltersOn(table, filter, options).map { it[id] }.sorted() }

    private fun containerIds(filter: FilterRequest, options: FilterOptions = polymorphic): List<Int> =
        ids(RpContainers, RpContainers.id, filter, options)

    @Test
    fun `the named target is read and other kinds are excluded`() {
        // Container 2 points at id 1 too, but into shipments.
        assertEquals(listOf(1), containerIds(eq("documentId[RECEIPT].number", "R-1")))
    }

    @Test
    fun `another target reads its own table`() {
        assertEquals(listOf(2), containerIds(eq("documentId[SHIPMENT].number", "S-1")))
    }

    @Test
    fun `the path continues past the target`() {
        val filter = eq("documentId[RECEIPT].customerId.name", "Acme")
        assertEquals(listOf(1), containerIds(filter, polymorphic.copy(maxReferenceHops = 2)))
    }

    @Test
    fun `a target in the middle of a path`() {
        val filter = eq("containerId.documentId[RECEIPT].number", "R-1")
        assertEquals(listOf(50), ids(RpMoves, RpMoves.id, filter, polymorphic.copy(maxReferenceHops = 2)))
    }

    @Test
    fun `under NOT`() {
        val receipt = FilterLeaf(listOf(FieldFilter("documentId[RECEIPT].number", FilterOperator.EQ, listOf("R-1"))))
        assertEquals(listOf(2, 3), containerIds(FilterRequest(FilterGroup(FilterCombinator.NOT, listOf(receipt)))))
    }

    @Test
    fun `a varchar discriminator matches the target case-sensitively`() {
        // Container 1 stores "receipt": case-insensitive strings, the default, do not apply to a target.
        assertEquals(listOf(2), containerIds(eq("documentRef[RECEIPT].number", "R-1")))
    }

    @Test
    fun `an aliased root reads the discriminator through its alias`() {
        val c = RpContainers.alias("c")
        val columns = mapOf("id" to c[RpContainers.id], "documentId" to c[RpContainers.documentId])
        val ids = transaction {
            c.selectAll()
                .applyFilters(columns, eq("documentId[RECEIPT].number", "R-1"), polymorphic)
                .map { it[c[RpContainers.id]] }
        }
        assertEquals(listOf(1), ids)
    }

    @Test
    fun `the discriminator sits outside the subquery`() {
        transaction {
            val library = RpContainers.selectAll()
                .applyFiltersOn(RpContainers, eq("documentId[RECEIPT].number", "R-1"), polymorphic)
            val manual = RpContainers.selectAll().where {
                (RpContainers.documentType eq DocKind.RECEIPT) and exists(
                    RpReceipts.selectAll().where {
                        (RpReceipts.id eq RpContainers.documentId) and (RpReceipts.number.lowerCase() eq "r-1")
                    },
                )
            }
            assertEquals(manual.prepareSQL(QueryBuilder(false)), library.prepareSQL(QueryBuilder(false)))
        }
    }
}
