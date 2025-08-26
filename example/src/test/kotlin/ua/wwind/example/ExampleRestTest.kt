package ua.wwind.example

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExampleRestTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `POST users with GTE age filter returns adults`() = testApplication {
        application { module() }

        val filterJson = """
            {
              "combinator": "AND",
              "filters": {
                "age": [ { "op": "GTE", "value": "25" } ]
              }
            }
        """.trimIndent()

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
        val filterJson = """
            {
              "filters": {
                "warehouseId": [ { "op": "EQ", "value": "389d58bf-1f05-4b62-b7e5-c6feedf9da30" } ]
              }
            }
        """.trimIndent()

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
}
