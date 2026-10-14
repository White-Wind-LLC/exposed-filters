package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.exceptions.UnsupportedByDialectException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.json.json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterOperator
import ua.wwind.exposed.filters.core.FilterRequest

object H2JsonUsersTable : Table("h2_json_users") {
    val id: Column<Int> = integer("id").autoIncrement()
    val name: Column<String> = varchar("name", 100)
    val payload: Column<String> = json("payload", { it }, { it })
    override val primaryKey = PrimaryKey(id)
}

class QueryFilterJsonPathTest {
    @BeforeEach
    fun setUp() {
        Database.connect(
            url = "jdbc:h2:mem:json_path_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        transaction {
            SchemaUtils.create(H2JsonUsersTable)
            H2JsonUsersTable.insert {
                it[name] = "Alice"
                it[payload] =
                    """{"profile":{"name":"Alice","age":25,"active":true,"joined":"2024-01-01","lastSeen":"2024-01-01T10:30:00"}}"""
            }
            H2JsonUsersTable.insert {
                it[name] = "Bob"
                it[payload] =
                    """{"profile":{"name":"Bob","age":31,"active":false,"joined":"2024-02-15","lastSeen":"2024-02-15T08:15:00"}}"""
            }
        }
    }

    @AfterEach
    fun tearDown() {
        transaction { SchemaUtils.drop(H2JsonUsersTable) }
    }

    @Test
    fun `throws dialect error for json path evaluation on h2`() {
        val filter = FilterRequest(
            FilterLeaf(
                listOf(
                    FieldFilter("payload.profile.age", FilterOperator.GTE, listOf("30"))
                )
            )
        )

        assertThrows(UnsupportedByDialectException::class.java) {
            transaction {
                H2JsonUsersTable.selectAll()
                    .applyFiltersOn(H2JsonUsersTable, filter)
                    .toList()
            }
        }
    }

    @Test
    fun `treats missing json key as null for IS_NULL`() {
        val filter = FilterRequest(
            FilterLeaf(
                listOf(
                    FieldFilter("payload.profile.missingField", FilterOperator.IS_NULL, emptyList())
                )
            )
        )

        assertThrows(UnsupportedByDialectException::class.java) {
            transaction {
                H2JsonUsersTable.selectAll()
                    .applyFiltersOn(H2JsonUsersTable, filter)
                    .count()
            }
        }
    }

    @Test
    fun `rejects contains for non-string json inferred values`() {
        val filter = FilterRequest(
            FilterLeaf(
                listOf(
                    FieldFilter("payload.profile.age", FilterOperator.CONTAINS, listOf("31"))
                )
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            transaction {
                H2JsonUsersTable.selectAll()
                    .applyFiltersOn(H2JsonUsersTable, filter)
                    .toList()
            }
        }
    }

    @Test
    fun `rejects mixed inferred types for IN`() {
        val filter = FilterRequest(
            FilterLeaf(
                listOf(
                    FieldFilter("payload.profile.age", FilterOperator.IN, listOf("31", "text"))
                )
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            transaction {
                H2JsonUsersTable.selectAll()
                    .applyFiltersOn(H2JsonUsersTable, filter)
                    .toList()
            }
        }
    }

    @Test
    fun `throws dialect error for strict date and datetime comparisons on h2`() {
        val dateFilter = FilterRequest(
            FilterLeaf(
                listOf(
                    FieldFilter("payload.profile.joined", FilterOperator.GTE, listOf("2024-02-01"))
                )
            )
        )
        val datetimeFilter = FilterRequest(
            FilterLeaf(
                listOf(
                    FieldFilter("payload.profile.lastSeen", FilterOperator.GT, listOf("2024-02-01T00:00:00"))
                )
            )
        )

        assertThrows(UnsupportedByDialectException::class.java) {
            transaction {
                H2JsonUsersTable.selectAll()
                    .applyFiltersOn(H2JsonUsersTable, dateFilter)
                    .toList()
            }
        }

        assertThrows(UnsupportedByDialectException::class.java) {
            transaction {
                H2JsonUsersTable.selectAll()
                    .applyFiltersOn(H2JsonUsersTable, datetimeFilter)
                    .toList()
            }
        }
    }

    private fun declaredFilterFails(type: JsonValueType, operator: FilterOperator, values: List<String>): String? {
        val filter = FilterRequest(FilterLeaf(listOf(FieldFilter("payload.profile.age", operator, values))))
        val options = FilterOptions(jsonPathType = { type })
        return runCatching {
            transaction { H2JsonUsersTable.selectAll().applyFiltersOn(H2JsonUsersTable, filter, options) }
        }.exceptionOrNull()?.let {
            assertTrue(it is IllegalArgumentException, "expected IllegalArgumentException, got $it")
            it.message
        }
    }

    @Test
    fun `rejects a value that does not parse as the declared json type before sql is built`() {
        assertEquals(
            "Value 'abc' for JSON field 'payload.profile.age' is not a valid NUMBER",
            declaredFilterFails(JsonValueType.NUMBER, FilterOperator.EQ, listOf("abc")),
        )
        assertNotNull(declaredFilterFails(JsonValueType.BOOLEAN, FilterOperator.EQ, listOf("yes")))
        assertNotNull(declaredFilterFails(JsonValueType.DATE, FilterOperator.GT, listOf("n/a")))
        assertNotNull(declaredFilterFails(JsonValueType.DATETIME, FilterOperator.LT, listOf("soon")))
        assertNotNull(declaredFilterFails(JsonValueType.NUMBER, FilterOperator.IN, listOf("12", "abc")))
        assertNotNull(declaredFilterFails(JsonValueType.NUMBER, FilterOperator.BETWEEN, listOf("1", "abc")))
    }

    @Test
    fun `accepts values that parse as the declared json type`() {
        assertNull(declaredFilterFails(JsonValueType.NUMBER, FilterOperator.EQ, listOf("12")))
        assertNull(declaredFilterFails(JsonValueType.DATETIME, FilterOperator.GTE, listOf("2024-01-01")))
    }

    @Test
    fun `declared string json type accepts like operators and mixed-looking values`() {
        assertNull(declaredFilterFails(JsonValueType.STRING, FilterOperator.CONTAINS, listOf("31")))
        assertNull(declaredFilterFails(JsonValueType.STRING, FilterOperator.IN, listOf("31", "text", "true")))
        assertNull(declaredFilterFails(JsonValueType.STRING, FilterOperator.BETWEEN, listOf("1", "z")))
    }

    @Test
    fun `declared non-string json type rejects like operators`() {
        assertNotNull(declaredFilterFails(JsonValueType.NUMBER, FilterOperator.STARTS_WITH, listOf("3")))
    }

    @Test
    fun `declared boolean json type rejects range operators`() {
        val filter = FilterRequest(
            FilterLeaf(listOf(FieldFilter("payload.profile.active", FilterOperator.GT, listOf("true"))))
        )
        val options = FilterOptions(jsonPathType = { JsonValueType.BOOLEAN })
        assertThrows(IllegalStateException::class.java) {
            transaction { H2JsonUsersTable.selectAll().applyFiltersOn(H2JsonUsersTable, filter, options) }
        }
    }
}
