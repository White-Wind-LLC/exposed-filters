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
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ua.wwind.exposed.filters.jdbc.applyFiltersOn
import ua.wwind.exposed.filters.rest.receiveFilterRequestOrNull
import java.util.UUID

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
        SchemaUtils.create(Users, Warehouses, Products)
        // Seed data only if empty
        if (Users.selectAll().empty()) {
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
                Users.insert { st ->
                    st[Users.name] = u.name
                    st[Users.age] = u.age
                }
            }
        }

        // Seed Warehouses and Products
        if (Warehouses.selectAll().empty()) {
            val wh1 = UUID.fromString("389d58bf-1f05-4b62-b7e5-c6feedf9da30")
            val wh2 = UUID.fromString("33d23a36-5dab-4d50-9a4e-6ebd9383680b")
            Warehouses.insert { st ->
                st[Warehouses.id] = wh1
                st[Warehouses.name] = "Central"
            }
            Warehouses.insert { st ->
                st[Warehouses.id] = wh2
                st[Warehouses.name] = "East"
            }

            listOf(
                Product(id = null, warehouseId = wh1, title = "Screwdriver"),
                Product(id = null, warehouseId = wh1, title = "Hammer"),
                Product(id = null, warehouseId = wh2, title = "Wrench"),
                Product(id = null, warehouseId = wh2, title = "Saw")
            ).forEach { p ->
                Products.insert { st ->
                    st[Products.warehouseId] = p.warehouseId
                    st[Products.title] = p.title
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

    routing {
        // Demonstration endpoint: selectAll + filters
        post("/users") {
            val filter = call.receiveFilterRequestOrNull()
            val items: List<UserDto> = transaction {
                Users
                    .selectAll()
                    .applyFiltersOn(Users, filter)
                    .map { row ->
                        UserDto(
                            id = row[Users.id],
                            name = row[Users.name],
                            age = row[Users.age]
                        )
                    }
            }
            call.respond(items)
        }

        // New endpoint: products with UUID foreign key filter by property name 'warehouseId'
        post("/products") {
            val filter = call.receiveFilterRequestOrNull()
            val items: List<ProductDto> = transaction {
                Products
                    .selectAll()
                    .applyFiltersOn(Products, filter)
                    .map { row ->
                        ProductDto(
                            id = row[Products.id].value,
                            title = row[Products.title],
                            // Serialize UUID as string for simplicity
                            warehouseId = row[Products.warehouseId].toString()
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
data class ProductDto(val id: Long, val title: String, val warehouseId: String)
