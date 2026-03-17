package ua.wwind.exposed.filters.jdbc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.json.jsonb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import ua.wwind.exposed.filters.core.filterRequest

@Serializable
data class ProfileMeta(val tier: String)

@Serializable
data class UserProfile(
    val score: Int,
    val active: Boolean,
    val meta: ProfileMeta,
    val registeredAt: LocalDate,
    val lastLogin: LocalDateTime
)

@Serializable
data class UserPayload(val profile: UserProfile)

private val json = Json { encodeDefaults = true }

object PgJsonPathWarehousesTable : Table("pg_json_path_warehouses") {
    val id: Column<Int> = integer("id").autoIncrement()
    val name: Column<String> = varchar("name", 100)
    override val primaryKey = PrimaryKey(id)
}

object PgJsonPathUsersTable : Table("pg_json_path_users") {
    val id: Column<Int> = integer("id").autoIncrement()
    val warehouseId: Column<Int> = reference("warehouse_id", PgJsonPathWarehousesTable.id)
    val payload: Column<UserPayload> = jsonb(
        "payload",
        serialize = { payload: UserPayload -> json.encodeToString(payload) },
        deserialize = { jsonString: String -> json.decodeFromString<UserPayload>(jsonString) }
    )
    override val primaryKey = PrimaryKey(id)
}

@Testcontainers(disabledWithoutDocker = true)
class QueryFilterJsonPathPostgresTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18-alpine")
    }

    @BeforeEach
    fun setUp() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password
        )
        transaction {
            SchemaUtils.create(PgJsonPathWarehousesTable, PgJsonPathUsersTable)

            val centralId = PgJsonPathWarehousesTable.insert {
                it[name] = "Central"
            } get PgJsonPathWarehousesTable.id

            val westId = PgJsonPathWarehousesTable.insert {
                it[name] = "West"
            } get PgJsonPathWarehousesTable.id

            PgJsonPathUsersTable.insert {
                it[warehouseId] = centralId
                it[payload] = UserPayload(
                    profile = UserProfile(
                        score = 95,
                        active = true,
                        meta = ProfileMeta(tier = "gold"),
                        registeredAt = LocalDate.parse("2024-01-15"),
                        lastLogin = LocalDateTime.parse("2024-01-15T10:30:00")
                    )
                )
            }
            PgJsonPathUsersTable.insert {
                it[warehouseId] = westId
                it[payload] = UserPayload(
                    profile = UserProfile(
                        score = 70,
                        active = false,
                        meta = ProfileMeta(tier = "silver"),
                        registeredAt = LocalDate.parse("2023-06-20"),
                        lastLogin = LocalDateTime.parse("2023-06-20T14:45:00")
                    )
                )
            }
        }
    }

    @AfterEach
    fun tearDown() {
        transaction { SchemaUtils.drop(PgJsonPathUsersTable, PgJsonPathWarehousesTable) }
    }

    @Test
    fun `applies deep jsonb path and reference dot-path in one request`() {
        val filter =
            filterRequest {
                "warehouseId.name" eq "Central"
                "payload.profile.meta.tier" eq "gold"
                "payload.profile.score" gte 90
            }

        val count = transaction {
            PgJsonPathUsersTable.selectAll()
                .applyFiltersOn(PgJsonPathUsersTable, filter)
                .count()
        }

        assertEquals(1, count)
    }

    @Test
    fun `treats missing jsonb key as null for IS_NULL`() {
        val filter =
            filterRequest {
                "payload.profile.notExisting".isNull()
            }

        val count = transaction {
            PgJsonPathUsersTable.selectAll()
                .applyFiltersOn(PgJsonPathUsersTable, filter)
                .count()
        }

        assertEquals(2, count)
    }

    // STRING operator tests
    @Test
    fun `string EQ returns matching records`() {
        val filter = filterRequest { "payload.profile.meta.tier" eq "gold" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `string NEQ returns non-matching records`() {
        val filter = filterRequest { "payload.profile.meta.tier" neq "gold" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `string CONTAINS returns matching records`() {
        val filter = filterRequest { "payload.profile.meta.tier" contains "old" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `string STARTS_WITH returns matching records`() {
        val filter = filterRequest { "payload.profile.meta.tier" startsWith "gol" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `string ENDS_WITH returns matching records`() {
        val filter = filterRequest { "payload.profile.meta.tier" endsWith "old" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `string IN returns matching records`() {
        val filter = filterRequest { "payload.profile.meta.tier" inList listOf("gold", "silver") }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(2, count)
    }

    @Test
    fun `string NOT_IN returns non-matching records`() {
        val filter = filterRequest { "payload.profile.meta.tier" notInList listOf("gold") }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    // NUMBER operator tests
    @Test
    fun `number EQ returns matching records`() {
        val filter = filterRequest { "payload.profile.score" eq "95" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `number NEQ returns non-matching records`() {
        val filter = filterRequest { "payload.profile.score" neq "95" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `number GT returns matching records`() {
        val filter = filterRequest { "payload.profile.score" gt "80" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `number GTE returns matching records`() {
        val filter = filterRequest { "payload.profile.score" gte "95" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `number LT returns matching records`() {
        val filter = filterRequest { "payload.profile.score" lt "80" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `number LTE returns matching records`() {
        val filter = filterRequest { "payload.profile.score" lte "70" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `number IN returns matching records`() {
        val filter = filterRequest { "payload.profile.score" inList listOf("95", "70") }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(2, count)
    }

    @Test
    fun `number NOT_IN returns non-matching records`() {
        val filter = filterRequest { "payload.profile.score" notInList listOf("95") }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `number BETWEEN returns matching records`() {
        val filter = filterRequest { "payload.profile.score" between ("80" to "100") }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    // BOOLEAN operator tests
    @Test
    fun `boolean EQ returns matching records`() {
        val filter = filterRequest { "payload.profile.active" eq "true" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `boolean NEQ returns non-matching records`() {
        val filter = filterRequest { "payload.profile.active" neq "true" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `boolean IN returns matching records`() {
        val filter = filterRequest { "payload.profile.active" inList listOf("true", "false") }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(2, count)
    }

    @Test
    fun `boolean NOT_IN returns non-matching records`() {
        val filter = filterRequest { "payload.profile.active" notInList listOf("true") }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    // DATE operator tests
    @Test
    fun `date EQ returns matching records`() {
        val filter = filterRequest { "payload.profile.registeredAt" eq "2024-01-15" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `date NEQ returns non-matching records`() {
        val filter = filterRequest { "payload.profile.registeredAt" neq "2024-01-15" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `date GT returns matching records`() {
        val filter = filterRequest { "payload.profile.registeredAt" gt "2024-01-01" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `date GTE returns matching records`() {
        val filter = filterRequest { "payload.profile.registeredAt" gte "2024-01-15" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `date LT returns matching records`() {
        val filter = filterRequest { "payload.profile.registeredAt" lt "2024-01-01" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `date LTE returns matching records`() {
        val filter = filterRequest { "payload.profile.registeredAt" lte "2023-06-20" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `date IN returns matching records`() {
        val filter = filterRequest { "payload.profile.registeredAt" inList listOf("2024-01-15", "2023-06-20") }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(2, count)
    }

    @Test
    fun `date NOT_IN returns non-matching records`() {
        val filter = filterRequest { "payload.profile.registeredAt" notInList listOf("2024-01-15") }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `date BETWEEN returns matching records`() {
        val filter = filterRequest { "payload.profile.registeredAt" between ("2023-01-01" to "2024-12-31") }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(2, count)
    }

    // DATETIME operator tests
    @Test
    fun `datetime EQ returns matching records`() {
        val filter = filterRequest { "payload.profile.lastLogin" eq "2024-01-15T10:30:00" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `datetime NEQ returns non-matching records`() {
        val filter = filterRequest { "payload.profile.lastLogin" neq "2024-01-15T10:30:00" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `datetime GT returns matching records`() {
        val filter = filterRequest { "payload.profile.lastLogin" gt "2024-01-01T00:00:00" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `datetime GTE returns matching records`() {
        val filter = filterRequest { "payload.profile.lastLogin" gte "2024-01-15T10:30:00" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `datetime LT returns matching records`() {
        val filter = filterRequest { "payload.profile.lastLogin" lt "2024-01-01T00:00:00" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `datetime LTE returns matching records`() {
        val filter = filterRequest { "payload.profile.lastLogin" lte "2023-06-20T14:45:00" }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `datetime IN returns matching records`() {
        val filter =
            filterRequest { "payload.profile.lastLogin" inList listOf("2024-01-15T10:30:00", "2023-06-20T14:45:00") }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(2, count)
    }

    @Test
    fun `datetime NOT_IN returns non-matching records`() {
        val filter = filterRequest { "payload.profile.lastLogin" notInList listOf("2024-01-15T10:30:00") }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(1, count)
    }

    @Test
    fun `datetime BETWEEN returns matching records`() {
        val filter =
            filterRequest { "payload.profile.lastLogin" between ("2023-01-01T00:00:00" to "2024-12-31T23:59:59") }
        val count = transaction {
            PgJsonPathUsersTable.selectAll().applyFiltersOn(PgJsonPathUsersTable, filter).count()
        }
        assertEquals(2, count)
    }
}
