package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.upperCase
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

/** Resolution of a filter path into hops, occurrences and the compared expression; it builds no SQL. */
class FilterPathResolutionTest {

    private val deep = FilterOptions(
        maxReferenceHops = null,
        referenceResolver = { if (it == FpCustomers.lastOrderId) ReferenceInfo(FpOrders.id, FpOrders) else null },
    )

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
}
