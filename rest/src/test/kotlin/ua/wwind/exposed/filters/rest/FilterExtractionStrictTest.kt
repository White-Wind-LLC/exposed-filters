package ua.wwind.exposed.filters.rest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ua.wwind.exposed.filters.core.FilterGroup
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterOperator

/**
 * Verifies that a non-empty filter body which does not match the expected shape is rejected
 * instead of being silently degraded into "no filters at all".
 */
class FilterExtractionStrictTest {

    // --- Bodies that legitimately mean "no filters" -------------------------------------------

    @Test
    fun `blank body yields no filter`() {
        assertNull(parseFilterRequestOrNull(""))
        assertNull(parseFilterRequestOrNull("   "))
    }

    @Test
    fun `empty json object yields no filter`() {
        assertNull(parseFilterRequestOrNull("{}"))
    }

    @Test
    fun `explicitly empty filters map yields no filter`() {
        assertNull(parseFilterRequestOrNull("""{"filters":{}}"""))
    }

    @Test
    fun `explicitly null filters yields no filter`() {
        assertNull(parseFilterRequestOrNull("""{"filters":null}"""))
    }

    // --- Bodies that must be rejected ----------------------------------------------------------

    @Test
    fun `shorthand body with no known key is rejected`() {
        assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull("""{"documentId":{"eq":"00000000-0000-0000-0000-000000000001"}}""")
        }
    }

    @Test
    fun `misspelled filters key is rejected`() {
        assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull("""{"filterz":{"status":[{"op":"EQ","value":"ACTIVE"}]}}""")
        }
    }

    @Test
    fun `unknown key alongside a valid filters key is rejected`() {
        assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull("""{"filters":{"status":[{"op":"EQ","value":"ACTIVE"}]},"sort":"name"}""")
        }
    }

    @Test
    fun `unknown key inside a nested child is rejected`() {
        assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull(
                """{"children":[{"filters":{"status":[{"op":"EQ","value":"A"}]},"combinatorr":"OR"}]}"""
            )
        }
    }

    @Test
    fun `unknown key inside a condition is rejected`() {
        assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull("""{"filters":{"status":[{"op":"EQ","value":"A","mode":"ci"}]}}""")
        }
    }

    @Test
    fun `unknown combinator is rejected instead of being coerced to AND`() {
        assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull("""{"combinator":"XOR","filters":{"status":[{"op":"EQ","value":"A"}]}}""")
        }
    }

    @Test
    fun `unknown operator is rejected`() {
        assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull("""{"filters":{"status":[{"op":"MATCHES","value":"A"}]}}""")
        }
    }

    @Test
    fun `malformed json is rejected`() {
        assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull("""{"filters":""")
        }
    }

    @Test
    fun `json array body is rejected`() {
        assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull("""[{"op":"EQ","value":"A"}]""")
        }
    }

    @Test
    fun `json scalar body is rejected`() {
        assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull(""""documentId"""")
        }
    }

    // --- Contract of the thrown exception ------------------------------------------------------

    @Test
    fun `rejection is an IllegalArgumentException so existing handlers map it to 400`() {
        val thrown = assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull("""{"documentId":{"eq":"x"}}""")
        }
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `rejection message names the filter body`() {
        val thrown = assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull("""{"documentId":{"eq":"x"}}""")
        }
        assertTrue(
            thrown.message.orEmpty().contains("filter", ignoreCase = true),
            "message should mention the filter body, was: ${thrown.message}"
        )
    }

    @Test
    fun `rejection keeps the underlying serialization failure as cause`() {
        val thrown = assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull("""{"documentId":{"eq":"x"}}""")
        }
        assertNotNull(thrown.cause)
    }

    // --- Valid bodies still parse --------------------------------------------------------------

    @Test
    fun `valid flat body still parses`() {
        val parsed = parseFilterRequestOrNull("""{"filters":{"status":[{"op":"EQ","value":"ACTIVE"}]}}""")

        assertNotNull(parsed)
        val leaf = parsed!!.root as FilterLeaf
        assertTrue(leaf.predicates.single().field == "status")
    }

    @Test
    fun `filters and children at the same level are combined, not rejected`() {
        val parsed = parseFilterRequestOrNull(
            """
            {
              "combinator": "AND",
              "filters": {"status":[{"op":"EQ","value":"A"}]},
              "children": [{"filters":{"age":[{"op":"GTE","value":"18"}]}}]
            }
            """.trimIndent()
        )

        assertNotNull(parsed)
        val group = parsed!!.root as FilterGroup
        assertTrue(group.children.size == 2, "expected the leaf and the child to be combined")
    }

    @Test
    fun `valid tree body still parses`() {
        val parsed = parseFilterRequestOrNull(
            """
            {
              "combinator": "OR",
              "children": [
                {"filters":{"status":[{"op":"EQ","value":"A"}]}},
                {"filters":{"status":[{"op":"EQ","value":"B"}]}}
              ]
            }
            """.trimIndent()
        )

        assertNotNull(parsed)
    }

    // --- Typed (non-string) scalar values ------------------------------------------------------
    //
    // Filter values are declared as String, but real clients send numbers and booleans unquoted.
    // Lenient parsing reads any JSON scalar in a value position as its text, so these must parse
    // rather than 400. See `isLenient` in FilterExtraction.kt.

    @Test
    fun `numeric value is read as its text`() {
        val parsed = parseFilterRequestOrNull("""{"filters":{"pickingOrder":[{"op":"GT","value":5}]}}""")

        assertNotNull(parsed)
        val predicate = (parsed!!.root as FilterLeaf).predicates.single()
        assertEquals("pickingOrder", predicate.field)
        assertEquals(FilterOperator.GT, predicate.operator)
        assertEquals(listOf("5"), predicate.values)
    }

    @Test
    fun `boolean value is read as its text`() {
        val parsed = parseFilterRequestOrNull("""{"combinator":"AND","filters":{"supplier":[{"op":"EQ","value":true}]}}""")

        assertNotNull(parsed)
        val predicate = (parsed!!.root as FilterLeaf).predicates.single()
        assertEquals(listOf("true"), predicate.values)
    }

    @Test
    fun `numeric values list is read as text`() {
        val parsed = parseFilterRequestOrNull("""{"filters":{"id":[{"op":"IN","values":[1,2]}]}}""")

        assertNotNull(parsed)
        val predicate = (parsed!!.root as FilterLeaf).predicates.single()
        assertEquals(FilterOperator.IN, predicate.operator)
        assertEquals(listOf("1", "2"), predicate.values)
    }

    @Test
    fun `decimal value is read as its text`() {
        val parsed = parseFilterRequestOrNull("""{"filters":{"weight":[{"op":"LTE","value":1.5}]}}""")

        assertNotNull(parsed)
        val predicate = (parsed!!.root as FilterLeaf).predicates.single()
        assertEquals(listOf("1.5"), predicate.values)
    }

    @Test
    fun `typed value inside a nested child is read as its text`() {
        val parsed = parseFilterRequestOrNull(
            """
            {
              "combinator": "OR",
              "children": [
                {"filters":{"pickingOrder":[{"op":"GT","value":5}]}},
                {"filters":{"supplier":[{"op":"EQ","value":false}]}}
              ]
            }
            """.trimIndent()
        )

        assertNotNull(parsed)
        val group = parsed!!.root as FilterGroup
        val values = group.children.flatMap { (it as FilterLeaf).predicates }.flatMap { it.values }
        assertEquals(listOf("5", "false"), values)
    }

    @Test
    fun `a non-scalar value is still rejected`() {
        assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull("""{"filters":{"status":[{"op":"EQ","value":{"eq":"A"}}]}}""")
        }
        assertThrows<FilterRequestParseException> {
            parseFilterRequestOrNull("""{"filters":{"status":[{"op":"EQ","value":["A"]}]}}""")
        }
    }
}
