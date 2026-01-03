package ua.wwind.exposed.filters.core

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FilterRequestSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `simple eq filter serializes correctly`() {
        val request = filterRequest {
            "status" eq "ACTIVE"
        }

        assertNotNull(request)
        val jsonString = request!!.toJsonString()

        // Verify it can be parsed back
        val dto = json.decodeFromString<FilterBodyDto>(jsonString)
        assertNotNull(dto.filters)
        assertEquals(1, dto.filters!!.size)
        assertEquals("status", dto.filters.keys.first())
        assertEquals(FilterOperator.EQ, dto.filters["status"]!!.first().op)
        assertEquals("ACTIVE", dto.filters["status"]!!.first().value)
    }

    @Test
    fun `multiple predicates serialize correctly`() {
        val request = filterRequest {
            "status" eq "ACTIVE"
            "age" gte 18
        }

        assertNotNull(request)
        val jsonString = request!!.toJsonString()

        val dto = json.decodeFromString<FilterBodyDto>(jsonString)
        assertNotNull(dto.filters)
        assertEquals(2, dto.filters!!.size)
        assertTrue(dto.filters.containsKey("status"))
        assertTrue(dto.filters.containsKey("age"))
    }

    @Test
    fun `inList operator serializes with values array`() {
        val request = filterRequest {
            "status" inList listOf("A", "B", "C")
        }

        assertNotNull(request)
        val jsonString = request!!.toJsonString()

        val dto = json.decodeFromString<FilterBodyDto>(jsonString)
        val condition = dto.filters!!["status"]!!.first()
        assertEquals(FilterOperator.IN, condition.op)
        assertEquals(listOf("A", "B", "C"), condition.values)
    }

    @Test
    fun `between operator serializes with values array`() {
        val request = filterRequest {
            "age" between 18..65
        }

        assertNotNull(request)
        val jsonString = request!!.toJsonString()

        val dto = json.decodeFromString<FilterBodyDto>(jsonString)
        val condition = dto.filters!!["age"]!!.first()
        assertEquals(FilterOperator.BETWEEN, condition.op)
        assertEquals(listOf("18", "65"), condition.values)
    }

    @Test
    fun `isNull operator serializes without value`() {
        val request = filterRequest {
            "deletedAt".isNull()
        }

        assertNotNull(request)
        val jsonString = request!!.toJsonString()

        val dto = json.decodeFromString<FilterBodyDto>(jsonString)
        val condition = dto.filters!!["deletedAt"]!!.first()
        assertEquals(FilterOperator.IS_NULL, condition.op)
    }

    @Test
    fun `or nested group serializes correctly`() {
        val request = filterRequest {
            "status" eq "ACTIVE"
            or {
                "type" eq "A"
                "type" eq "B"
            }
        }

        assertNotNull(request)
        val jsonString = request!!.toJsonString()

        val dto = json.decodeFromString<FilterBodyDto>(jsonString)
        assertEquals(FilterCombinator.AND, dto.combinator)
        assertNotNull(dto.filters)
        assertNotNull(dto.children)
        assertEquals(1, dto.children!!.size)
        assertEquals(FilterCombinator.OR, dto.children.first().combinator)
    }

    @Test
    fun `not combinator serializes correctly`() {
        val request = filterRequest {
            not {
                "status" eq "DELETED"
            }
        }

        assertNotNull(request)
        val jsonString = request!!.toJsonString()

        val dto = json.decodeFromString<FilterBodyDto>(jsonString)
        assertEquals(FilterCombinator.NOT, dto.combinator)
    }

    @Test
    fun `deeply nested structure serializes correctly`() {
        val request = filterRequest {
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

        assertNotNull(request)
        val jsonString = request!!.toJsonString()

        val dto = json.decodeFromString<FilterBodyDto>(jsonString)
        assertEquals(FilterCombinator.AND, dto.combinator)
        assertNotNull(dto.children)
        assertEquals(2, dto.children!!.size)
    }

    @Test
    fun `all comparison operators serialize correctly`() {
        val request = filterRequest {
            "a" gt 1
            "b" gte 2
            "c" lt 3
            "d" lte 4
            "e" neq 5
            "f" contains "text"
            "g" startsWith "pre"
            "h" endsWith "suf"
        }

        assertNotNull(request)
        val jsonString = request!!.toJsonString()

        val dto = json.decodeFromString<FilterBodyDto>(jsonString)
        assertEquals(8, dto.filters!!.size)
        assertEquals(FilterOperator.GT, dto.filters["a"]!!.first().op)
        assertEquals(FilterOperator.GTE, dto.filters["b"]!!.first().op)
        assertEquals(FilterOperator.LT, dto.filters["c"]!!.first().op)
        assertEquals(FilterOperator.LTE, dto.filters["d"]!!.first().op)
        assertEquals(FilterOperator.NEQ, dto.filters["e"]!!.first().op)
        assertEquals(FilterOperator.CONTAINS, dto.filters["f"]!!.first().op)
        assertEquals(FilterOperator.STARTS_WITH, dto.filters["g"]!!.first().op)
        assertEquals(FilterOperator.ENDS_WITH, dto.filters["h"]!!.first().op)
    }

    @Test
    fun `notInList operator serializes correctly`() {
        val request = filterRequest {
            "status" notInList listOf("DELETED", "ARCHIVED")
        }

        assertNotNull(request)
        val jsonString = request!!.toJsonString()

        val dto = json.decodeFromString<FilterBodyDto>(jsonString)
        val condition = dto.filters!!["status"]!!.first()
        assertEquals(FilterOperator.NOT_IN, condition.op)
        assertEquals(listOf("DELETED", "ARCHIVED"), condition.values)
    }

    @Test
    fun `isNotNull operator serializes correctly`() {
        val request = filterRequest {
            "email".isNotNull()
        }

        assertNotNull(request)
        val jsonString = request!!.toJsonString()

        val dto = json.decodeFromString<FilterBodyDto>(jsonString)
        val condition = dto.filters!!["email"]!!.first()
        assertEquals(FilterOperator.IS_NOT_NULL, condition.op)
    }

    @Test
    fun `only or child serializes correctly`() {
        val request = filterRequest(FilterCombinator.OR) {
            "type" eq "A"
            "status" eq "ACTIVE"
        }

        assertNotNull(request)
        val jsonString = request!!.toJsonString()

        val dto = json.decodeFromString<FilterBodyDto>(jsonString)
        assertEquals(FilterCombinator.OR, dto.combinator)
    }
}
