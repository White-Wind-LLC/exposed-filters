package ua.wwind.example

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ua.wwind.exposed.filters.jdbc.applyFiltersOn
import ua.wwind.exposed.filters.rest.receiveFilterRequestOrNull

fun Application.module() {
    // DB init (in-memory H2)
    Database.connect("jdbc:h2:mem:example;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    transaction {
        SchemaUtils.create(Users)
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
    }

    install(ContentNegotiation) {
        json()
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

        // Simple health
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
    }
}

@Serializable
data class UserDto(val id: Int, val name: String, val age: Int)
