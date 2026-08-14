package ua.wwind.example

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end coverage for the filter body contract: a body the server cannot understand must fail
 * the request instead of quietly returning an unfiltered page.
 */
class ExampleFilterBodyValidationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `shorthand filter body is rejected with 400 instead of returning every row`() = testApplication {
        application { module() }

        val response: HttpResponse = client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"age":{"gte":25}}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(
            response.bodyAsText().contains("filter", ignoreCase = true),
            "error body should explain the filter body was invalid, was: ${response.bodyAsText()}"
        )
    }

    @Test
    fun `unknown key alongside a valid filter is rejected with 400`() = testApplication {
        application { module() }

        val response: HttpResponse = client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"filters":{"age":[{"op":"GTE","value":"25"}]},"sort":"name"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `empty body still means no filters`() = testApplication {
        application { module() }

        val response: HttpResponse = client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val users: List<UserDto> = json.decodeFromString(response.bodyAsText())
        assertTrue(users.size > 1, "an empty body applies no filters, expected the full list")
    }
}
