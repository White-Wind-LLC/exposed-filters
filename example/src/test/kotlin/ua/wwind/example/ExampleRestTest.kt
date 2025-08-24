package ua.wwind.example

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
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
}
