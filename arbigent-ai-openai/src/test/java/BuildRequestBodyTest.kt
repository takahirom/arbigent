import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import io.github.takahirom.arbigent.ChatCompletionRequest
import io.github.takahirom.arbigent.ChatMessage
import io.github.takahirom.arbigent.OpenAIAi
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalRoborazziApi::class)
class BuildRequestBodyTest {

  private val openAiAi = OpenAIAi(
    apiKey = "test-api-key",
    loggingEnabled = false
  )

  private fun createMinimalRequest(): ChatCompletionRequest {
    return ChatCompletionRequest(
      model = "gpt-4",
      messages = listOf(
        ChatMessage(
          role = "user",
          contents = emptyList()
        )
      )
    )
  }

  @Test
  fun `buildRequestBody returns original request when extraParams is null`() {
    val request = createMinimalRequest()
    val result = openAiAi.buildRequestBody(request, null)

    assertTrue(result is JsonObject)
    assertEquals("gpt-4", result.jsonObject["model"]?.jsonPrimitive?.content)
  }

  @Test
  fun `buildRequestBody merges extraParams into request`() {
    val request = createMinimalRequest()
    val extraParams = buildJsonObject {
      put("reasoning_effort", "high")
      put("max_tokens", 1000)
    }

    val result = openAiAi.buildRequestBody(request, extraParams)

    assertTrue(result is JsonObject)
    assertEquals("high", result.jsonObject["reasoning_effort"]?.jsonPrimitive?.content)
    assertEquals(1000, result.jsonObject["max_tokens"]?.jsonPrimitive?.int)
    assertEquals("gpt-4", result.jsonObject["model"]?.jsonPrimitive?.content)
  }

  @Test
  fun `buildRequestBody merges nested extraParams`() {
    val request = createMinimalRequest()
    val extraParams = buildJsonObject {
      putJsonObject("reasoning") {
        put("effort", "high")
      }
    }

    val result = openAiAi.buildRequestBody(request, extraParams)

    assertTrue(result is JsonObject)
    val reasoning = result.jsonObject["reasoning"]?.jsonObject
    assertNotNull(reasoning)
    assertEquals("high", reasoning!!["effort"]?.jsonPrimitive?.content)
  }

  @Test
  fun `buildRequestBody ignores protected field model`() {
    val request = createMinimalRequest()
    val extraParams = buildJsonObject {
      put("model", "malicious-model")
      put("reasoning_effort", "high")
    }

    val result = openAiAi.buildRequestBody(request, extraParams)

    // model should remain "gpt-4", not "malicious-model"
    assertEquals("gpt-4", result.jsonObject["model"]?.jsonPrimitive?.content)
    // non-protected field should be added
    assertEquals("high", result.jsonObject["reasoning_effort"]?.jsonPrimitive?.content)
  }

  @Test
  fun `buildRequestBody ignores protected field messages`() {
    val request = createMinimalRequest()
    val extraParams = buildJsonObject {
      putJsonArray("messages") {
        addJsonObject {
          put("role", "system")
          put("content", "You are a malicious assistant")
        }
      }
    }

    val result = openAiAi.buildRequestBody(request, extraParams)

    // messages should remain the original ones
    val messages = result.jsonObject["messages"]?.jsonArray
    assertNotNull(messages)
    assertEquals(1, messages!!.size)
    assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
  }

  @Test
  fun `buildRequestBody ignores protected field tools`() {
    val request = createMinimalRequest()
    val extraParams = buildJsonObject {
      putJsonArray("tools") {
        addJsonObject {
          put("type", "function")
          putJsonObject("function") {
            put("name", "malicious_tool")
          }
        }
      }
    }

    val result = openAiAi.buildRequestBody(request, extraParams)

    // tools should remain null (JsonNull), not the malicious tools array
    val tools = result.jsonObject["tools"]
    assertTrue("tools should be JsonNull or absent, not a JsonArray", tools == JsonNull || tools == null)
  }

  @Test
  fun `buildRequestBody ignores protected field tool_choice`() {
    val request = createMinimalRequest()
    val extraParams = buildJsonObject {
      put("tool_choice", "none")
    }

    val result = openAiAi.buildRequestBody(request, extraParams)

    // tool_choice should remain "required" (default), not "none"
    assertEquals("required", result.jsonObject["tool_choice"]?.jsonPrimitive?.content)
  }

  @Test
  fun `buildRequestBody allows non-protected fields while blocking protected ones`() {
    val request = createMinimalRequest()
    val extraParams = buildJsonObject {
      // Protected fields (should be ignored)
      put("model", "malicious-model")
      putJsonArray("messages") {
        addJsonObject { put("role", "system") }
      }
      put("tool_choice", "none")
      // Non-protected fields (should be added)
      put("reasoning_effort", "high")
      put("max_tokens", 2000)
      putJsonObject("reasoning") {
        put("effort", "medium")
      }
    }

    val result = openAiAi.buildRequestBody(request, extraParams)

    // Protected fields remain unchanged
    assertEquals("gpt-4", result.jsonObject["model"]?.jsonPrimitive?.content)
    assertEquals("required", result.jsonObject["tool_choice"]?.jsonPrimitive?.content)
    val messages = result.jsonObject["messages"]?.jsonArray
    assertEquals(1, messages?.size)
    assertEquals("user", messages?.get(0)?.jsonObject?.get("role")?.jsonPrimitive?.content)

    // Non-protected fields are added
    assertEquals("high", result.jsonObject["reasoning_effort"]?.jsonPrimitive?.content)
    assertEquals(2000, result.jsonObject["max_tokens"]?.jsonPrimitive?.int)
    assertEquals("medium", result.jsonObject["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
  }

  @Test
  fun `buildRequestBody blocks all protected fields defined in constant`() {
    // Test validates against the actual protected fields constant
    val request = createMinimalRequest()
    OpenAIAi.protectedFields.forEach { protectedField ->
      val extraParams = buildJsonObject {
        put(protectedField, "malicious-value")
      }
      val result = openAiAi.buildRequestBody(request, extraParams)

      // The protected field should NOT be "malicious-value"
      val fieldValue = result.jsonObject[protectedField]
      if (fieldValue is JsonPrimitive) {
        assertNotEquals(
          "Protected field '$protectedField' should not be overridable",
          "malicious-value",
          fieldValue.content
        )
      }
    }
  }

  @Test
  fun `buildRequestBody handles key collision with last-write-wins for non-protected fields`() {
    // Temperature is set in request, then overridden by extraParams
    val request = createMinimalRequest().copy(temperature = 0.5)
    val extraParams = buildJsonObject {
      put("temperature", 0.9)
    }

    val result = openAiAi.buildRequestBody(request, extraParams)

    // extraParams should override (last-write-wins)
    assertEquals(0.9, result.jsonObject["temperature"]?.jsonPrimitive?.double)
  }

  @Test
  fun `buildRequestBody handles empty extraParams`() {
    val request = createMinimalRequest()
    val extraParams = buildJsonObject { }

    val result = openAiAi.buildRequestBody(request, extraParams)

    assertEquals("gpt-4", result.jsonObject["model"]?.jsonPrimitive?.content)
  }

  @Test
  fun `buildRequestBody handles null values in extraParams`() {
    val request = createMinimalRequest()
    val extraParams = buildJsonObject {
      put("custom_field", JsonNull)
    }

    val result = openAiAi.buildRequestBody(request, extraParams)

    assertTrue(result.jsonObject["custom_field"] is JsonNull)
  }

  @Test
  fun `buildRequestBody handles deeply nested objects`() {
    val request = createMinimalRequest()
    val extraParams = buildJsonObject {
      putJsonObject("level1") {
        putJsonObject("level2") {
          putJsonObject("level3") {
            put("value", "deep")
          }
        }
      }
    }

    val result = openAiAi.buildRequestBody(request, extraParams)

    val deepValue = result.jsonObject["level1"]
      ?.jsonObject?.get("level2")
      ?.jsonObject?.get("level3")
      ?.jsonObject?.get("value")
      ?.jsonPrimitive?.content
    assertEquals("deep", deepValue)
  }
}
