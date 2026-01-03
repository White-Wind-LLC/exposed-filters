package ua.wwind.example

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ua.wwind.exposed.filters.core.FilterCombinator
import ua.wwind.exposed.filters.core.filterRequest
import ua.wwind.exposed.filters.core.toJsonString

class ExampleRestTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `POST users with GTE age filter returns adults`() = testApplication {
        application { module() }

        val filterJson = filterRequest {
            "age" gte 25
        }!!.toJsonString()

        val response: HttpResponse = client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody(filterJson)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val users: List<UserDto> = json.decodeFromString(body)
        val actual = users.map { it.name }.sorted()
        val expected = listOf(
            "Carol", "David", "Frank", "Henry", "Ivy", "Jack"
        ).sorted()
        assertEquals(expected, actual)
    }

    @Test
    fun `POST products filtered by warehouseId returns only products from that warehouse`() = testApplication {
        application { module() }

        // Use fixed UUID seeded in ExampleServer.kt for Central warehouse
        val filterJson = filterRequest {
            "warehouseId" eq "389d58bf-1f05-4b62-b7e5-c6feedf9da30"
        }!!.toJsonString()

        val response: HttpResponse = client.post("/products") {
            contentType(ContentType.Application.Json)
            setBody(filterJson)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val products: List<ProductDto> = json.decodeFromString(body)
        val actualTitles = products.map { it.title }.sorted()
        val expectedTitles = listOf("Hammer", "Screwdriver").sorted()
        assertEquals(expectedTitles, actualTitles)
    }

    @Test
    fun `POST users with filter on non-existing field returns BadRequest`() = testApplication {
        application { module() }

        val filterJson = filterRequest {
            "nonExistingField" eq "anything"
        }!!.toJsonString()

        val response: HttpResponse = client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody(filterJson)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        // Optional sanity check on error structure
        // e.g. { "error": "Unknown filter field: nonExistingField" }
        // We only assert it contains the field name to avoid brittle formatting checks
        kotlin.test.assertTrue(body.contains("nonExistingField"))
    }

    @Test
    fun `POST products filtered by warehouse name via relation returns only Central products`() = testApplication {
        application { module() }

        val filterJson = filterRequest {
            "warehouseId.name" startsWith "Cent"
        }!!.toJsonString()

        val response: HttpResponse = client.post("/products") {
            contentType(ContentType.Application.Json)
            setBody(filterJson)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val products: List<ProductDto> = json.decodeFromString(body)
        val actualTitles = products.map { it.title }.sorted()
        val expectedTitles = listOf("Hammer", "Screwdriver").sorted()
        assertEquals(expectedTitles, actualTitles)
    }

    @Test
    fun `POST events filtered by LocalDate EQ returns exact day`() = testApplication {
        application { module() }

        val filterJson = filterRequest {
            "day" eq "2024-01-01"
        }!!.toJsonString()

        val response: HttpResponse = client.post("/events") {
            contentType(ContentType.Application.Json)
            setBody(filterJson)
        }

        val body = response.bodyAsText()
        println("/events EQ day response: status=${response.status}, body=${body}")
        assertEquals(HttpStatusCode.OK, response.status)
        val events: List<EventDto> = json.decodeFromString(body)
        val titles = events.map { it.title }
        assertEquals(listOf("New Year Party"), titles)
    }

    @Test
    fun `POST events filtered by Instant GTE returns events after threshold`() = testApplication {
        application { module() }

        val filterJson = filterRequest {
            "occurredAt" gte "2024-07-01T00:00:00"
        }!!.toJsonString()

        val response: HttpResponse = client.post("/events") {
            contentType(ContentType.Application.Json)
            setBody(filterJson)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val events: List<EventDto> = json.decodeFromString(body)
        val titles = events.map { it.title }.sorted()
        val expected = listOf("Summer Gala", "Winter Meet").sorted()
        assertEquals(expected, titles)
    }

    @Test
    fun `POST users with IN empty values returns empty result`() = testApplication {
        application { module() }

        val filterJson = filterRequest {
            "name" inList emptyList()
        }!!.toJsonString()

        val response: HttpResponse = client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody(filterJson)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val users: List<UserDto> = json.decodeFromString(body)
        assertEquals(emptyList<UserDto>(), users)
    }

    @Test
    fun `POST users with NOT GTE age returns users under threshold`() = testApplication {
        application { module() }

        val filterJson = filterRequest(FilterCombinator.NOT) {
            "age" gte 30
        }!!.toJsonString()

        val response: HttpResponse = client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody(filterJson)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val users: List<UserDto> = json.decodeFromString(body)
        val actual = users.map { it.name }.sorted()
        val expected = listOf("Alice", "Bob", "Carol", "Eve", "Grace", "Ivy").sorted()
        assertEquals(expected, actual)
    }

    @Test
    fun `POST users with NOT over OR group excludes age ge 30 or name starts with A`() = testApplication {
        application { module() }

        val filterJson = filterRequest(FilterCombinator.NOT) {
            or {
                "age" gte 30
                "name" startsWith "A"
            }
        }!!.toJsonString()

        println(filterJson)

        val response: HttpResponse = client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody(filterJson)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val users: List<UserDto> = json.decodeFromString(body)
        val actual = users.map { it.name }.sorted()
        val expected = listOf("Bob", "Carol", "Eve", "Grace", "Ivy").sorted()
        assertEquals(expected, actual)
    }
}
