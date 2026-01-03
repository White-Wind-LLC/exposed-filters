package ua.wwind.exposed.filters.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FilterRequestBuilderTest {

    @Test
    fun `filterRequest returns null for empty builder`() {
        val result = filterRequest { }
        assertNull(result)
    }

    @Test
    fun `eq creates correct filter`() {
        val result = filterRequest {
            eq("status", "ACTIVE")
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(1, leaf.predicates.size)
        assertEquals("status", leaf.predicates[0].field)
        assertEquals(FilterOperator.EQ, leaf.predicates[0].operator)
        assertEquals(listOf("ACTIVE"), leaf.predicates[0].values)
    }

    @Test
    fun `multiple predicates create leaf with AND combinator`() {
        val result = filterRequest {
            eq("status", "ACTIVE")
            gte("age", 18)
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(2, leaf.predicates.size)
    }

    @Test
    fun `or child creates nested group`() {
        val result = filterRequest {
            eq("status", "ACTIVE")
            or {
                eq("type", "A")
                eq("type", "B")
            }
        }

        assertNotNull(result)
        val group = result!!.root as FilterGroup
        assertEquals(FilterCombinator.AND, group.combinator)
        assertEquals(2, group.children.size)

        val orGroup = group.children[1] as FilterGroup
        assertEquals(FilterCombinator.OR, orGroup.combinator)
    }

    @Test
    fun `inList creates IN operator`() {
        val result = filterRequest {
            inList("status", "A", "B", "C")
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.IN, leaf.predicates[0].operator)
        assertEquals(listOf("A", "B", "C"), leaf.predicates[0].values)
    }

    @Test
    fun `between creates BETWEEN operator`() {
        val result = filterRequest {
            between("age", 18, 65)
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.BETWEEN, leaf.predicates[0].operator)
        assertEquals(listOf("18", "65"), leaf.predicates[0].values)
    }

    @Test
    fun `isNull creates IS_NULL operator`() {
        val result = filterRequest {
            isNull("deletedAt")
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.IS_NULL, leaf.predicates[0].operator)
        assertTrue(leaf.predicates[0].values.isEmpty())
    }

    @Test
    fun `eqOrRemove removes field when value is null`() {
        val result = filterRequest {
            eq("status", "ACTIVE")
            eqOrRemove("type", null)
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(1, leaf.predicates.size)
        assertEquals("status", leaf.predicates[0].field)
    }

    @Test
    fun `eq adds multiple predicates for same field`() {
        val result = filterRequest {
            eq("status", "ACTIVE")
            eq("status", "INACTIVE")
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(2, leaf.predicates.size)
        assertEquals(listOf("ACTIVE"), leaf.predicates[0].values)
        assertEquals(listOf("INACTIVE"), leaf.predicates[1].values)
    }

    @Test
    fun `replace explicitly replaces existing predicate`() {
        val result = filterRequest {
            eq("status", "ACTIVE")
            replace("status", FilterOperator.EQ, "INACTIVE")
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(1, leaf.predicates.size)
        assertEquals(listOf("INACTIVE"), leaf.predicates[0].values)
    }

    @Test
    fun `not combinator creates NOT group`() {
        val result = filterRequest {
            not {
                eq("status", "DELETED")
            }
        }

        assertNotNull(result)
        val group = result!!.root as FilterGroup
        assertEquals(FilterCombinator.NOT, group.combinator)
    }

    @Test
    fun `typed values are converted to strings`() {
        val result = filterRequest {
            eq("count", 42)
            eq("active", true)
            eq("rate", 3.14)
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(3, leaf.predicates.size)
        assertEquals(listOf("42"), leaf.predicates[0].values)
        assertEquals(listOf("true"), leaf.predicates[1].values)
        assertEquals(listOf("3.14"), leaf.predicates[2].values)
    }

    @Test
    fun `toBuilder preserves existing filter structure`() {
        val original = filterRequest {
            eq("status", "ACTIVE")
            gte("age", 18)
        }

        assertNotNull(original)
        val builder = original!!.toBuilder()
        builder.eq("role", "ADMIN")
        val modified = builder.build()

        assertNotNull(modified)
        val leaf = modified!!.root as FilterLeaf
        assertEquals(3, leaf.predicates.size)
    }

    @Test
    fun `nested and or not structure`() {
        val result = filterRequest {
            and {
                eq("active", true)
                or {
                    eq("role", "ADMIN")
                    eq("role", "MODERATOR")
                }
            }
            not {
                eq("deleted", true)
            }
        }

        assertNotNull(result)
        val root = result!!.root as FilterGroup
        assertEquals(FilterCombinator.AND, root.combinator)
        assertEquals(2, root.children.size)
    }

    @Test
    fun `eqIfAbsent does not override existing predicate`() {
        val result = filterRequest {
            eq("status", "ACTIVE")
            eqIfAbsent("status", "INACTIVE")
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(1, leaf.predicates.size)
        assertEquals(listOf("ACTIVE"), leaf.predicates[0].values)
    }

    @Test
    fun `eqIfAbsent adds predicate when field is absent`() {
        val result = filterRequest {
            eq("type", "A")
            eqIfAbsent("status", "ACTIVE")
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(2, leaf.predicates.size)
    }

    @Test
    fun `contains creates CONTAINS operator`() {
        val result = filterRequest {
            contains("name", "John")
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.CONTAINS, leaf.predicates[0].operator)
        assertEquals(listOf("John"), leaf.predicates[0].values)
    }

    @Test
    fun `comparison operators work correctly`() {
        val result = filterRequest {
            gt("score", 90)
            lt("errors", 5)
            gte("level", 1)
            lte("attempts", 3)
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(4, leaf.predicates.size)
        assertEquals(FilterOperator.GT, leaf.predicates[0].operator)
        assertEquals(FilterOperator.LT, leaf.predicates[1].operator)
        assertEquals(FilterOperator.GTE, leaf.predicates[2].operator)
        assertEquals(FilterOperator.LTE, leaf.predicates[3].operator)
    }

    @Test
    fun `notInList creates NOT_IN operator`() {
        val result = filterRequest {
            notInList("status", listOf("DELETED", "ARCHIVED"))
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.NOT_IN, leaf.predicates[0].operator)
        assertEquals(listOf("DELETED", "ARCHIVED"), leaf.predicates[0].values)
    }

    @Test
    fun `betweenOrRemove removes field when both values are null`() {
        val result = filterRequest {
            eq("status", "ACTIVE")
            betweenOrRemove("date", null, null)
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(1, leaf.predicates.size)
    }

    @Test
    fun `betweenOrRemove keeps field when at least one value is present`() {
        val result = filterRequest {
            betweenOrRemove("date", "2024-01-01", null)
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.BETWEEN, leaf.predicates[0].operator)
        assertEquals(listOf("2024-01-01", ""), leaf.predicates[0].values)
    }

    @Test
    fun `eqOrIsNull uses IS_NULL when value is null`() {
        val result = filterRequest {
            eqOrIsNull("parentId", null)
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.IS_NULL, leaf.predicates[0].operator)
    }

    @Test
    fun `eqOrIsNull uses EQ when value is not null`() {
        val result = filterRequest {
            eqOrIsNull("parentId", 123)
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.EQ, leaf.predicates[0].operator)
        assertEquals(listOf("123"), leaf.predicates[0].values)
    }

    // ----- Infix DSL tests -----

    @Test
    fun `infix eq creates correct filter`() {
        val result = filterRequest {
            "status" eq "ACTIVE"
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(1, leaf.predicates.size)
        assertEquals("status", leaf.predicates[0].field)
        assertEquals(FilterOperator.EQ, leaf.predicates[0].operator)
        assertEquals(listOf("ACTIVE"), leaf.predicates[0].values)
    }

    @Test
    fun `infix neq creates correct filter`() {
        val result = filterRequest {
            "status" neq "DELETED"
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.NEQ, leaf.predicates[0].operator)
    }

    @Test
    fun `infix comparison operators work correctly`() {
        val result = filterRequest {
            "score" gt 90
            "errors" lt 5
            "level" gte 1
            "attempts" lte 3
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(4, leaf.predicates.size)
        assertEquals(FilterOperator.GT, leaf.predicates[0].operator)
        assertEquals(FilterOperator.LT, leaf.predicates[1].operator)
        assertEquals(FilterOperator.GTE, leaf.predicates[2].operator)
        assertEquals(FilterOperator.LTE, leaf.predicates[3].operator)
    }

    @Test
    fun `infix inList creates IN operator`() {
        val result = filterRequest {
            "status" inList listOf("A", "B", "C")
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.IN, leaf.predicates[0].operator)
        assertEquals(listOf("A", "B", "C"), leaf.predicates[0].values)
    }

    @Test
    fun `infix notInList creates NOT_IN operator`() {
        val result = filterRequest {
            "status" notInList listOf("DELETED", "ARCHIVED")
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.NOT_IN, leaf.predicates[0].operator)
        assertEquals(listOf("DELETED", "ARCHIVED"), leaf.predicates[0].values)
    }

    @Test
    fun `infix between with IntRange creates BETWEEN operator`() {
        val result = filterRequest {
            "age" between 15..28
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.BETWEEN, leaf.predicates[0].operator)
        assertEquals(listOf("15", "28"), leaf.predicates[0].values)
    }

    @Test
    fun `infix between with LongRange creates BETWEEN operator`() {
        val result = filterRequest {
            "timestamp" between 1000L..2000L
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.BETWEEN, leaf.predicates[0].operator)
        assertEquals(listOf("1000", "2000"), leaf.predicates[0].values)
    }

    @Test
    fun `infix between with Pair creates BETWEEN operator`() {
        val result = filterRequest {
            "date" between ("2024-01-01" to "2024-12-31")
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.BETWEEN, leaf.predicates[0].operator)
        assertEquals(listOf("2024-01-01", "2024-12-31"), leaf.predicates[0].values)
    }

    @Test
    fun `infix contains creates CONTAINS operator`() {
        val result = filterRequest {
            "name" contains "John"
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.CONTAINS, leaf.predicates[0].operator)
        assertEquals(listOf("John"), leaf.predicates[0].values)
    }

    @Test
    fun `infix startsWith and endsWith work correctly`() {
        val result = filterRequest {
            "email" startsWith "admin"
            "email" endsWith ".com"
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(2, leaf.predicates.size)
        assertEquals(FilterOperator.STARTS_WITH, leaf.predicates[0].operator)
        assertEquals(FilterOperator.ENDS_WITH, leaf.predicates[1].operator)
    }

    @Test
    fun `nested or and blocks with infix syntax`() {
        val result = filterRequest {
            or {
                "type" eq "A"
                "type" eq "B"
                and {
                    "status" inList listOf("ACTIVE", "PENDING")
                    "age" between 15..28
                }
            }
        }

        assertNotNull(result)
        val orGroup = result!!.root as FilterGroup
        assertEquals(FilterCombinator.OR, orGroup.combinator)
        assertEquals(2, orGroup.children.size)

        val orLeaf = orGroup.children[0] as FilterLeaf
        assertEquals(2, orLeaf.predicates.size)
        assertEquals("type", orLeaf.predicates[0].field)
        assertEquals("A", orLeaf.predicates[0].values[0])
        assertEquals("type", orLeaf.predicates[1].field)
        assertEquals("B", orLeaf.predicates[1].values[0])

        // and block with only predicates simplifies to FilterLeaf
        val andLeaf = orGroup.children[1] as FilterLeaf
        assertEquals(2, andLeaf.predicates.size)
        assertEquals("status", andLeaf.predicates[0].field)
        assertEquals(FilterOperator.IN, andLeaf.predicates[0].operator)
        assertEquals("age", andLeaf.predicates[1].field)
        assertEquals(FilterOperator.BETWEEN, andLeaf.predicates[1].operator)
    }

    @Test
    fun `complex nested structure with infix syntax`() {
        val result = filterRequest {
            "active" eq true
            or {
                "role" eq "ADMIN"
                and {
                    "level" gte 5
                    "score" between 80..100
                }
            }
            not {
                "status" eq "DELETED"
            }
        }

        assertNotNull(result)
        val root = result!!.root as FilterGroup
        assertEquals(FilterCombinator.AND, root.combinator)
        assertEquals(3, root.children.size)
    }

    @Test
    fun `extension isNull creates IS_NULL operator`() {
        val result = filterRequest {
            "deletedAt".isNull()
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.IS_NULL, leaf.predicates[0].operator)
        assertEquals("deletedAt", leaf.predicates[0].field)
        assertTrue(leaf.predicates[0].values.isEmpty())
    }

    @Test
    fun `extension isNotNull creates IS_NOT_NULL operator`() {
        val result = filterRequest {
            "email".isNotNull()
        }

        assertNotNull(result)
        val leaf = result!!.root as FilterLeaf
        assertEquals(FilterOperator.IS_NOT_NULL, leaf.predicates[0].operator)
        assertEquals("email", leaf.predicates[0].field)
        assertTrue(leaf.predicates[0].values.isEmpty())
    }
}
