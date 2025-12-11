@file:OptIn(kotlin.time.ExperimentalTime::class)

package ua.wwind.exposed.filters.jdbc

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.UUIDColumnType

import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ua.wwind.exposed.filters.core.FieldFilter
import ua.wwind.exposed.filters.core.FilterCombinator
import ua.wwind.exposed.filters.core.FilterGroup
import ua.wwind.exposed.filters.core.FilterLeaf
import ua.wwind.exposed.filters.core.FilterOperator
import ua.wwind.exposed.filters.core.FilterRequest
import java.util.UUID
import kotlin.time.Instant

// Test tables

object TestUsersTable : Table("test_users") {
    val id: Column<Int> = integer("id").autoIncrement()
    val name: Column<String> = varchar("name", 100)
    val age: Column<Int> = integer("age")
    val active: Column<Boolean> = bool("active")
    val score: Column<Double> = double("score")
    val email: Column<String?> = varchar("email", 100).nullable()
    override val primaryKey = PrimaryKey(id)
}

object TestProductsTable : Table("test_products") {
    val id: Column<UUID> = uuid("id")
    val title: Column<String> = varchar("title", 120)
    val price: Column<Long> = long("price")
    val category: Column<String> = varchar("category", 50)
    override val primaryKey = PrimaryKey(id)
}

@JvmInline
value class CustomId(val value: UUID)

object CustomIdColumnType : ColumnType<CustomId>() {
    private val delegate = UUIDColumnType()
    override fun sqlType(): String = delegate.sqlType()
    override fun nonNullValueToString(value: CustomId): String = delegate.nonNullValueToString(value.value)
    override fun notNullValueToDB(value: CustomId): Any = delegate.notNullValueToDB(value.value)
    override fun valueFromDB(value: Any): CustomId = when (value) {
        is CustomId -> value
        is UUID -> CustomId(value)
        is String -> CustomId(UUID.fromString(value))
        else -> error("Cannot convert ${value::class.simpleName} to CustomId")
    }
}

fun Table.customId(name: String): Column<CustomId> = registerColumn(name, CustomIdColumnType)

object TestCustomTable : Table("test_custom") {
    val id: Column<CustomId> = customId("id")
    val name: Column<String> = varchar("name", 100)
    override val primaryKey = PrimaryKey(id)
}

object TestEventsTable : Table("test_events") {
    val id: Column<Int> = integer("id").autoIncrement()
    val title: Column<String> = varchar("title", 100)
    val eventDay = date("event_day")
    val occurredAt = timestamp("occurred_at")
    override val primaryKey = PrimaryKey(id)
}

enum class Status { ACTIVE, INACTIVE, PENDING }

object TestEnumTable : Table("test_enum") {
    val id: Column<Int> = integer("id").autoIncrement()
    val status: Column<Status> = enumerationByName<Status>("status", 20)
    override val primaryKey = PrimaryKey(id)
}

object TestShortTable : Table("test_short") {
    val code: Column<Short> = short("code")
    val name: Column<String> = varchar("name", 100)
    override val primaryKey = PrimaryKey(code)
}

class QueryFilterExtensionsTest {

    @BeforeEach
    fun setUp() {
        Database.connect(
            url = "jdbc:h2:mem:test_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
    }

    @AfterEach
    fun tearDown() {
        // No explicit cleanup needed for in-memory DB
    }

    // ---------------------------------------------------------
    // Tests for Table.propertyToColumnMap()
    // ---------------------------------------------------------
    @Nested
    inner class PropertyToColumnMapTests {

        @Test
        fun `propertyToColumnMap returns all columns mapped by property names`() {
            val columnMap = TestUsersTable.propertyToColumnMap()

            // 6 columns: id, name, age, active, score, email
            assertTrue(columnMap.size >= 6, "Expected at least 6 columns, got ${columnMap.size}")
            assertTrue(columnMap.containsKey("id"))
            assertTrue(columnMap.containsKey("name"))
            assertTrue(columnMap.containsKey("age"))
            assertTrue(columnMap.containsKey("active"))
            assertTrue(columnMap.containsKey("score"))
            assertTrue(columnMap.containsKey("email"))
        }

        @Test
        fun `propertyToColumnMap returns correct column instances`() {
            val columnMap = TestUsersTable.propertyToColumnMap()

            assertEquals(TestUsersTable.name, columnMap["name"])
            assertEquals(TestUsersTable.age, columnMap["age"])
            assertEquals(TestUsersTable.active, columnMap["active"])
        }
    }

    // ---------------------------------------------------------
    // Tests for applyFiltersOn(Table, FilterRequest)
    // ---------------------------------------------------------
    @Nested
    inner class ApplyFiltersOnTableTests {

        @BeforeEach
        fun setUpData() {
            transaction {
                SchemaUtils.create(TestUsersTable)
                listOf(
                    Triple("Alice", 25, true),
                    Triple("Bob", 30, true),
                    Triple("Charlie", 35, false),
                    Triple("Diana", 28, true),
                    Triple("Eve", 22, false)
                ).forEach { (name, age, active) ->
                    TestUsersTable.insert {
                        it[TestUsersTable.name] = name
                        it[TestUsersTable.age] = age
                        it[TestUsersTable.active] = active
                        it[TestUsersTable.score] = age.toDouble() * 1.5
                        it[TestUsersTable.email] = "$name@example.com".lowercase()
                    }
                }
            }
        }

        @AfterEach
        fun cleanUp() {
            transaction { SchemaUtils.drop(TestUsersTable) }
        }

        @Test
        fun `applyFiltersOn returns all rows when filterRequest is null`() {
            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, null)
                    .toList()

                assertEquals(5, results.size)
            }
        }

        @Test
        fun `applyFiltersOn with EQ operator filters correctly`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("name", FilterOperator.EQ, listOf("Alice"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .toList()

                assertEquals(1, results.size)
                assertEquals("Alice", results.first()[TestUsersTable.name])
            }
        }

        @Test
        fun `applyFiltersOn with NEQ operator filters correctly`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("name", FilterOperator.NEQ, listOf("Alice"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .toList()

                assertEquals(4, results.size)
                assertTrue(results.none { it[TestUsersTable.name] == "Alice" })
            }
        }

        @Test
        fun `applyFiltersOn with CONTAINS operator filters strings`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("name", FilterOperator.CONTAINS, listOf("li"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }

                assertEquals(2, results.size)
                assertTrue(results.contains("Alice"))
                assertTrue(results.contains("Charlie"))
            }
        }

        @Test
        fun `applyFiltersOn with STARTS_WITH operator filters strings`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("name", FilterOperator.STARTS_WITH, listOf("A"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }

                assertEquals(1, results.size)
                assertEquals("Alice", results.first())
            }
        }

        @Test
        fun `applyFiltersOn with ENDS_WITH operator filters strings`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("name", FilterOperator.ENDS_WITH, listOf("e"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }

                assertEquals(3, results.size)
                assertTrue(results.containsAll(listOf("Alice", "Charlie", "Eve")))
            }
        }

        @Test
        fun `applyFiltersOn with GT operator on integer column`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("age", FilterOperator.GT, listOf("28"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Bob", "Charlie"), results)
            }
        }

        @Test
        fun `applyFiltersOn with GTE operator on integer column`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("age", FilterOperator.GTE, listOf("28"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Bob", "Charlie", "Diana"), results)
            }
        }

        @Test
        fun `applyFiltersOn with LT operator on integer column`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("age", FilterOperator.LT, listOf("25"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }

                assertEquals(listOf("Eve"), results)
            }
        }

        @Test
        fun `applyFiltersOn with LTE operator on integer column`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("age", FilterOperator.LTE, listOf("25"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Alice", "Eve"), results)
            }
        }

        @Test
        fun `applyFiltersOn with IN operator filters multiple values`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("name", FilterOperator.IN, listOf("Alice", "Bob", "NonExistent"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Alice", "Bob"), results)
            }
        }

        @Test
        fun `applyFiltersOn with IN empty values returns empty result`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("name", FilterOperator.IN, emptyList())))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .toList()

                assertEquals(0, results.size)
            }
        }

        @Test
        fun `applyFiltersOn with NOT_IN operator excludes values`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("name", FilterOperator.NOT_IN, listOf("Alice", "Bob"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Charlie", "Diana", "Eve"), results)
            }
        }

        @Test
        fun `applyFiltersOn with NOT_IN empty values returns all rows`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("name", FilterOperator.NOT_IN, emptyList())))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .toList()

                assertEquals(5, results.size)
            }
        }

        @Test
        fun `applyFiltersOn with BETWEEN operator on integer column`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("age", FilterOperator.BETWEEN, listOf("25", "30"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Alice", "Bob", "Diana"), results)
            }
        }

        @Test
        fun `applyFiltersOn with BETWEEN empty values returns empty result`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("age", FilterOperator.BETWEEN, emptyList())))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .toList()

                assertEquals(0, results.size)
            }
        }

        @Test
        fun `applyFiltersOn with Boolean column EQ true`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("active", FilterOperator.EQ, listOf("true"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Alice", "Bob", "Diana"), results)
            }
        }

        @Test
        fun `applyFiltersOn with Boolean column EQ false`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("active", FilterOperator.EQ, listOf("false"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Charlie", "Eve"), results)
            }
        }

        @Test
        fun `applyFiltersOn with Double column GT`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("score", FilterOperator.GT, listOf("40.0"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Bob", "Charlie", "Diana"), results)
            }
        }

        @Test
        fun `applyFiltersOn throws exception for unknown field`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("unknownField", FilterOperator.EQ, listOf("value"))))
            )

            transaction {
                assertThrows(IllegalArgumentException::class.java) {
                    TestUsersTable.selectAll()
                        .applyFiltersOn(TestUsersTable, filter)
                        .toList()
                }
            }
        }
    }

    // ---------------------------------------------------------
    // Tests for applyFiltersOn with FilterGroup (AND, OR, NOT)
    // ---------------------------------------------------------
    @Nested
    inner class FilterGroupTests {

        @BeforeEach
        fun setUpData() {
            transaction {
                SchemaUtils.create(TestUsersTable)
                listOf(
                    Triple("Alice", 25, true),
                    Triple("Bob", 30, true),
                    Triple("Charlie", 35, false),
                    Triple("Diana", 28, true),
                    Triple("Eve", 22, false)
                ).forEach { (name, age, active) ->
                    TestUsersTable.insert {
                        it[TestUsersTable.name] = name
                        it[TestUsersTable.age] = age
                        it[TestUsersTable.active] = active
                        it[TestUsersTable.score] = age.toDouble() * 1.5
                        it[TestUsersTable.email] = "$name@example.com".lowercase()
                    }
                }
            }
        }

        @AfterEach
        fun cleanUp() {
            transaction { SchemaUtils.drop(TestUsersTable) }
        }

        @Test
        fun `applyFiltersOn with AND combinator combines filters`() {
            val filter = FilterRequest(
                FilterGroup(
                    FilterCombinator.AND,
                    listOf(
                        FilterLeaf(listOf(FieldFilter("active", FilterOperator.EQ, listOf("true")))),
                        FilterLeaf(listOf(FieldFilter("age", FilterOperator.GTE, listOf("28"))))
                    )
                )
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Bob", "Diana"), results)
            }
        }

        @Test
        fun `applyFiltersOn with OR combinator combines filters`() {
            val filter = FilterRequest(
                FilterGroup(
                    FilterCombinator.OR,
                    listOf(
                        FilterLeaf(listOf(FieldFilter("name", FilterOperator.EQ, listOf("Alice")))),
                        FilterLeaf(listOf(FieldFilter("name", FilterOperator.EQ, listOf("Bob"))))
                    )
                )
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Alice", "Bob"), results)
            }
        }

        @Test
        fun `applyFiltersOn with NOT combinator negates filter`() {
            val filter = FilterRequest(
                FilterGroup(
                    FilterCombinator.NOT,
                    listOf(
                        FilterLeaf(listOf(FieldFilter("active", FilterOperator.EQ, listOf("true"))))
                    )
                )
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Charlie", "Eve"), results)
            }
        }

        @Test
        fun `applyFiltersOn with nested groups`() {
            // NOT (age >= 30 OR name starts with 'A')
            val filter = FilterRequest(
                FilterGroup(
                    FilterCombinator.NOT,
                    listOf(
                        FilterGroup(
                            FilterCombinator.OR,
                            listOf(
                                FilterLeaf(listOf(FieldFilter("age", FilterOperator.GTE, listOf("30")))),
                                FilterLeaf(listOf(FieldFilter("name", FilterOperator.STARTS_WITH, listOf("A"))))
                            )
                        )
                    )
                )
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Diana", "Eve"), results)
            }
        }

        @Test
        fun `applyFiltersOn with multiple predicates in FilterLeaf uses AND`() {
            val filter = FilterRequest(
                FilterLeaf(
                    listOf(
                        FieldFilter("age", FilterOperator.GTE, listOf("25")),
                        FieldFilter("age", FilterOperator.LTE, listOf("30"))
                    )
                )
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Alice", "Bob", "Diana"), results)
            }
        }

        @Test
        fun `applyFiltersOn with empty FilterLeaf returns all rows`() {
            val filter = FilterRequest(FilterLeaf(emptyList()))

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .toList()

                assertEquals(5, results.size)
            }
        }

        @Test
        fun `applyFiltersOn with empty FilterGroup returns all rows`() {
            val filter = FilterRequest(FilterGroup(FilterCombinator.AND, emptyList()))

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .toList()

                assertEquals(5, results.size)
            }
        }
    }

    // ---------------------------------------------------------
    // Tests for applyFilters with custom column map
    // ---------------------------------------------------------
    @Nested
    inner class ApplyFiltersWithCustomMapTests {

        @BeforeEach
        fun setUpData() {
            transaction {
                SchemaUtils.create(TestUsersTable)
                listOf(
                    Triple("Alice", 25, true),
                    Triple("Bob", 30, true),
                    Triple("Charlie", 35, false)
                ).forEach { (name, age, active) ->
                    TestUsersTable.insert {
                        it[TestUsersTable.name] = name
                        it[TestUsersTable.age] = age
                        it[TestUsersTable.active] = active
                        it[TestUsersTable.score] = age.toDouble() * 1.5
                        it[TestUsersTable.email] = null
                    }
                }
            }
        }

        @AfterEach
        fun cleanUp() {
            transaction { SchemaUtils.drop(TestUsersTable) }
        }

        @Test
        fun `applyFilters with custom column map allows aliased field names`() {
            val columns = mapOf(
                "userName" to TestUsersTable.name,
                "userAge" to TestUsersTable.age
            )

            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("userName", FilterOperator.EQ, listOf("Alice"))))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFilters(columns, filter)
                    .toList()

                assertEquals(1, results.size)
                assertEquals("Alice", results.first()[TestUsersTable.name])
            }
        }

        @Test
        fun `applyFilters returns all rows when filterRequest is null`() {
            val columns = mapOf("name" to TestUsersTable.name)

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFilters(columns, null)
                    .toList()

                assertEquals(3, results.size)
            }
        }
    }

    // ---------------------------------------------------------
    // Tests for IS_NULL and IS_NOT_NULL operators
    // ---------------------------------------------------------
    @Nested
    inner class NullOperatorTests {

        @BeforeEach
        fun setUpData() {
            transaction {
                SchemaUtils.create(TestUsersTable)
                TestUsersTable.insert {
                    it[name] = "Alice"
                    it[age] = 25
                    it[active] = true
                    it[score] = 37.5
                    it[email] = "alice@example.com"
                }
                TestUsersTable.insert {
                    it[name] = "Bob"
                    it[age] = 30
                    it[active] = true
                    it[score] = 45.0
                    it[email] = null
                }
                TestUsersTable.insert {
                    it[name] = "Charlie"
                    it[age] = 35
                    it[active] = false
                    it[score] = 52.5
                    it[email] = null
                }
            }
        }

        @AfterEach
        fun cleanUp() {
            transaction { SchemaUtils.drop(TestUsersTable) }
        }

        @Test
        fun `applyFiltersOn with IS_NULL filters null values`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("email", FilterOperator.IS_NULL, emptyList())))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }
                    .sorted()

                assertEquals(listOf("Bob", "Charlie"), results)
            }
        }

        @Test
        fun `applyFiltersOn with IS_NOT_NULL filters non-null values`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("email", FilterOperator.IS_NOT_NULL, emptyList())))
            )

            transaction {
                val results = TestUsersTable.selectAll()
                    .applyFiltersOn(TestUsersTable, filter)
                    .map { it[TestUsersTable.name] }

                assertEquals(listOf("Alice"), results)
            }
        }
    }

    // ---------------------------------------------------------
    // Tests for UUID columns
    // ---------------------------------------------------------
    @Nested
    inner class UUIDColumnTests {

        private val uuid1 = UUID.fromString("550e8400-e29b-41d4-a716-446655440001")
        private val uuid2 = UUID.fromString("550e8400-e29b-41d4-a716-446655440002")
        private val uuid3 = UUID.fromString("550e8400-e29b-41d4-a716-446655440003")

        @BeforeEach
        fun setUpData() {
            transaction {
                SchemaUtils.create(TestProductsTable)
                listOf(
                    Triple(uuid1, "Product A", 100L),
                    Triple(uuid2, "Product B", 200L),
                    Triple(uuid3, "Product C", 300L)
                ).forEach { (id, title, price) ->
                    TestProductsTable.insert {
                        it[TestProductsTable.id] = id
                        it[TestProductsTable.title] = title
                        it[TestProductsTable.price] = price
                        it[TestProductsTable.category] = "Test"
                    }
                }
            }
        }

        @AfterEach
        fun cleanUp() {
            transaction { SchemaUtils.drop(TestProductsTable) }
        }

        @Test
        fun `applyFiltersOn with UUID EQ operator`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("id", FilterOperator.EQ, listOf(uuid1.toString()))))
            )

            transaction {
                val results = TestProductsTable.selectAll()
                    .applyFiltersOn(TestProductsTable, filter)
                    .map { it[TestProductsTable.title] }

                assertEquals(listOf("Product A"), results)
            }
        }

        @Test
        fun `applyFiltersOn with UUID IN operator`() {
            val filter = FilterRequest(
                FilterLeaf(
                    listOf(
                        FieldFilter(
                            "id",
                            FilterOperator.IN,
                            listOf(uuid1.toString(), uuid2.toString())
                        )
                    )
                )
            )

            transaction {
                val results = TestProductsTable.selectAll()
                    .applyFiltersOn(TestProductsTable, filter)
                    .map { it[TestProductsTable.title] }
                    .sorted()

                assertEquals(listOf("Product A", "Product B"), results)
            }
        }

        @Test
        fun `applyFiltersOn with Long column comparison`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("price", FilterOperator.GTE, listOf("200"))))
            )

            transaction {
                val results = TestProductsTable.selectAll()
                    .applyFiltersOn(TestProductsTable, filter)
                    .map { it[TestProductsTable.title] }
                    .sorted()

                assertEquals(listOf("Product B", "Product C"), results)
            }
        }
    }

    // ---------------------------------------------------------
    // Tests for applyFiltersOn with ColumnMappersModule
    // ---------------------------------------------------------
    @Nested
    inner class CustomMapperTests {

        private val customId1 = CustomId(UUID.fromString("550e8400-e29b-41d4-a716-446655440001"))
        private val customId2 = CustomId(UUID.fromString("550e8400-e29b-41d4-a716-446655440002"))

        @BeforeEach
        fun setUpData() {
            transaction {
                SchemaUtils.create(TestCustomTable)
                TestCustomTable.insert {
                    it[id] = customId1
                    it[name] = "Custom A"
                }
                TestCustomTable.insert {
                    it[id] = customId2
                    it[name] = "Custom B"
                }
            }
        }

        @AfterEach
        fun cleanUp() {
            transaction { SchemaUtils.drop(TestCustomTable) }
        }

        @Test
        fun `applyFiltersOn with custom mapper handles custom column type`() {
            val mappers = columnMappers {
                mapper { columnType, raw ->
                    when (columnType) {
                        is CustomIdColumnType -> CustomId(UUID.fromString(raw))
                        else -> null
                    }
                }
            }

            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("id", FilterOperator.EQ, listOf(customId1.value.toString()))))
            )

            transaction {
                val results = TestCustomTable.selectAll()
                    .applyFiltersOn(TestCustomTable, filter, mappers)
                    .map { it[TestCustomTable.name] }

                assertEquals(listOf("Custom A"), results)
            }
        }

        @Test
        fun `applyFiltersOn with custom mapper handles IN operator`() {
            val mappers = columnMappers {
                mapper { columnType, raw ->
                    when (columnType) {
                        is CustomIdColumnType -> CustomId(UUID.fromString(raw))
                        else -> null
                    }
                }
            }

            val filter = FilterRequest(
                FilterLeaf(
                    listOf(
                        FieldFilter(
                            "id",
                            FilterOperator.IN,
                            listOf(customId1.value.toString(), customId2.value.toString())
                        )
                    )
                )
            )

            transaction {
                val results = TestCustomTable.selectAll()
                    .applyFiltersOn(TestCustomTable, filter, mappers)
                    .map { it[TestCustomTable.name] }
                    .sorted()

                assertEquals(listOf("Custom A", "Custom B"), results)
            }
        }
    }

    // ---------------------------------------------------------
    // Tests for Date and Timestamp columns
    // ---------------------------------------------------------
    @Nested
    inner class DateTimeColumnTests {

        @BeforeEach
        fun setUpData() {
            transaction {
                SchemaUtils.create(TestEventsTable)
                listOf(
                    Triple("New Year", LocalDate(2024, 1, 1), Instant.fromEpochMilliseconds(1704067200000)),
                    Triple("Spring Fest", LocalDate(2024, 4, 1), Instant.fromEpochMilliseconds(1711929600000)),
                    Triple("Summer Gala", LocalDate(2024, 7, 1), Instant.fromEpochMilliseconds(1719792000000)),
                    Triple("Winter Meet", LocalDate(2024, 12, 1), Instant.fromEpochMilliseconds(1733011200000))
                ).forEach { (title, day, ts) ->
                    TestEventsTable.insert {
                        it[TestEventsTable.title] = title
                        it[TestEventsTable.eventDay] = day
                        it[TestEventsTable.occurredAt] = ts
                    }
                }
            }
        }

        @AfterEach
        fun cleanUp() {
            transaction { SchemaUtils.drop(TestEventsTable) }
        }

        @Test
        fun `applyFiltersOn with LocalDate EQ operator`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("eventDay", FilterOperator.EQ, listOf("2024-01-01"))))
            )

            transaction {
                val results = TestEventsTable.selectAll()
                    .applyFiltersOn(TestEventsTable, filter)
                    .map { it[TestEventsTable.title] }

                assertEquals(listOf("New Year"), results)
            }
        }

        @Test
        fun `applyFiltersOn with LocalDate GTE operator`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("eventDay", FilterOperator.GTE, listOf("2024-07-01"))))
            )

            transaction {
                val results = TestEventsTable.selectAll()
                    .applyFiltersOn(TestEventsTable, filter)
                    .map { it[TestEventsTable.title] }
                    .sorted()

                assertEquals(listOf("Summer Gala", "Winter Meet"), results)
            }
        }

        @Test
        fun `applyFiltersOn with LocalDate BETWEEN operator`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("eventDay", FilterOperator.BETWEEN, listOf("2024-03-01", "2024-08-01"))))
            )

            transaction {
                val results = TestEventsTable.selectAll()
                    .applyFiltersOn(TestEventsTable, filter)
                    .map { it[TestEventsTable.title] }
                    .sorted()

                assertEquals(listOf("Spring Fest", "Summer Gala"), results)
            }
        }

        @Test
        fun `applyFiltersOn with LocalDate IN operator`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("eventDay", FilterOperator.IN, listOf("2024-01-01", "2024-12-01"))))
            )

            transaction {
                val results = TestEventsTable.selectAll()
                    .applyFiltersOn(TestEventsTable, filter)
                    .map { it[TestEventsTable.title] }
                    .sorted()

                assertEquals(listOf("New Year", "Winter Meet"), results)
            }
        }

        @Test
        fun `applyFiltersOn with Instant GTE operator`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("occurredAt", FilterOperator.GTE, listOf("2024-07-01T00:00:00"))))
            )

            transaction {
                val results = TestEventsTable.selectAll()
                    .applyFiltersOn(TestEventsTable, filter)
                    .map { it[TestEventsTable.title] }
                    .sorted()

                assertEquals(listOf("Summer Gala", "Winter Meet"), results)
            }
        }

        @Test
        fun `applyFiltersOn with Instant LT operator`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("occurredAt", FilterOperator.LT, listOf("2024-04-01T00:00:00"))))
            )

            transaction {
                val results = TestEventsTable.selectAll()
                    .applyFiltersOn(TestEventsTable, filter)
                    .map { it[TestEventsTable.title] }

                assertEquals(listOf("New Year"), results)
            }
        }
    }

    // ---------------------------------------------------------
    // Tests for Enumeration columns
    // ---------------------------------------------------------
    @Nested
    inner class EnumColumnTests {

        @BeforeEach
        fun setUpData() {
            transaction {
                SchemaUtils.create(TestEnumTable)
                listOf(Status.ACTIVE, Status.ACTIVE, Status.INACTIVE, Status.PENDING).forEach { status ->
                    TestEnumTable.insert {
                        it[TestEnumTable.status] = status
                    }
                }
            }
        }

        @AfterEach
        fun cleanUp() {
            transaction { SchemaUtils.drop(TestEnumTable) }
        }

        @Test
        fun `applyFiltersOn with enum EQ operator`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("status", FilterOperator.EQ, listOf("ACTIVE"))))
            )

            transaction {
                val results = TestEnumTable.selectAll()
                    .applyFiltersOn(TestEnumTable, filter)
                    .toList()

                assertEquals(2, results.size)
                assertTrue(results.all { it[TestEnumTable.status] == Status.ACTIVE })
            }
        }

        @Test
        fun `applyFiltersOn with enum IN operator`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("status", FilterOperator.IN, listOf("ACTIVE", "PENDING"))))
            )

            transaction {
                val results = TestEnumTable.selectAll()
                    .applyFiltersOn(TestEnumTable, filter)
                    .toList()

                assertEquals(3, results.size)
            }
        }

        @Test
        fun `applyFiltersOn with enum NEQ operator`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("status", FilterOperator.NEQ, listOf("INACTIVE"))))
            )

            transaction {
                val results = TestEnumTable.selectAll()
                    .applyFiltersOn(TestEnumTable, filter)
                    .toList()

                assertEquals(3, results.size)
                assertTrue(results.none { it[TestEnumTable.status] == Status.INACTIVE })
            }
        }
    }

    // ---------------------------------------------------------
    // Tests for Short column type
    // ---------------------------------------------------------
    @Nested
    inner class ShortColumnTests {

        @BeforeEach
        fun setUpData() {
            transaction {
                SchemaUtils.create(TestShortTable)
                listOf(
                    1.toShort() to "First",
                    2.toShort() to "Second",
                    3.toShort() to "Third"
                ).forEach { (code, name) ->
                    TestShortTable.insert {
                        it[TestShortTable.code] = code
                        it[TestShortTable.name] = name
                    }
                }
            }
        }

        @AfterEach
        fun cleanUp() {
            transaction { SchemaUtils.drop(TestShortTable) }
        }

        @Test
        fun `applyFiltersOn with Short EQ operator`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("code", FilterOperator.EQ, listOf("2"))))
            )

            transaction {
                val results = TestShortTable.selectAll()
                    .applyFiltersOn(TestShortTable, filter)
                    .map { it[TestShortTable.name] }

                assertEquals(listOf("Second"), results)
            }
        }

        @Test
        fun `applyFiltersOn with Short IN operator`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("code", FilterOperator.IN, listOf("1", "3"))))
            )

            transaction {
                val results = TestShortTable.selectAll()
                    .applyFiltersOn(TestShortTable, filter)
                    .map { it[TestShortTable.name] }
                    .sorted()

                assertEquals(listOf("First", "Third"), results)
            }
        }

        @Test
        fun `applyFiltersOn with Short BETWEEN operator`() {
            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("code", FilterOperator.BETWEEN, listOf("1", "2"))))
            )

            transaction {
                val results = TestShortTable.selectAll()
                    .applyFiltersOn(TestShortTable, filter)
                    .map { it[TestShortTable.name] }
                    .sorted()

                assertEquals(listOf("First", "Second"), results)
            }
        }

        @Test
        fun `applyFiltersOn with Short comparison operators`() {
            val gtFilter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("code", FilterOperator.GT, listOf("1"))))
            )

            transaction {
                val results = TestShortTable.selectAll()
                    .applyFiltersOn(TestShortTable, gtFilter)
                    .map { it[TestShortTable.name] }
                    .sorted()

                assertEquals(listOf("Second", "Third"), results)
            }
        }
    }

    // ---------------------------------------------------------
    // Tests for Join queries (uses SQL column names)
    // ---------------------------------------------------------
    @Nested
    inner class JoinQueryTests {

        @BeforeEach
        fun setUpData() {
            transaction {
                SchemaUtils.create(TestUsersTable, TestProductsTable)
                // Insert users
                listOf(
                    Triple("Alice", 25, true),
                    Triple("Bob", 30, true)
                ).forEach { (name, age, active) ->
                    TestUsersTable.insert {
                        it[TestUsersTable.name] = name
                        it[TestUsersTable.age] = age
                        it[TestUsersTable.active] = active
                        it[TestUsersTable.score] = 0.0
                        it[TestUsersTable.email] = null
                    }
                }
                // Insert products
                listOf(
                    Triple(UUID.randomUUID(), "Product A", 100L),
                    Triple(UUID.randomUUID(), "Product B", 200L)
                ).forEach { (id, title, price) ->
                    TestProductsTable.insert {
                        it[TestProductsTable.id] = id
                        it[TestProductsTable.title] = title
                        it[TestProductsTable.price] = price
                        it[TestProductsTable.category] = "Test"
                    }
                }
            }
        }

        @AfterEach
        fun cleanUp() {
            transaction { SchemaUtils.drop(TestUsersTable, TestProductsTable) }
        }

        @Test
        fun `applyFiltersOn with cross join uses SQL column names`() {
            // When using joins, field names should match SQL column names
            val join = TestUsersTable.crossJoin(TestProductsTable)

            val filter = FilterRequest(
                FilterLeaf(listOf(FieldFilter("name", FilterOperator.EQ, listOf("Alice"))))
            )

            transaction {
                val results = join.selectAll()
                    .applyFiltersOn(join, filter)
                    .toList()

                assertEquals(2, results.size) // 1 user * 2 products
                assertTrue(results.all { it[TestUsersTable.name] == "Alice" })
            }
        }
    }
}
