@file:OptIn(kotlin.time.ExperimentalTime::class)
package ua.wwind.example

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ua.wwind.example.table.EventTable
import ua.wwind.example.table.Product
import ua.wwind.example.table.ProductTable
import ua.wwind.example.table.User
import ua.wwind.example.table.UserTable
import ua.wwind.example.table.WarehouseTable
import ua.wwind.example.type.ProductId
import ua.wwind.example.type.ProductIdColumnType
import ua.wwind.example.type.WarehouseId
import ua.wwind.example.type.WarehouseIdColumnType
import ua.wwind.exposed.filters.jdbc.applyFiltersOn
import ua.wwind.exposed.filters.jdbc.columnMappers
import ua.wwind.exposed.filters.rest.receiveFilterRequestOrNull
import java.util.UUID
import kotlin.time.Instant

fun Application.module() {
    // DB init (in-memory H2)
    Database.connect(
        url = "jdbc:h2:mem:example;DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
        databaseConfig = DatabaseConfig {
            sqlLogger = StdOutSqlLogger
        }
    )
    transaction {
        SchemaUtils.create(UserTable, WarehouseTable, ProductTable, EventTable)
        // Seed data only if empty
        if (UserTable.selectAll().empty()) {
            listOf(
                User(id = null, name = "Alice", age = 17),
                User(id = null, name = "Bob", age = 18),
                User(id = null, name = "Carol", age = 25),
                User(id = null, name = "David", age = 30),
                User(id = null, name = "Eve", age = 22),
                User(id = null, name = "Frank", age = 45),
                User(id = null, name = "Grace", age = 19),
                User(id = null, name = "Henry", age = 35),
                User(id = null, name = "Ivy", age = 28),
                User(id = null, name = "Jack", age = 50)
            ).forEach { u ->
                UserTable.insert { st ->
                    st[UserTable.name] = u.name
                    st[UserTable.age] = u.age
                }
            }
        }

        // Seed Warehouses and Products
        if (WarehouseTable.selectAll().empty()) {
            val wh1 = UUID.fromString("389d58bf-1f05-4b62-b7e5-c6feedf9da30")
            val wh2 = UUID.fromString("33d23a36-5dab-4d50-9a4e-6ebd9383680b")
            WarehouseTable.insert { st ->
                st[WarehouseTable.id] = WarehouseId(wh1)
                st[WarehouseTable.name] = "Central"
            }
            WarehouseTable.insert { st ->
                st[WarehouseTable.id] = WarehouseId(wh2)
                st[WarehouseTable.name] = "East"
            }

            listOf(
                Product(id = null, warehouseId = WarehouseId(wh1), title = "Screwdriver"),
                Product(id = null, warehouseId = WarehouseId(wh1), title = "Hammer"),
                Product(id = null, warehouseId = WarehouseId(wh2), title = "Wrench"),
                Product(id = null, warehouseId = WarehouseId(wh2), title = "Saw")
            ).forEach { p ->
                ProductTable.insert { st ->
                    st[ProductTable.warehouseId] = p.warehouseId
                    st[ProductTable.title] = p.title
                }
            }
        }

        // Seed Events
        if (EventTable.selectAll().empty()) {
            val baseDay = LocalDate.parse("2024-01-01")
            val events = listOf(
                Triple("New Year Party", baseDay, Instant.fromEpochMilliseconds(1704067200000)),
                Triple(
                    "Spring Fest",
                    baseDay.plus(DatePeriod(days = 90)),
                    Instant.fromEpochMilliseconds(1711843200000)
                ),
                Triple(
                    "Summer Gala",
                    baseDay.plus(DatePeriod(days = 180)),
                    Instant.fromEpochMilliseconds(1719792000000)
                ),
                Triple(
                    "Winter Meet",
                    baseDay.plus(DatePeriod(days = 330)),
                    Instant.fromEpochMilliseconds(1733011200000)
                )
            )
            events.forEach { (title, day, ts) ->
                EventTable.insert { st ->
                    st[EventTable.title] = title
                    st[EventTable.day] = day
                    st[EventTable.occurredAt] = ts
                }
            }
        }
    }

    install(ContentNegotiation) {
        json()
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                status = io.ktor.http.HttpStatusCode.BadRequest,
                message = mapOf("error" to (cause.message ?: "Bad request"))
            )
        }
    }

    val idMappers = columnMappers {
        mapper { columnType, raw ->
            when (columnType) {
                is ProductIdColumnType -> ProductId(UUID.fromString(raw))
                is WarehouseIdColumnType -> WarehouseId(UUID.fromString(raw))
                else -> null
            }
        }
    }

    routing {
        // Demonstration endpoint: selectAll + filters
        post("/users") {
            val filter = call.receiveFilterRequestOrNull()
            val items: List<UserDto> = transaction {
                UserTable
                    .selectAll()
                    .applyFiltersOn(UserTable, filter)
                    .map { row ->
                        UserDto(
                            id = row[UserTable.id].value,
                            name = row[UserTable.name],
                            age = row[UserTable.age]
                        )
                    }
            }
            call.respond(items)
        }

        // New endpoint: products with UUID foreign key filter by property name 'warehouseId'
        post("/products") {
            val filter = call.receiveFilterRequestOrNull()
            val items: List<ProductDto> = transaction {
                ProductTable
                    .selectAll()
                    .applyFiltersOn(ProductTable, filter, idMappers)
                    .map { row ->
                        ProductDto(
                            id = row[ProductTable.id].value.toString(),
                            title = row[ProductTable.title],
                            // Serialize UUID as string for simplicity
                            warehouseId = row[ProductTable.warehouseId].value.toString()
                        )
                    }
            }
            call.respond(items)
        }

        // Events endpoint: supports date (LocalDate) and timestamp (Instant) filters
        post("/events") {
            val filter = call.receiveFilterRequestOrNull()
            val items: List<EventDto> = transaction {
                EventTable
                    .selectAll()
                    .applyFiltersOn(EventTable, filter)
                    .map { row ->
                        EventDto(
                            id = row[EventTable.id],
                            title = row[EventTable.title],
                            day = row[EventTable.day].toString(),
                            occurredAt = row[EventTable.occurredAt].toString()
                        )
                    }
            }
            call.respond(items)
        }

        // Simple health
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
    }
}

@Serializable
data class UserDto(val id: Int, val name: String, val age: Int)

@Serializable
data class ProductDto(val id: String, val title: String, val warehouseId: String)

@Serializable
data class EventDto(val id: Int, val title: String, val day: String, val occurredAt: String)
