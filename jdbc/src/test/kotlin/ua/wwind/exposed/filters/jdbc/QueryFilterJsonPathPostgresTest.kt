package ua.wwind.exposed.filters.jdbc

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

object PgJsonPathWarehousesTable : Table("pg_json_path_warehouses") {
    val id: Column<Int> = integer("id").autoIncrement()
    val name: Column<String> = varchar("name", 100)
    override val primaryKey = PrimaryKey(id)
}

object PgJsonPathUsersTable : Table("pg_json_path_users") {
    val id: Column<Int> = integer("id").autoIncrement()
    val warehouseId: Column<Int> = reference("warehouse_id", PgJsonPathWarehousesTable.id)
    val payload: Column<String> = jsonb("payload", { it }, { it })
    override val primaryKey = PrimaryKey(id)
}

@Testcontainers(disabledWithoutDocker = true)
class QueryFilterJsonPathPostgresTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
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
                it[payload] = """{"profile":{"score":95,"active":true,"meta":{"tier":"gold"}}}"""
            }
            PgJsonPathUsersTable.insert {
                it[warehouseId] = westId
                it[payload] = """{"profile":{"score":70,"active":false,"meta":{"tier":"silver"}}}"""
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
}
