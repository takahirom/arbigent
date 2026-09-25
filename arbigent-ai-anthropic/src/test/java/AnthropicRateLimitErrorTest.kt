import io.github.takahirom.arbigent.AnthropicAi
import io.github.takahirom.arbigent.AnthropicAiRateLimitExceededException
import io.github.takahirom.arbigent.AnthropicMessage
import io.github.takahirom.arbigent.AnthropicMessagesRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 429 is not always a transient rate limit (e.g. an exhausted budget on a proxy), so the
 * provider's own message has to reach the user instead of a bare "Rate limit exceeded".
 */
class AnthropicRateLimitErrorTest {

  @Test
  fun `429 exception carries the provider's error message with keys masked`() {
    val apiKey = "sk-ant-test-secret-429"
    val anthropicAi = AnthropicAi(
      apiKey = apiKey,
      loggingEnabled = false,
      httpClient = mockHttpClient(
        """{"type":"error","error":{"type":"rate_limit_error","message":"Number of request tokens has exceeded your per-minute rate limit for $apiKey and sk-ant-api03-abcd****wxyz"}}"""
      )
    )

    val e = assertThrows(AnthropicAiRateLimitExceededException::class.java) {
      anthropicAi.createMessage(requestUuid = "uuid", request = request())
    }

    val message = e.message.orEmpty()
    assertTrue(message, message.contains("per-minute rate limit"))
    assertFalse(message, message.contains(apiKey))
    assertFalse(message, message.contains("wxyz"))
  }

  private fun request() = AnthropicMessagesRequest(
    model = "claude-test",
    maxTokens = 16,
    messages = listOf(AnthropicMessage(role = "user", content = emptyList())),
  )

  private fun mockHttpClient(body: String): HttpClient {
    return HttpClient(
      MockEngine {
        respond(
          content = body,
          status = HttpStatusCode.TooManyRequests,
          headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )
      }
    ) {
      install(ContentNegotiation) { json() }
    }
  }
}
