package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.core.upperCase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.union
import org.jetbrains.exposed.v1.json.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ua.wwind.exposed.filters.jdbc.FilterPathException.Reason

private object FpRegions : Table("fp_regions") {
    val id: Column<Int> = integer("id")
    val name: Column<String> = varchar("name", 50)
    override val primaryKey = PrimaryKey(id)
}

/** A translation beside [FpRegions], the kind of source a projection reads a localized name from. */
private object FpRegionTranslations : Table("fp_region_translations") {
    val regionId: Column<Int> = reference("region_id", FpRegions.id)
    val language: Column<String> = varchar("language", 2)
    val name: Column<String> = varchar("name", 50)
}

private object FpCustomers : Table("fp_customers") {
    val id: Column<Int> = integer("id")
    val name: Column<String> = varchar("name", 50)
    val regionId: Column<Int?> = reference("region_id", FpRegions.id).nullable()

    /** A logical reference to an order: no foreign key, only the resolver knows it. */
    val lastOrderId: Column<Int?> = integer("last_order_id").nullable()
    val profile: Column<String?> = json("profile", { it }, { it }).nullable()
    override val primaryKey = PrimaryKey(id)
}

private object FpOrders : Table("fp_orders") {
    val id: Column<Int> = integer("id")
    val number: Column<String> = varchar("number", 20)
    val customerId: Column<Int?> = reference("customer_id", FpCustomers.id).nullable()
    override val primaryKey = PrimaryKey(id)
}

private object FpLines : Table("fp_lines") {
    val id: Column<Int> = integer("id")
    val orderId: Column<Int> = reference("order_id", FpOrders.id)
    override val primaryKey = PrimaryKey(id)
}

private object FpCategories : Table("fp_categories") {
    val id: Column<Int> = integer("id")
    val name: Column<String> = varchar("name", 50)
    val code: Column<String> = varchar("code", 10)
    val parentId: Column<Int?> = reference("parent_id", id).nullable()
    override val primaryKey = PrimaryKey(id)
}

/** A source of a kind the library does not know: it exposes nothing but its columns. */
private class ColumnsOnlySource(override val columns: List<Column<*>>) : ColumnSet() {
    override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = error("never rendered")

    override fun join(
        otherTable: ColumnSet,
        joinType: JoinType,
        onColumn: Expression<*>?,
        otherColumn: Expression<*>?,
        lateral: Boolean,
        additionalConstraint: (() -> Op<Boolean>)?,
    ): Join = error("never joined")

    override fun innerJoin(otherTable: ColumnSet): Join = error("never joined")

    override fun leftJoin(otherTable: ColumnSet): Join = error("never joined")

    override fun rightJoin(otherTable: ColumnSet): Join = error("never joined")

    override fun fullJoin(otherTable: ColumnSet): Join = error("never joined")

    override fun crossJoin(otherTable: ColumnSet): Join = error("never joined")
}

/** Resolution of a filter path into hops, occurrences and the compared expression; it builds no SQL. */
class FilterPathResolutionTest {

    private val deep = FilterOptions(
        maxReferenceHops = null,
        referenceResolver = { if (it == FpCustomers.lastOrderId) ReferenceInfo(FpOrders.id, FpOrders) else null },
    )

    /** [deep], with a projection for [field] of the plain [table]. */
    private fun projecting(table: Table, field: String, projection: () -> NestedFieldProjection): FilterOptions =
        deep.copy(
            nestedFieldResolver = { target, name -> if (target === table && name == field) projection() else null },
        )

    private fun regionsWithTranslations(): Join =
        FpRegions.join(FpRegionTranslations, JoinType.INNER, FpRegions.id, FpRegionTranslations.regionId)

    private fun assertPathFailure(
        reason: Reason,
        segmentIndex: Int,
        segment: String,
        resolve: () -> Unit,
    ): FilterPathException {
        val failure = assertThrows(FilterPathException::class.java) { resolve() }
        assertEquals(reason, failure.reason)
        assertEquals(segmentIndex, failure.segmentIndex)
        assertEquals(segment, failure.segment)
        return failure
    }

    @Test
    fun `three hops through plain references read each table unaliased`() {
        val resolved = FpLines.resolveFilterField("orderId.customerId.regionId.name", deep)

        assertEquals("orderId", resolved.rootName)
        assertEquals(FpLines.orderId, resolved.rootExpression)
        assertEquals(listOf(FpOrders, FpCustomers, FpRegions), resolved.hops.map { it.reference.referencedTable })
        assertEquals(listOf("orderId", "customerId", "regionId"), resolved.hops.map { it.segment })
        assertEquals(listOf("customerId", "regionId", "name"), resolved.hops.map { it.field })
        resolved.hops.forEach { assertSame(it.reference.referencedTable, it.occurrence) }
        assertEquals(listOf(FpOrders.id, FpCustomers.id, FpRegions.id), resolved.hops.map { it.idExpression })
        assertEquals(FpRegions.name, resolved.expression)
        assertTrue(resolved.jsonPath.isEmpty())
        assertEquals(listOf(FpOrders), resolved.hops[0].tables)
    }

    @Test
    fun `a table met again on the path is aliased per hop`() {
        val resolved = FpCategories.resolveFilterField("parentId.parentId.name", deep)

        assertEquals(2, resolved.hops.size)
        val (first, second) = resolved.hops.map { it.occurrence as Alias<*> }
        assertEquals(listOf("ef_1", "ef_2"), listOf(first.alias, second.alias))
        assertSame(FpCategories, first.delegate)
        assertSame(FpCategories, second.delegate)
        assertEquals(second[FpCategories.name], resolved.expression)
        assertEquals(FpCategories.parentId, resolved.hops[0].referenceExpression)
        assertEquals(first[FpCategories.parentId], resolved.hops[1].referenceExpression)
        assertEquals(FpCategories.parentId, resolved.hops[1].referenceColumn)
        assertEquals(second[FpCategories.id], resolved.hops[1].idExpression)
        assertEquals(listOf(FpCategories), resolved.hops[1].tables)
    }

    @Test
    fun `a hop back to the root table is aliased and may come from the reference resolver`() {
        val resolved = FpOrders.resolveFilterField("customerId.lastOrderId.number", deep)

        assertSame(FpCustomers, resolved.hops[0].occurrence)
        val back = resolved.hops[1]
        val occurrence = back.occurrence as Alias<*>
        assertEquals("ef_2", occurrence.alias)
        assertEquals(ReferenceInfo(FpOrders.id, FpOrders), back.reference)
        assertEquals(occurrence[FpOrders.number], resolved.expression)
    }

    @Test
    fun `a JSON column behind a hop takes the rest of the path`() {
        val resolved = FpOrders.resolveFilterField("customerId.profile.tier", deep)

        assertEquals(1, resolved.hops.size)
        assertEquals(listOf("tier"), resolved.jsonPath)
        assertEquals(FpCustomers.profile, resolved.expression)
    }

    @Test
    fun `JSON segments do not count as hops`() {
        val resolved = FpCustomers.resolveFilterField("profile.tier", FilterOptions(maxReferenceHops = 0))

        assertTrue(resolved.hops.isEmpty())
        assertEquals(listOf("tier"), resolved.jsonPath)
        assertEquals(FpCustomers.profile, resolved.expression)
    }

    @Test
    fun `a root field resolves without hops`() {
        val resolved = FpCustomers.resolveFilterField("name")

        assertTrue(resolved.hops.isEmpty())
        assertEquals("name", resolved.rootName)
        assertEquals(FpCustomers.name, resolved.expression)
        assertTrue(resolved.jsonPath.isEmpty())
    }

    @Test
    fun `an aliased root puts only its alias in scope`() {
        val c = FpCategories.alias("c")
        val expressions = mapOf("id" to c[FpCategories.id], "parentId" to c[FpCategories.parentId])

        val resolved = expressions.resolveFilterField("parentId.parentId.name", deep)

        assertEquals(2, resolved.hops.size)
        val (first, second) = resolved.hops
        assertEquals(c[FpCategories.parentId], first.referenceExpression)
        assertEquals(FpCategories.parentId, first.referenceColumn)
        assertSame(FpCategories, first.occurrence)
        val inner = second.occurrence as Alias<*>
        assertEquals("ef_2", inner.alias)
        assertEquals(inner[FpCategories.name], resolved.expression)
    }

    @Test
    fun `a projection over a join reads the target and the joined table`() {
        val translated = regionsWithTranslations()
        val options = projecting(FpRegions, "name") { NestedFieldProjection(translated, FpRegionTranslations.name) }

        val resolved = FpCustomers.resolveFilterField("regionId.name", options)

        val hop = resolved.hops.single()
        assertSame(FpRegions, hop.occurrence)
        assertSame(translated, hop.source)
        assertEquals(FpRegions.id, hop.idExpression)
        assertEquals(FpRegionTranslations.name, hop.expression)
        assertEquals(FpRegionTranslations.name, resolved.expression)
        assertEquals(listOf(FpRegions, FpRegionTranslations), hop.tables)
        assertEquals(setOf("fp_regions", "fp_region_translations"), hop.source.scopeNames())
    }

    @Test
    fun `a subquery projection reads the tables of the subquery's source`() {
        val cq = regionsWithTranslations().select(FpRegions.id, FpRegionTranslations.name).alias("cq")
        val options = projecting(FpRegions, "name") {
            NestedFieldProjection(cq, expression = cq[FpRegionTranslations.name], idExpression = cq[FpRegions.id])
        }

        val resolved = FpCustomers.resolveFilterField("regionId.name", options)

        val hop = resolved.hops.single()
        assertSame(cq, hop.source)
        assertEquals(cq[FpRegions.id], hop.idExpression)
        assertEquals(cq[FpRegionTranslations.name], resolved.expression)
        assertEquals(listOf(FpRegions, FpRegionTranslations), hop.tables)
        assertEquals(setOf("cq"), hop.source.scopeNames())
    }

    @Test
    fun `a subquery projection over set operations reads the tables of every statement`() {
        val db = Database.connect("jdbc:h2:mem:fp_set_${System.nanoTime()};DB_CLOSE_DELAY=-1", "org.h2.Driver")
        // Exposed takes a set operation's fields from its first statement; the other statements read tables too.
        val names = transaction(db) {
            FpRegions.select(FpRegions.id, FpRegions.name)
                .union(FpRegionTranslations.select(FpRegionTranslations.regionId, FpRegionTranslations.name))
                .union(FpCategories.select(FpCategories.id, FpCategories.name))
                .alias("names")
        }
        val options = projecting(FpRegions, "name") {
            NestedFieldProjection(names, expression = names[FpRegions.name], idExpression = names[FpRegions.id])
        }

        val hop = FpCustomers.resolveFilterField("regionId.name", options).hops.single()

        assertEquals(listOf(FpRegions, FpRegionTranslations, FpCategories), hop.tables)
    }

    @Test
    fun `a set operation joined into a projection's source reads the tables of every statement`() {
        val db = Database.connect("jdbc:h2:mem:fp_set_join_${System.nanoTime()};DB_CLOSE_DELAY=-1", "org.h2.Driver")
        val names = transaction(db) {
            FpRegionTranslations.select(FpRegionTranslations.regionId, FpRegionTranslations.name)
                .union(FpCategories.select(FpCategories.id, FpCategories.name))
                .alias("names")
        }
        val source = FpRegions.join(names, JoinType.LEFT, FpRegions.id, names[FpRegionTranslations.regionId])
        val options = projecting(FpRegions, "name") { NestedFieldProjection(source, names[FpRegionTranslations.name]) }

        val hop = FpCustomers.resolveFilterField("regionId.name", options).hops.single()

        assertEquals(listOf(FpRegions, FpRegionTranslations, FpCategories), hop.tables)
    }

    @Test
    fun `a target the reference resolver gives as an alias is listed as its table`() {
        val o = FpOrders.alias("o")
        val options = deep.copy(
            referenceResolver = { if (it == FpCustomers.lastOrderId) ReferenceInfo(o[FpOrders.id], o) else null },
        )

        val hop = FpCustomers.resolveFilterField("lastOrderId.number", options).hops.single()

        assertSame(o, hop.occurrence)
        assertEquals(listOf<Table>(FpOrders), hop.tables)
        assertFalse(hop.tables.single() is Alias<*>)
    }

    @Test
    fun `the hop after a projection follows its expression and is aliased only by names its source binds`() {
        val o2 = FpOrders.alias("o2")
        val options = projecting(FpOrders, "customerId") {
            NestedFieldProjection(source = o2, expression = o2[FpOrders.customerId], idExpression = o2[FpOrders.id])
        }

        val resolved = FpLines.resolveFilterField("orderId.customerId.name", options)

        assertEquals(2, resolved.hops.size)
        val (orders, customers) = resolved.hops
        assertEquals(o2, orders.source)
        assertEquals(o2[FpOrders.id], orders.idExpression)
        assertEquals(listOf(FpOrders), orders.tables)
        assertEquals(o2[FpOrders.customerId], customers.referenceExpression)
        assertEquals(FpOrders.customerId, customers.referenceColumn)
        assertSame(FpCustomers, customers.occurrence)
        assertEquals(FpCustomers.name, resolved.expression)
    }

    @Test
    fun `the hop after a projection is aliased when the projection's source binds its table`() {
        val withCustomers = FpOrders.join(FpCustomers, JoinType.INNER, FpOrders.customerId, FpCustomers.id)
        val options = projecting(FpOrders, "customerId") { NestedFieldProjection(withCustomers, FpOrders.customerId) }

        val resolved = FpLines.resolveFilterField("orderId.customerId.name", options)

        assertEquals(2, resolved.hops.size)
        assertEquals(setOf("fp_orders", "fp_customers"), resolved.hops[0].source.scopeNames())
        val customers = resolved.hops[1].occurrence as Alias<*>
        assertEquals("ef_2", customers.alias)
        assertSame(FpCustomers, customers.delegate)
        assertEquals(customers[FpCustomers.name], resolved.expression)
    }

    @Test
    fun `a source of an unknown kind is read through the tables of its columns`() {
        val o2 = FpOrders.alias("o2")
        val source = ColumnsOnlySource(listOf(FpRegions.name, o2[FpOrders.id], o2[FpOrders.number]))

        assertEquals(listOf(FpRegions, FpOrders), source.readTables())
        assertEquals(setOf("fp_regions", "o2"), source.scopeNames())
    }

    @Test
    fun `a reference whose id column is not on its table is a misconfiguration, not a path failure`() {
        val misconfigured = deep.copy(
            referenceResolver = { if (it == FpCustomers.lastOrderId) ReferenceInfo(FpRegions.id, FpOrders) else null },
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            FpOrders.resolveFilterField("customerId.lastOrderId.number", misconfigured)
        }

        assertFalse(failure is FilterPathException)
        assertTrue(failure.message.orEmpty().contains("'customerId.lastOrderId.number'"))
        assertTrue(failure.message.orEmpty().contains("'lastOrderId'"))
    }

    @Test
    fun `the default allows one hop`() {
        assertPathFailure(Reason.DEPTH_EXCEEDED, 1, "customerId") {
            FpLines.resolveFilterField("orderId.customerId.name")
        }
    }

    @Test
    fun `a hop past the limit fails at the reference segment`() {
        assertPathFailure(Reason.DEPTH_EXCEEDED, 2, "regionId") {
            FpLines.resolveFilterField("orderId.customerId.regionId.name", FilterOptions(maxReferenceHops = 2))
        }
    }

    @Test
    fun `the depth is checked before the reference is resolved`() {
        assertPathFailure(Reason.DEPTH_EXCEEDED, 1, "number") {
            FpLines.resolveFilterField("orderId.number.x")
        }
    }

    @Test
    fun `an unknown root keeps its message`() {
        val failure = assertPathFailure(Reason.UNKNOWN_FIELD, 0, "missing") {
            FpLines.resolveFilterField("missing.x", deep)
        }
        assertEquals("Unknown filter field: missing", failure.message)
    }

    @Test
    fun `an unknown field of a target fails at its segment`() {
        assertPathFailure(Reason.UNKNOWN_FIELD, 1, "nope") { FpLines.resolveFilterField("orderId.nope", deep) }
    }

    @Test
    fun `a path below a plain column fails`() {
        assertPathFailure(Reason.NOT_A_REFERENCE, 0, "number") { FpOrders.resolveFilterField("number.x", deep) }
    }

    @Test
    fun `a path below a computed expression fails`() {
        val expressions = mapOf("label" to FpCustomers.name.upperCase())
        assertPathFailure(Reason.NOT_A_COLUMN, 0, "label") { expressions.resolveFilterField("label.x", deep) }
    }

    @Test
    fun `an empty reference segment fails`() {
        assertPathFailure(Reason.INVALID_SEGMENT, 1, "") { FpLines.resolveFilterField("orderId..name", deep) }
    }

    @Test
    fun `a target on a plain reference fails`() {
        assertPathFailure(Reason.TARGET_NOT_SUPPORTED, 0, "orderId[X]") {
            FpLines.resolveFilterField("orderId[X].number", deep)
        }
    }

    @Test
    fun `a target on the segment reaching a JSON column fails`() {
        assertPathFailure(Reason.TARGET_NOT_SUPPORTED, 1, "profile[X]") {
            FpOrders.resolveFilterField("customerId.profile[X].tier", deep)
        }
    }

    @Test
    fun `an empty JSON segment fails`() {
        assertPathFailure(Reason.INVALID_SEGMENT, 2, "") {
            FpOrders.resolveFilterField("customerId.profile..tier", deep)
        }
    }

    @Test
    fun `a path ending at a target fails`() {
        assertPathFailure(Reason.TARGET_WITHOUT_FIELD, 0, "orderId[X]") {
            FpLines.resolveFilterField("orderId[X]", deep)
        }
    }

    // ----- A reference with several targets -----

    private val polymorphic = FilterOptions(polymorphicReferenceResolver = rpDocumentReferences)

    @Test
    fun `a named target resolves into its table`() {
        val resolved = RpContainers.resolveFilterField("documentId[RECEIPT].number", polymorphic)

        val hop = resolved.hops.single()
        assertEquals("RECEIPT", hop.target)
        assertEquals(RpContainers.documentType, hop.discriminator)
        assertSame(RpReceipts, hop.reference.referencedTable)
        assertEquals("documentId[RECEIPT]", hop.segment)
        assertEquals(RpReceipts.number, resolved.expression)
    }

    @Test
    fun `a reference with several targets needs a target`() {
        assertPathFailure(Reason.TARGET_REQUIRED, 0, "documentId") {
            RpContainers.resolveFilterField("documentId.number", polymorphic)
        }
    }

    @Test
    fun `a target the reference does not know fails`() {
        assertPathFailure(Reason.UNKNOWN_TARGET, 0, "documentId[ORDER]") {
            RpContainers.resolveFilterField("documentId[ORDER].number", polymorphic)
        }
    }

    @Test
    fun `a target on a column the polymorphic resolver does not describe fails`() {
        assertPathFailure(Reason.TARGET_NOT_SUPPORTED, 0, "code[X]") {
            RpContainers.resolveFilterField("code[X].y", polymorphic)
        }
    }

    @Test
    fun `a path ending at a known target fails`() {
        assertPathFailure(Reason.TARGET_WITHOUT_FIELD, 0, "documentId[RECEIPT]") {
            RpContainers.resolveFilterField("documentId[RECEIPT]", polymorphic)
        }
    }

    /** Describes `documentId` with a discriminator of another table. */
    private val misplacedDiscriminator = FilterOptions(
        polymorphicReferenceResolver = { column ->
            if (column == RpContainers.documentId) {
                PolymorphicReferenceInfo(
                    discriminator = RpMoves.containerId,
                    targets = mapOf("RECEIPT" to ReferenceInfo(RpReceipts.id, RpReceipts)),
                )
            } else {
                null
            }
        },
    )

    @Test
    fun `a discriminator on another table is a misconfiguration, not a path failure`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RpContainers.resolveFilterField("documentId[RECEIPT].number", misplacedDiscriminator)
        }

        assertFalse(failure is FilterPathException)
        assertTrue(failure.message.orEmpty().contains("rp_moves.container_id"), failure.message)
    }

    @Test
    fun `a discriminator on another table is a misconfiguration even when no target is named`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RpContainers.resolveFilterField("documentId.number", misplacedDiscriminator)
        }

        assertFalse(failure is FilterPathException)
        assertTrue(failure.message.orEmpty().contains("rp_moves.container_id"), failure.message)
    }

    @Test
    fun `an ambiguous name of a join is not a path failure`() {
        val join = FpRegions.join(FpCustomers, JoinType.CROSS)
        val failure = assertThrows(IllegalArgumentException::class.java) { join.resolveFilterField("name", deep) }
        assertFalse(failure is FilterPathException)
    }

    @Test
    fun `a negative hop limit is refused`() {
        assertThrows(IllegalArgumentException::class.java) { FilterOptions(maxReferenceHops = -1) }
    }

    private fun categoryProjection(
        source: (Table) -> ColumnSet,
        expression: (Table) -> ExpressionWithColumnType<*>,
    ): FilterOptions =
        deep.copy(
            nestedFieldResolver = { occurrence, name ->
                if (name == "code" && occurrence is Alias<*>) {
                    NestedFieldProjection(source = source(occurrence), expression = expression(occurrence))
                } else {
                    null
                }
            },
        )

    private fun aliased(table: Table): Alias<*> = table as Alias<*>

    @Test
    fun `a source that joins a table in scope again is a misconfiguration`() {
        val options = categoryProjection(
            { occurrence -> aliased(occurrence).join(FpCategories, JoinType.CROSS) },
            { occurrence -> aliased(occurrence)[FpCategories.code] },
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            FpCategories.resolveFilterField("parentId.parentId.code", options)
        }
        assertFalse(failure is FilterPathException)
        assertTrue(failure.message!!.contains("already in scope outside it"), failure.message)
        assertTrue(failure.message!!.contains("parentId.parentId"), failure.message)
    }

    @Test
    fun `an expression over the plain table while the source reads its alias is a misconfiguration`() {
        val options = categoryProjection({ it }, { FpCategories.code })

        val failure = assertThrows(IllegalArgumentException::class.java) {
            FpCategories.resolveFilterField("parentId.parentId.code", options)
        }
        assertFalse(failure is FilterPathException)
        assertTrue(failure.message!!.contains("does not make visible"), failure.message)
        assertTrue(failure.message!!.contains("parentId.parentId.code"), failure.message)
    }

    @Test
    fun `an expression over the occurrence is accepted`() {
        val options = categoryProjection({ it }, { aliased(it)[FpCategories.code] })

        val resolved = FpCategories.resolveFilterField("parentId.parentId.code", options)

        assertEquals(aliased(resolved.hops[1].occurrence)[FpCategories.code], resolved.expression)
    }

    @Test
    fun `a subquery alias projection over the plain table is accepted`() {
        val cq = FpCategories.select(FpCategories.id, FpCategories.code).alias("cq")
        val code = cq[FpCategories.code]
        val id = cq[FpCategories.id]
        val options = deep.copy(
            nestedFieldResolver = { _, name ->
                if (name == "code") {
                    NestedFieldProjection(source = cq, expression = code, idExpression = id)
                } else {
                    null
                }
            },
        )

        val resolved = FpCategories.resolveFilterField("parentId.code", options)

        assertEquals(code, resolved.expression)
        assertEquals(id, resolved.hops[0].idExpression)
    }

    @Test
    fun `the id message wins when the source also breaks the scope`() {
        val options = categoryProjection(
            { ColumnsOnlySource(listOf(FpCategories.name, FpCategories.code)) },
            { aliased(it)[FpCategories.code] },
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            FpCategories.resolveFilterField("parentId.parentId.code", options)
        }
        assertTrue(failure.message!!.contains("does not expose"), failure.message)
        assertFalse(failure.message!!.contains("in scope"), failure.message)
    }

    @Test
    fun `an alias-only reference to a computed field reads no column`() {
        val total = FpOrders.id.sum().alias("total")
        val orders = FpOrders.select(total).alias("o")

        assertEquals(emptySet<Column<*>>(), orders[total].referencedColumns())
    }

    @Test
    fun `a query held by an expression contributes none of its columns`() {
        val expression = exists(FpOrders.selectAll())

        assertEquals(emptySet<Column<*>>(), expression.referencedColumns())
    }

    @Test
    fun `a union held by an expression is never iterated`() {
        val db = Database.connect("jdbc:h2:mem:fp_union_${System.nanoTime()};DB_CLOSE_DELAY=-1", "org.h2.Driver")
        val union = transaction(db) { FpOrders.select(FpOrders.id).union(FpOrders.select(FpOrders.id)) }
        // Iterating the union runs SQL, which throws here: the transaction is over.

        assertEquals(emptySet<Column<*>>(), exists(union).referencedColumns())
    }

    @Test
    fun `columns nested in a function are collected`() {
        val expression = coalesce(FpOrders.number, FpCustomers.name)

        assertEquals(setOf<Column<*>>(FpOrders.number, FpCustomers.name), expression.referencedColumns())
    }

    @Test
    fun `an expression without columns reads none`() {
        assertEquals(emptySet<Column<*>>(), stringLiteral("x").referencedColumns())
    }
}
