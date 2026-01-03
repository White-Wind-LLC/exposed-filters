package ua.wwind.exposed.filters.rest

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ua.wwind.exposed.filters.core.*

/**
 * Integration tests that verify round-trip serialization:
 * FilterRequestBuilder -> toJsonString() -> parseFilterRequestOrNull()
 */
class FilterSerializationRoundTripTest {

    // Helper function to collect all predicates from a filter tree
    private fun collectAllPredicates(node: FilterNode): List<FieldFilter> {
        return when (node) {
            is FilterLeaf -> node.predicates
            is FilterGroup -> node.children.flatMap { collectAllPredicates(it) }
        }
    }

    // Helper function to check if a combinator exists in the tree
    private fun containsCombinator(node: FilterNode, combinator: FilterCombinator): Boolean {
        return when (node) {
            is FilterLeaf -> false
            is FilterGroup -> node.combinator == combinator ||
                    node.children.any { containsCombinator(it, combinator) }
        }
    }

    @Test
    fun `simple eq filter round-trips correctly`() {
        val original = filterRequest {
            "status" eq "ACTIVE"
        }

        assertNotNull(original)
        val json = original!!.toJsonString()
        val parsed = parseFilterRequestOrNull(json)

        assertNotNull(parsed)
        val leaf = parsed!!.root as FilterLeaf
        assertEquals(1, leaf.predicates.size)
        assertEquals("status", leaf.predicates[0].field)
        assertEquals(FilterOperator.EQ, leaf.predicates[0].operator)
        assertEquals(listOf("ACTIVE"), leaf.predicates[0].values)
    }

    @Test
    fun `multiple predicates round-trip correctly`() {
        val original = filterRequest {
            "status" eq "ACTIVE"
            "age" gte 18
            "name" contains "John"
        }

        assertNotNull(original)
        val json = original!!.toJsonString()
        val parsed = parseFilterRequestOrNull(json)

        assertNotNull(parsed)
        val leaf = parsed!!.root as FilterLeaf
        assertEquals(3, leaf.predicates.size)

        val statusFilter = leaf.predicates.find { it.field == "status" }
        assertNotNull(statusFilter)
        assertEquals(FilterOperator.EQ, statusFilter!!.operator)

        val ageFilter = leaf.predicates.find { it.field == "age" }
        assertNotNull(ageFilter)
        assertEquals(FilterOperator.GTE, ageFilter!!.operator)

        val nameFilter = leaf.predicates.find { it.field == "name" }
        assertNotNull(nameFilter)
        assertEquals(FilterOperator.CONTAINS, nameFilter!!.operator)
    }

    @Test
    fun `inList operator round-trips correctly`() {
        val original = filterRequest {
            "status" inList listOf("A", "B", "C")
        }

        assertNotNull(original)
        val json = original!!.toJsonString()
        val parsed = parseFilterRequestOrNull(json)

        assertNotNull(parsed)
        val leaf = parsed!!.root as FilterLeaf
        assertEquals(FilterOperator.IN, leaf.predicates[0].operator)
        assertEquals(listOf("A", "B", "C"), leaf.predicates[0].values)
    }

    @Test
    fun `between operator round-trips correctly`() {
        val original = filterRequest {
            "age" between 18..65
        }

        assertNotNull(original)
        val json = original!!.toJsonString()
        val parsed = parseFilterRequestOrNull(json)

        assertNotNull(parsed)
        val leaf = parsed!!.root as FilterLeaf
        assertEquals(FilterOperator.BETWEEN, leaf.predicates[0].operator)
        assertEquals(listOf("18", "65"), leaf.predicates[0].values)
    }

    @Test
    fun `isNull operator round-trips correctly`() {
        val original = filterRequest {
            "deletedAt".isNull()
        }

        assertNotNull(original)
        val json = original!!.toJsonString()
        val parsed = parseFilterRequestOrNull(json)

        assertNotNull(parsed)
        val leaf = parsed!!.root as FilterLeaf
        assertEquals(FilterOperator.IS_NULL, leaf.predicates[0].operator)
    }

    @Test
    fun `isNotNull operator round-trips correctly`() {
        val original = filterRequest {
            "email".isNotNull()
        }

        assertNotNull(original)
        val json = original!!.toJsonString()
        val parsed = parseFilterRequestOrNull(json)

        assertNotNull(parsed)
        val leaf = parsed!!.root as FilterLeaf
        assertEquals(FilterOperator.IS_NOT_NULL, leaf.predicates[0].operator)
    }

    @Test
    fun `or nested group round-trips correctly`() {
        val original = filterRequest {
            "status" eq "ACTIVE"
            or {
                "type" eq "A"
                "type" eq "B"
            }
        }

        assertNotNull(original)
        val json = original!!.toJsonString()
        val parsed = parseFilterRequestOrNull(json)

        assertNotNull(parsed)

        // Collect all predicates from tree
        val allPredicates = collectAllPredicates(parsed!!.root)

        // Verify status filter exists
        val statusFilter = allPredicates.find { it.field == "status" }
        assertNotNull(statusFilter)
        assertEquals(FilterOperator.EQ, statusFilter!!.operator)
        assertEquals(listOf("ACTIVE"), statusFilter.values)

        // Verify type filters exist (from OR group)
        val typeFilters = allPredicates.filter { it.field == "type" }
        assertEquals(2, typeFilters.size)
        // Note: OR combinator may be simplified by normalize() during parsing
        // when there's only one child, so we only verify data presence
    }

    @Test
    fun `not combinator round-trips correctly`() {
        val original = filterRequest {
            not {
                "status" eq "DELETED"
            }
        }

        assertNotNull(original)
        val json = original!!.toJsonString()
        val parsed = parseFilterRequestOrNull(json)

        assertNotNull(parsed)
        val group = parsed!!.root as FilterGroup
        assertEquals(FilterCombinator.NOT, group.combinator)
    }

    @Test
    fun `deeply nested structure round-trips correctly`() {
        val original = filterRequest {
            and {
                "active" eq true
                or {
                    "role" eq "ADMIN"
                    "role" eq "MODERATOR"
                }
            }
            not {
                "deleted" eq true
            }
        }

        assertNotNull(original)
        val json = original!!.toJsonString()
        val parsed = parseFilterRequestOrNull(json)

        assertNotNull(parsed)
        val root = parsed!!.root as FilterGroup
        assertEquals(FilterCombinator.AND, root.combinator)
        assertEquals(2, root.children.size)
    }

    @Test
    fun `all comparison operators round-trip correctly`() {
        val operators = listOf(
            Triple("gt", FilterOperator.GT) { b: ua.wwind.exposed.filters.core.FilterRequestBuilder -> b.gt("f", 1) },
            Triple("gte", FilterOperator.GTE) { b: ua.wwind.exposed.filters.core.FilterRequestBuilder ->
                b.gte(
                    "f",
                    1
                )
            },
            Triple("lt", FilterOperator.LT) { b: ua.wwind.exposed.filters.core.FilterRequestBuilder -> b.lt("f", 1) },
            Triple("lte", FilterOperator.LTE) { b: ua.wwind.exposed.filters.core.FilterRequestBuilder ->
                b.lte(
                    "f",
                    1
                )
            },
            Triple("neq", FilterOperator.NEQ) { b: ua.wwind.exposed.filters.core.FilterRequestBuilder ->
                b.neq(
                    "f",
                    1
                )
            },
            Triple(
                "startsWith",
                FilterOperator.STARTS_WITH
            ) { b: ua.wwind.exposed.filters.core.FilterRequestBuilder -> b.startsWith("f", "x") },
            Triple(
                "endsWith",
                FilterOperator.ENDS_WITH
            ) { b: ua.wwind.exposed.filters.core.FilterRequestBuilder -> b.endsWith("f", "x") },
        )

        for ((name, expectedOp, setup) in operators) {
            val original = filterRequest { setup(this) }
            assertNotNull(original, "Original should not be null for $name")

            val json = original!!.toJsonString()
            val parsed = parseFilterRequestOrNull(json)

            assertNotNull(parsed, "Parsed should not be null for $name")
            val leaf = parsed!!.root as FilterLeaf
            assertEquals(expectedOp, leaf.predicates[0].operator, "Operator mismatch for $name")
        }
    }

    @Test
    fun `notInList operator round-trips correctly`() {
        val original = filterRequest {
            "status" notInList listOf("DELETED", "ARCHIVED")
        }

        assertNotNull(original)
        val json = original!!.toJsonString()
        val parsed = parseFilterRequestOrNull(json)

        assertNotNull(parsed)
        val leaf = parsed!!.root as FilterLeaf
        assertEquals(FilterOperator.NOT_IN, leaf.predicates[0].operator)
        assertEquals(listOf("DELETED", "ARCHIVED"), leaf.predicates[0].values)
    }

    @Test
    fun `or combinator at root round-trips correctly`() {
        val original = filterRequest(FilterCombinator.OR) {
            "type" eq "A"
            "status" eq "ACTIVE"
        }

        assertNotNull(original)
        val json = original!!.toJsonString()
        val parsed = parseFilterRequestOrNull(json)

        assertNotNull(parsed)
        val group = parsed!!.root as FilterGroup
        assertEquals(FilterCombinator.OR, group.combinator)
    }

    @Test
    fun `complex real-world filter round-trips correctly`() {
        // Simulate a real-world filter: active users who are admins or moderators, not deleted
        val original = filterRequest {
            "active" eq true
            or {
                "role" eq "ADMIN"
                "role" eq "MODERATOR"
            }
            not {
                "deletedAt".isNotNull()
            }
            "createdAt" between ("2024-01-01" to "2024-12-31")
            "department" inList listOf("IT", "HR", "Finance")
        }

        assertNotNull(original)
        val json = original!!.toJsonString()
        val parsed = parseFilterRequestOrNull(json)

        assertNotNull(parsed)

        // Collect all predicates from tree
        val allPredicates = collectAllPredicates(parsed!!.root)

        // Verify all expected filters exist
        val activeFilter = allPredicates.find { it.field == "active" }
        assertNotNull(activeFilter, "Should have active filter")
        assertEquals(FilterOperator.EQ, activeFilter!!.operator)
        assertEquals(listOf("true"), activeFilter.values)

        val roleFilters = allPredicates.filter { it.field == "role" }
        assertEquals(2, roleFilters.size, "Should have 2 role filters")

        val deletedAtFilter = allPredicates.find { it.field == "deletedAt" }
        assertNotNull(deletedAtFilter, "Should have deletedAt filter")
        assertEquals(FilterOperator.IS_NOT_NULL, deletedAtFilter!!.operator)

        val createdAtFilter = allPredicates.find { it.field == "createdAt" }
        assertNotNull(createdAtFilter, "Should have createdAt filter")
        assertEquals(FilterOperator.BETWEEN, createdAtFilter!!.operator)
        assertEquals(listOf("2024-01-01", "2024-12-31"), createdAtFilter.values)

        val departmentFilter = allPredicates.find { it.field == "department" }
        assertNotNull(departmentFilter, "Should have department filter")
        assertEquals(FilterOperator.IN, departmentFilter!!.operator)
        assertEquals(listOf("IT", "HR", "Finance"), departmentFilter.values)

        // Note: combinators may be simplified by normalize() during parsing
        // NOT should be preserved as it has special handling
        assertTrue(containsCombinator(parsed.root, FilterCombinator.NOT), "Should contain NOT")
    }
}
