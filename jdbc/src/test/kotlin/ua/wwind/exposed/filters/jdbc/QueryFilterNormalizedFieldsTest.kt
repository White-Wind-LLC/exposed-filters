package ua.wwind.exposed.filters.jdbc

import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterOperator
import ua.wwind.exposed.filters.core.FilterRequest

private object TestUsersNormTable : Table("test_users_norm") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 100)
    val email = varchar("email", 100)
    override val primaryKey = PrimaryKey(id)
}


class QueryFilterNormalizedFieldsTest {

    @BeforeEach
    fun setUp() {
        Database.connect(
            url = "jdbc:h2:mem:test_norm_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
    }

    @BeforeEach
    fun setUpData() {
        transaction {
            SchemaUtils.create(TestUsersNormTable)
            listOf("Alice", "Bob", "Charlie", "Diana", "Eve").forEach { name ->
                TestUsersNormTable.insert {
                    it[TestUsersNormTable.name] = name
                    it[TestUsersNormTable.email] = "$name@example.com".lowercase()
                }
            }
        }
    }

    @AfterEach
    fun cleanUp() {
        transaction { SchemaUtils.drop(TestUsersNormTable) }
    }

    private val normalized = FilterOptions(normalizedStringFields = setOf("email"))

    @Test
    fun `EQ on normalized field lowercases the value and keeps the column raw`() {
        val filter = FilterRequest(
            FilterLeaf(listOf(FieldFilter("email", FilterOperator.EQ, listOf("ALICE@EXAMPLE.COM")))),
        )
        transaction {
            val query = TestUsersNormTable.selectAll().applyFiltersOn(TestUsersNormTable, filter, normalized)
            val sql = query.prepareSQL(QueryBuilder(false))
            assertFalse(sql.contains("LOWER", ignoreCase = true), "no LOWER() expected on a normalized field: $sql")
            assertEquals(listOf("alice@example.com"), query.map { it[TestUsersNormTable.email] })
        }
    }

    @Test
    fun `EQ on non-normalized field still wraps the column in LOWER`() {
        val filter = FilterRequest(
            FilterLeaf(listOf(FieldFilter("name", FilterOperator.EQ, listOf("ALICE")))),
        )
        transaction {
            val query = TestUsersNormTable.selectAll().applyFiltersOn(TestUsersNormTable, filter, normalized)
            val sql = query.prepareSQL(QueryBuilder(false))
            assertTrue(sql.contains("LOWER", ignoreCase = true), "non-normalized field keeps LOWER(): $sql")
            assertEquals(listOf("Alice"), query.map { it[TestUsersNormTable.name] })
        }
    }

    @Test
    fun `IN on normalized field matches case-insensitively without LOWER`() {
        val filter = FilterRequest(
            FilterLeaf(listOf(FieldFilter("email", FilterOperator.IN, listOf("ALICE@EXAMPLE.COM", "Bob@Example.Com")))),
        )
        transaction {
            val query = TestUsersNormTable.selectAll().applyFiltersOn(TestUsersNormTable, filter, normalized)
            val sql = query.prepareSQL(QueryBuilder(false))
            assertFalse(sql.contains("LOWER", ignoreCase = true), sql)
            assertEquals(listOf("alice@example.com", "bob@example.com"), query.map { it[TestUsersNormTable.email] }.sorted())
        }
    }

    @Test
    fun `STARTS_WITH on normalized field lowercases the pattern without LOWER`() {
        val filter = FilterRequest(
            FilterLeaf(listOf(FieldFilter("email", FilterOperator.STARTS_WITH, listOf("ALICE")))),
        )
        transaction {
            val query = TestUsersNormTable.selectAll().applyFiltersOn(TestUsersNormTable, filter, normalized)
            val sql = query.prepareSQL(QueryBuilder(false))
            assertFalse(sql.contains("LOWER", ignoreCase = true), sql)
            assertEquals(listOf("alice@example.com"), query.map { it[TestUsersNormTable.email] })
        }
    }

    @Test
    fun `caseSensitiveStrings=true wins over normalizedStringFields`() {
        val filter = FilterRequest(
            FilterLeaf(listOf(FieldFilter("email", FilterOperator.EQ, listOf("ALICE@EXAMPLE.COM")))),
        )
        transaction {
            val rows = TestUsersNormTable.selectAll()
                .applyFiltersOn(
                    TestUsersNormTable,
                    filter,
                    FilterOptions(caseSensitiveStrings = true, normalizedStringFields = setOf("email")),
                ).toList()
            assertTrue(rows.isEmpty(), "case-sensitive mode must compare raw values")
        }
    }

    // Finding 2: remaining operators on a normalized field must produce no LOWER on the column side.
    enum class NormalizedOperatorCase(
        val operator: FilterOperator,
        val values: List<String>,
    ) {
        NEQ(FilterOperator.NEQ, listOf("alice@example.com")),
        NOT_IN(FilterOperator.NOT_IN, listOf("alice@example.com", "bob@example.com")),
        CONTAINS(FilterOperator.CONTAINS, listOf("example")),
        ENDS_WITH(FilterOperator.ENDS_WITH, listOf(".com")),
        BETWEEN(FilterOperator.BETWEEN, listOf("a@example.com", "z@example.com")),
        GT(FilterOperator.GT, listOf("a@example.com")),
        GTE(FilterOperator.GTE, listOf("a@example.com")),
        LT(FilterOperator.LT, listOf("z@example.com")),
        LTE(FilterOperator.LTE, listOf("z@example.com")),
    }

    @ParameterizedTest
    @EnumSource(NormalizedOperatorCase::class)
    fun `normalized field operators produce no LOWER on the column`(case: NormalizedOperatorCase) {
        val filter = FilterRequest(
            FilterLeaf(listOf(FieldFilter("email", case.operator, case.values))),
        )
        transaction {
            val query = TestUsersNormTable.selectAll().applyFiltersOn(TestUsersNormTable, filter, normalized)
            val sql = query.prepareSQL(QueryBuilder(false))
            assertFalse(
                sql.contains("LOWER", ignoreCase = true),
                "operator ${case.operator} on normalized field must not wrap column in LOWER(): $sql",
            )
        }
    }
}
