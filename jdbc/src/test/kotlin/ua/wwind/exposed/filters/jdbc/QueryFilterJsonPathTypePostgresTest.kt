package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.json.jsonb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterOperator
import ua.wwind.exposed.filters.core.FilterRequest

object PgJsonPathTypeItemsTable : Table("pg_json_path_type_items") {
    val id: Column<Int> = integer("id").autoIncrement()
    val name: Column<String> = varchar("name", 50)
    val attributes: Column<String> = jsonb("attributes", { it }, { it })
    override val primaryKey = PrimaryKey(id)
}

@Testcontainers(disabledWithoutDocker = true)
class QueryFilterJsonPathTypePostgresTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18-alpine")
    }

    private val declared = FilterOptions(
        jsonPathType = { field -> if (field == "attributes.weight") JsonValueType.NUMBER else JsonValueType.STRING },
    )

    @BeforeEach
    fun setUp() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password
        )
        transaction {
            SchemaUtils.create(PgJsonPathTypeItemsTable)
            PgJsonPathTypeItemsTable.insert {
                it[name] = "first"
                it[attributes] = """{"size":"12","code":"A-12-03","note":"true","day":"2026-10-14","weight":12}"""
            }
            PgJsonPathTypeItemsTable.insert {
                it[name] = "second"
                it[attributes] = """{"size":"12A","code":"B-07-01","note":"true story","day":"n/a","weight":7}"""
            }
            PgJsonPathTypeItemsTable.insert {
                it[name] = "third"
                it[attributes] = """{"size":"0012"}"""
            }
        }
    }

    @AfterEach
    fun tearDown() {
        transaction { SchemaUtils.drop(PgJsonPathTypeItemsTable) }
    }

    private fun names(field: String, operator: FilterOperator, values: List<String>, options: FilterOptions): List<String> {
        val filter = FilterRequest(FilterLeaf(listOf(FieldFilter(field, operator, values))))
        return transaction {
            PgJsonPathTypeItemsTable.selectAll()
                .applyFiltersOn(PgJsonPathTypeItemsTable, filter, options)
                .map { it[PgJsonPathTypeItemsTable.name] }
                .sorted()
        }
    }

    @Test
    fun `declared string EQ compares text whatever the value looks like`() {
        assertEquals(listOf("first"), names("attributes.size", FilterOperator.EQ, listOf("12"), declared))
        assertEquals(listOf("first"), names("attributes.note", FilterOperator.EQ, listOf("true"), declared))
        assertEquals(listOf("first"), names("attributes.day", FilterOperator.EQ, listOf("2026-10-14"), declared))
    }

    @Test
    fun `declared string LIKE operators accept digits`() {
        assertEquals(listOf("first"), names("attributes.code", FilterOperator.CONTAINS, listOf("12"), declared))
        assertEquals(listOf("third"), names("attributes.size", FilterOperator.STARTS_WITH, listOf("00"), declared))
    }

    @Test
    fun `declared string IN and NOT_IN compare text`() {
        assertEquals(
            listOf("first", "third"),
            names("attributes.size", FilterOperator.IN, listOf("12", "0012"), declared),
        )
        assertEquals(listOf("second"), names("attributes.size", FilterOperator.NOT_IN, listOf("12", "0012"), declared))
    }

    @Test
    fun `declared string range operators compare text`() {
        assertEquals(listOf("second"), names("attributes.size", FilterOperator.GT, listOf("12"), declared))
        assertEquals(
            listOf("first", "second"),
            names("attributes.size", FilterOperator.BETWEEN, listOf("1", "2"), declared),
        )
    }

    @Test
    fun `declared string renders no cast`() {
        val filter = FilterRequest(FilterLeaf(listOf(FieldFilter("attributes.size", FilterOperator.EQ, listOf("12")))))
        val sql = transaction {
            PgJsonPathTypeItemsTable.selectAll()
                .applyFiltersOn(PgJsonPathTypeItemsTable, filter, declared)
                .prepareSQL(QueryBuilder(false))
        }
        assertFalse(sql.contains("CAST", ignoreCase = true), sql)
    }

    @Test
    fun `declared number EQ casts as before`() {
        assertEquals(listOf("first"), names("attributes.weight", FilterOperator.EQ, listOf("12"), declared))
        assertEquals(listOf("first"), names("attributes.weight", FilterOperator.GT, listOf("10"), declared))
    }

    @Test
    fun `declared number rejects a value that does not parse before the query runs`() {
        val filter = FilterRequest(FilterLeaf(listOf(FieldFilter("attributes.weight", FilterOperator.EQ, listOf("abc")))))
        assertThrows(IllegalArgumentException::class.java) {
            transaction { PgJsonPathTypeItemsTable.selectAll().applyFiltersOn(PgJsonPathTypeItemsTable, filter, declared) }
        }
    }

    @Test
    fun `resolver returning null keeps the inferred type`() {
        val inferring = FilterOptions(jsonPathType = { null })
        assertThrows(ExposedSQLException::class.java) {
            names("attributes.size", FilterOperator.EQ, listOf("12"), inferring)
        }
        assertThrows(ExposedSQLException::class.java) {
            names("attributes.size", FilterOperator.EQ, listOf("12"), FilterOptions())
        }
    }
}
