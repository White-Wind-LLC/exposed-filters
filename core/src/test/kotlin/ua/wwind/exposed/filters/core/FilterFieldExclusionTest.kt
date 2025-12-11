package ua.wwind.exposed.filters.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FilterFieldExclusionTest {

    // Helper functions for creating filters
    private fun leaf(vararg filters: FieldFilter) = FilterLeaf(filters.toList())
    private fun filter(field: String, value: String = "value") =
        FieldFilter(field, FilterOperator.EQ, listOf(value))

    private fun and(vararg children: FilterNode) = FilterGroup(FilterCombinator.AND, children.toList())
    private fun or(vararg children: FilterNode) = FilterGroup(FilterCombinator.OR, children.toList())
    private fun not(vararg children: FilterNode) = FilterGroup(FilterCombinator.NOT, children.toList())

    @Nested
    inner class BasicExclusionTests {

        @Test
        fun `excludeFields returns same filter when excludedFields is empty`() {
            val original = FilterRequest(leaf(filter("name"), filter("age")))

            val result = original.excludeFields(emptyList())

            assertEquals(original, result)
        }

        @Test
        fun `excludeFields returns null when all predicates are excluded`() {
            val original = FilterRequest(leaf(filter("name")))

            val result = original.excludeFields("name")

            assertNull(result)
        }

        @Test
        fun `excludeFields removes specified field from leaf`() {
            val original = FilterRequest(leaf(filter("name"), filter("age")))

            val result = original.excludeFields("name")

            val expected = FilterRequest(leaf(filter("age")))
            assertEquals(expected, result)
        }

        @Test
        fun `excludeFields removes multiple specified fields`() {
            val original = FilterRequest(leaf(filter("name"), filter("age"), filter("email")))

            val result = original.excludeFields("name", "email")

            val expected = FilterRequest(leaf(filter("age")))
            assertEquals(expected, result)
        }

        @Test
        fun `excludeFields with list parameter works same as vararg`() {
            val original = FilterRequest(leaf(filter("name"), filter("age")))

            val result = original.excludeFields(listOf("name"))

            val expected = FilterRequest(leaf(filter("age")))
            assertEquals(expected, result)
        }
    }

    @Nested
    inner class AndGroupTests {

        @Test
        fun `AND group removes excluded field and keeps others`() {
            val original = FilterRequest(
                and(
                    leaf(filter("name")),
                    leaf(filter("age"))
                )
            )

            val result = original.excludeFields("name")

            // Single child should be simplified (unwrapped from AND)
            val expected = FilterRequest(leaf(filter("age")))
            assertEquals(expected, result)
        }

        @Test
        fun `AND group returns null when all children are excluded`() {
            val original = FilterRequest(
                and(
                    leaf(filter("name")),
                    leaf(filter("email"))
                )
            )

            val result = original.excludeFields("name", "email")

            assertNull(result)
        }

        @Test
        fun `nested AND groups process correctly`() {
            val original = FilterRequest(
                and(
                    and(
                        leaf(filter("name")),
                        leaf(filter("age"))
                    ),
                    leaf(filter("email"))
                )
            )

            val result = original.excludeFields("name")

            val expected = FilterRequest(
                and(
                    leaf(filter("age")),
                    leaf(filter("email"))
                )
            )
            assertEquals(expected, result)
        }

        @Test
        fun `AND group with multiple predicates in leaf removes only excluded`() {
            val original = FilterRequest(
                and(
                    leaf(filter("name"), filter("age")),
                    leaf(filter("email"))
                )
            )

            val result = original.excludeFields("name")

            val expected = FilterRequest(
                and(
                    leaf(filter("age")),
                    leaf(filter("email"))
                )
            )
            assertEquals(expected, result)
        }
    }

    @Nested
    inner class OrGroupTests {

        @Test
        fun `OR group removes entire group when any direct leaf contains excluded field`() {
            // Removing from OR would narrow results, so we remove the whole OR
            val original = FilterRequest(
                or(
                    leaf(filter("name")),
                    leaf(filter("age"))
                )
            )

            val result = original.excludeFields("name")

            assertNull(result)
        }

        @Test
        fun `OR group keeps all when no excluded fields present`() {
            val original = FilterRequest(
                or(
                    leaf(filter("name")),
                    leaf(filter("age"))
                )
            )

            val result = original.excludeFields("email")

            assertEquals(original, result)
        }

        @Test
        fun `OR group with nested AND processes nested group`() {
            // OR contains AND which contains excluded field - process AND, keep OR
            val original = FilterRequest(
                or(
                    and(
                        leaf(filter("name")),
                        leaf(filter("age"))
                    ),
                    leaf(filter("email"))
                )
            )

            val result = original.excludeFields("name")

            val expected = FilterRequest(
                or(
                    leaf(filter("age")),
                    leaf(filter("email"))
                )
            )
            assertEquals(expected, result)
        }

        @Test
        fun `OR group with all nested children removed returns null`() {
            val original = FilterRequest(
                or(
                    and(
                        leaf(filter("name"))
                    ),
                    and(
                        leaf(filter("email"))
                    )
                )
            )

            val result = original.excludeFields("name", "email")

            assertNull(result)
        }

        @Test
        fun `OR simplifies to single child when one child is removed`() {
            val original = FilterRequest(
                or(
                    and(leaf(filter("name"))),
                    leaf(filter("email"))
                )
            )

            val result = original.excludeFields("name")

            // After removing AND(name), only leaf(email) remains, simplified from OR
            val expected = FilterRequest(leaf(filter("email")))
            assertEquals(expected, result)
        }
    }

    @Nested
    inner class NotGroupTests {

        @Test
        fun `NOT group is removed entirely when it contains excluded field`() {
            // NOT inverts logic, removing fields inside could narrow results
            val original = FilterRequest(
                not(
                    leaf(filter("name"))
                )
            )

            val result = original.excludeFields("name")

            assertNull(result)
        }

        @Test
        fun `NOT group keeps all when no excluded fields at any depth`() {
            val original = FilterRequest(
                not(
                    leaf(filter("name"))
                )
            )

            val result = original.excludeFields("email")

            assertEquals(original, result)
        }

        @Test
        fun `NOT group with nested AND is removed when any nested field is excluded`() {
            // Due to De Morgan's law, removing from NOT(AND(...)) could narrow results
            val original = FilterRequest(
                not(
                    and(
                        leaf(filter("name")),
                        leaf(filter("age"))
                    )
                )
            )

            val result = original.excludeFields("name")

            assertNull(result)
        }

        @Test
        fun `NOT group with deeply nested excluded field is removed`() {
            val original = FilterRequest(
                not(
                    and(
                        or(
                            leaf(filter("name")),
                            leaf(filter("age"))
                        ),
                        leaf(filter("email"))
                    )
                )
            )

            val result = original.excludeFields("name")

            assertNull(result)
        }
    }

    @Nested
    inner class ComplexScenarioTests {

        @Test
        fun `mixed AND and OR groups process correctly`() {
            val original = FilterRequest(
                and(
                    or(
                        leaf(filter("status")),
                        leaf(filter("type"))
                    ),
                    leaf(filter("name")),
                    leaf(filter("age"))
                )
            )

            val result = original.excludeFields("name")

            val expected = FilterRequest(
                and(
                    or(
                        leaf(filter("status")),
                        leaf(filter("type"))
                    ),
                    leaf(filter("age"))
                )
            )
            assertEquals(expected, result)
        }

        @Test
        fun `AND with OR child removes OR when OR has excluded leaf`() {
            val original = FilterRequest(
                and(
                    or(
                        leaf(filter("name")), // excluded - removes whole OR
                        leaf(filter("type"))
                    ),
                    leaf(filter("age"))
                )
            )

            val result = original.excludeFields("name")

            // OR is removed (has direct excluded leaf), only age remains
            val expected = FilterRequest(leaf(filter("age")))
            assertEquals(expected, result)
        }

        @Test
        fun `NOT inside AND is removed when NOT contains excluded field`() {
            val original = FilterRequest(
                and(
                    not(
                        leaf(filter("name"))
                    ),
                    leaf(filter("age"))
                )
            )

            val result = original.excludeFields("name")

            // NOT is removed, only age remains
            val expected = FilterRequest(leaf(filter("age")))
            assertEquals(expected, result)
        }

        @Test
        fun `all levels of nesting work together`() {
            val original = FilterRequest(
                and(
                    or(
                        and(
                            leaf(filter("a")),
                            leaf(filter("b"))
                        ),
                        leaf(filter("c"))
                    ),
                    not(
                        leaf(filter("d"))
                    ),
                    leaf(filter("e"))
                )
            )

            // Exclude 'a' - processes nested AND, removes from it
            // Exclude 'd' - removes NOT entirely
            val result = original.excludeFields("a", "d")

            val expected = FilterRequest(
                and(
                    or(
                        leaf(filter("b")),
                        leaf(filter("c"))
                    ),
                    leaf(filter("e"))
                )
            )
            assertEquals(expected, result)
        }

        @Test
        fun `multiple fields in single leaf with partial exclusion`() {
            val original = FilterRequest(
                leaf(
                    filter("name"),
                    filter("age"),
                    filter("email"),
                    filter("status")
                )
            )

            val result = original.excludeFields("name", "email")

            val expected = FilterRequest(
                leaf(
                    filter("age"),
                    filter("status")
                )
            )
            assertEquals(expected, result)
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `empty FilterLeaf remains empty`() {
            val original = FilterRequest(FilterLeaf(emptyList()))

            val result = original.excludeFields("name")

            assertNull(result)
        }

        @Test
        fun `empty FilterGroup AND returns null`() {
            val original = FilterRequest(FilterGroup(FilterCombinator.AND, emptyList()))

            val result = original.excludeFields("name")

            assertNull(result)
        }

        @Test
        fun `field name case sensitivity is preserved`() {
            val original = FilterRequest(
                leaf(filter("Name"), filter("name"), filter("NAME"))
            )

            val result = original.excludeFields("name")

            val expected = FilterRequest(leaf(filter("Name"), filter("NAME")))
            assertEquals(expected, result)
        }

        @Test
        fun `excluding non-existent field returns unchanged filter`() {
            val original = FilterRequest(leaf(filter("name"), filter("age")))

            val result = original.excludeFields("nonExistent")

            assertEquals(original, result)
        }

        @Test
        fun `single child AND is simplified after exclusion`() {
            val original = FilterRequest(
                and(
                    leaf(filter("name")),
                    leaf(filter("age"))
                )
            )

            val result = original.excludeFields("name")

            // Should be simplified to just the leaf, not AND with single child
            val expected = FilterRequest(leaf(filter("age")))
            assertEquals(expected, result)
        }

        @Test
        fun `single child OR is simplified after exclusion`() {
            val original = FilterRequest(
                or(
                    and(leaf(filter("name"))),
                    leaf(filter("age"))
                )
            )

            val result = original.excludeFields("name")

            val expected = FilterRequest(leaf(filter("age")))
            assertEquals(expected, result)
        }

        @Test
        fun `NOT with single child is not simplified`() {
            // NOT should keep its structure
            val original = FilterRequest(
                not(
                    leaf(filter("name"))
                )
            )

            val result = original.excludeFields("other")

            assertEquals(original, result)
        }
    }
}
