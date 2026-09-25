import io.github.takahirom.arbigent.ArbigentAiRateLimitExceededException
import io.github.takahirom.arbigent.ChatCompletionRequest
import io.github.takahirom.arbigent.ChatMessage
import io.github.takahirom.arbigent.OpenAIAi
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
 * OpenAI answers both "slow down" (rate_limit_exceeded) and "out of credits" (insufficient_quota)
 * with HTTP 429, and OpenAIAi also talks to Gemini, Azure OpenAI and other OpenAI-compatible
 * endpoints whose 429 bodies differ. Whatever the provider sent has to reach the user, otherwise
 * every 429 reads as a plain "Rate limit exceeded".
 */
class OpenAiRateLimitErrorTest {

  @Test
  fun `429 exception carries the provider's error body`() {
    val openAiAi = OpenAIAi(
      apiKey = "test-api-key",
      loggingEnabled = false,
      httpClient = mockHttpClient(INSUFFICIENT_QUOTA_BODY)
    )

    val e = assertThrows(ArbigentAiRateLimitExceededException::class.java) {
      openAiAi.chatCompletion(requestUuid = "uuid", chatCompletionRequest = request())
    }

    val message = e.message.orEmpty()
    assertTrue(message, message.contains("insufficient_quota"))
    assertTrue(message, message.contains("You exceeded your current quota"))
  }

  @Test
  fun `429 exception redacts the api key echoed in the body`() {
    val apiKey = "sk-test-secret-429"
    val openAiAi = OpenAIAi(
      apiKey = apiKey,
      loggingEnabled = false,
      httpClient = mockHttpClient("""{"error":{"message":"Rate limited for key $apiKey"}}""")
    )

    val e = assertThrows(ArbigentAiRateLimitExceededException::class.java) {
      openAiAi.chatCompletion(requestUuid = "uuid", chatCompletionRequest = request())
    }

    assertFalse(e.message.orEmpty(), e.message.orEmpty().contains(apiKey))
  }

  @Test
  fun `error body masks api-key-like tokens that are not the configured key`() {
    // Providers echo a partially masked key (e.g. on 401); even a suffix must not be shown.
    val echoedKey = "sk-proj-abc1********************wxyz"
    val openAiAi = OpenAIAi(
      apiKey = "test-api-key",
      loggingEnabled = false,
      httpClient = mockHttpClient(
        body = """{"error":{"message":"Incorrect API key provided: $echoedKey."}}""",
        status = HttpStatusCode.Unauthorized,
      )
    )

    val e = assertThrows(IllegalStateException::class.java) {
      openAiAi.chatCompletion(requestUuid = "uuid", chatCompletionRequest = request())
    }

    val message = e.message.orEmpty()
    assertTrue(message, message.contains("Incorrect API key provided"))
    assertFalse(message, message.contains("wxyz"))
    assertFalse(message, message.contains("sk-proj"))
  }

  private fun request() = ChatCompletionRequest(
    model = "gpt-test",
    messages = listOf(ChatMessage(role = "user", contents = emptyList())),
  )

  private fun mockHttpClient(
    body: String,
    status: HttpStatusCode = HttpStatusCode.TooManyRequests,
  ): HttpClient {
    return HttpClient(
      MockEngine {
        respond(
          content = body,
          status = status,
          headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )
      }
    ) {
      install(ContentNegotiation) { json() }
    }
  }

  private companion object {
    // Shape of OpenAI's response for an account without credits.
    const val INSUFFICIENT_QUOTA_BODY = """{
  "error": {
    "message": "You exceeded your current quota, please check your plan and billing details.",
    "type": "insufficient_quota",
    "param": null,
    "code": "insufficient_quota"
  }
}"""
  }
}
