import io.github.takahirom.arbigent.AgentActionType
import io.github.takahirom.arbigent.AnthropicAi
import io.github.takahirom.arbigent.AnthropicAiRateLimitExceededException
import io.github.takahirom.arbigent.AnthropicContent
import io.github.takahirom.arbigent.AnthropicErrorResponse
import io.github.takahirom.arbigent.AnthropicImageSource
import io.github.takahirom.arbigent.AnthropicMessage
import io.github.takahirom.arbigent.AnthropicMessagesRequest
import io.github.takahirom.arbigent.AnthropicMessagesResponse
import io.github.takahirom.arbigent.ArbigentAi
import io.github.takahirom.arbigent.ArbigentAiOptions
import io.github.takahirom.arbigent.ArbigentPrompt
import io.github.takahirom.arbigent.ClickWithIndex
import io.github.takahirom.arbigent.GoalAchievedAgentAction
import io.github.takahirom.arbigent.InputTextAgentAction
import io.github.takahirom.arbigent.WaitAgentAction
import io.github.takahirom.arbigent.retryOnAnthropicRateLimit
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AnthropicRequestConversionTest {

  private val anthropicAi = AnthropicAi(
    apiKey = "test-api-key",
    loggingEnabled = false
  )

  private fun minimalRequest(): AnthropicMessagesRequest {
    return AnthropicMessagesRequest(
      model = "claude-sonnet-4-5",
      maxTokens = 1024,
      messages = listOf(
        AnthropicMessage(role = "user", content = emptyList())
      )
    )
  }

  // --- Request conversion / extraBody merging ---

  @Test
  fun `buildRequestBody returns original request when extraParams is null`() {
    val result = anthropicAi.buildRequestBody(minimalRequest(), null)

    assertTrue(result is JsonObject)
    assertEquals("claude-sonnet-4-5", result.jsonObject["model"]?.jsonPrimitive?.content)
    assertEquals(1024, result.jsonObject["max_tokens"]?.jsonPrimitive?.int)
  }

  @Test
  fun `buildRequestBody allows overriding max_tokens via extraParams`() {
    val extraParams = buildJsonObject { put("max_tokens", 2048) }

    val result = anthropicAi.buildRequestBody(minimalRequest(), extraParams)

    assertEquals(2048, result.jsonObject["max_tokens"]?.jsonPrimitive?.int)
  }

  @Test
  fun `buildRequestBody ignores protected field model`() {
    val extraParams = buildJsonObject { put("model", "malicious-model") }

    val result = anthropicAi.buildRequestBody(minimalRequest(), extraParams)

    assertEquals("claude-sonnet-4-5", result.jsonObject["model"]?.jsonPrimitive?.content)
  }

  @Test
  fun `buildRequestBody ignores protected field system`() {
    val request = minimalRequest().copy(
      system = listOf(AnthropicContent(type = "text", text = "original system prompt"))
    )
    val extraParams = buildJsonObject {
      putJsonArray("system") {
        addJsonObject {
          put("type", "text")
          put("text", "malicious override")
        }
      }
    }

    val result = anthropicAi.buildRequestBody(request, extraParams)

    val system = result.jsonObject["system"]?.jsonArray
    assertEquals(1, system?.size)
    assertEquals("original system prompt", system?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content)
  }

  @Test
  fun `buildRequestBody ignores protected field tools and tool_choice`() {
    val extraParams = buildJsonObject {
      putJsonArray("tools") { addJsonObject { put("name", "malicious_tool") } }
      putJsonObject("tool_choice") { put("type", "none") }
    }

    val result = anthropicAi.buildRequestBody(minimalRequest(), extraParams)

    assertTrue(result.jsonObject["tools"] == null || result.jsonObject["tools"] == JsonNull)
    assertTrue(result.jsonObject["tool_choice"] == null || result.jsonObject["tool_choice"] == JsonNull)
  }

  @Test
  fun `buildRequestBody blocks all protected fields defined in constant`() {
    AnthropicAi.protectedFields.forEach { protectedField ->
      val extraParams = buildJsonObject { put(protectedField, "malicious-value") }
      val result = anthropicAi.buildRequestBody(minimalRequest(), extraParams)

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
  fun `buildRequestBody omits null fields from serialization`() {
    val result = anthropicAi.buildRequestBody(minimalRequest(), null)

    assertFalse("system should be omitted when null", result.jsonObject.containsKey("system"))
    assertFalse("temperature should be omitted when null", result.jsonObject.containsKey("temperature"))
    assertFalse("tools should be omitted when null", result.jsonObject.containsKey("tools"))
  }

  // --- Base URL normalization ---

  @Test
  fun `baseUrl without a trailing slash gets one appended`() {
    val ai = AnthropicAi(apiKey = "k", baseUrl = "https://my-proxy.example.com/v1", loggingEnabled = false)

    assertEquals("https://my-proxy.example.com/v1/", ai.normalizedBaseUrl)
  }

  @Test
  fun `baseUrl with a trailing slash is left unchanged`() {
    val ai = AnthropicAi(apiKey = "k", baseUrl = "https://my-proxy.example.com/v1/", loggingEnabled = false)

    assertEquals("https://my-proxy.example.com/v1/", ai.normalizedBaseUrl)
  }

  @Test
  fun `decision tool choice defaults to any`() {
    assertEquals("any", anthropicAi.decisionToolChoice(null).type)
  }

  @Test
  fun `manual extended thinking uses auto tool choice`() {
    val extraBody = buildJsonObject {
      putJsonObject("thinking") {
        put("type", "enabled")
        put("budget_tokens", 10_000)
      }
    }
    val options = ArbigentAiOptions(extraBody = extraBody)
    val request = minimalRequest().copy(toolChoice = anthropicAi.decisionToolChoice(options))

    val body = anthropicAi.buildRequestBody(request, extraBody).jsonObject

    assertEquals("auto", body["tool_choice"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    assertEquals(10_000, body["thinking"]?.jsonObject?.get("budget_tokens")?.jsonPrimitive?.int)
  }

  @Test
  fun `adaptive thinking keeps forced tool choice`() {
    val options = ArbigentAiOptions(
      extraBody = buildJsonObject {
        putJsonObject("thinking") {
          put("type", "adaptive")
        }
      }
    )

    assertEquals("any", anthropicAi.decisionToolChoice(options).type)
  }

  // --- System prompt handling ---

  @Test
  fun `buildSystemContents maps default prompts to text blocks`() {
    val prompt = ArbigentPrompt()

    val system = anthropicAi.buildSystemContents(prompt, ArbigentScenarioDeviceFormFactor.Mobile)

    assertTrue(system.isNotEmpty())
    assertTrue(system.all { it.type == "text" })
  }

  @Test
  fun `buildSystemContents uses TV prompts for Tv form factor`() {
    val prompt = ArbigentPrompt()

    val mobileSystem = anthropicAi.buildSystemContents(prompt, ArbigentScenarioDeviceFormFactor.Mobile)
    val tvSystem = anthropicAi.buildSystemContents(prompt, ArbigentScenarioDeviceFormFactor.Tv)

    assertNotEquals(mobileSystem.first().text, tvSystem.first().text)
  }

  @Test
  fun `buildSystemContents appends additional system prompts after the base prompt`() {
    val prompt = ArbigentPrompt(additionalSystemPrompts = listOf("Extra instruction"))

    val system = anthropicAi.buildSystemContents(prompt, ArbigentScenarioDeviceFormFactor.Mobile)

    assertEquals("Extra instruction", system.last().text)
  }

  // --- Image / multimodal request conversion ---

  @Test
  fun `buildUserMessage puts the image block before the text block`() {
    val message = anthropicAi.buildUserMessage(
      imageBase64 = "ZmFrZS1pbWFnZS1ieXRlcw==",
      mimeType = "image/png",
      promptText = "What should I do next?"
    )

    assertEquals("user", message.role)
    assertEquals(2, message.content.size)
    assertEquals("image", message.content[0].type)
    assertEquals("image/png", message.content[0].source?.mediaType)
    assertEquals("base64", message.content[0].source?.type)
    assertEquals("ZmFrZS1pbWFnZS1ieXRlcw==", message.content[0].source?.data)
    assertEquals("text", message.content[1].type)
    assertEquals("What should I do next?", message.content[1].text)
  }

  @Test
  fun `buildUserMessage forwards the given mime type for non-png formats`() {
    val message = anthropicAi.buildUserMessage(
      imageBase64 = "d2VicA==",
      mimeType = "image/webp",
      promptText = "goal"
    )

    assertEquals("image/webp", message.content[0].source?.mediaType)
  }

  // --- Tool schema conversion ---

  @Test
  fun `buildTools uses input_schema and perform_ prefixed names for agent actions`() {
    val tools = anthropicAi.buildTools(agentActionTypes = listOf(ClickWithIndex), mcpTools = null)

    assertEquals(1, tools.size)
    val tool = tools.first()
    assertEquals("perform_clickwithindex", tool.name)
    assertEquals("object", tool.inputSchema["type"]?.jsonPrimitive?.content)
    assertEquals(false, tool.inputSchema["additionalProperties"]?.jsonPrimitive?.boolean)
    assertTrue(tool.inputSchema["properties"]?.jsonObject?.containsKey("text") == true)
    // arbigent-memo / arbigent-image-description are always required alongside the action's own args
    val required = tool.inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content }
    assertTrue(required?.contains("text") == true)
    assertTrue(required?.contains("arbigent-memo") == true)
  }

  @Test
  fun `buildTools omits text argument for actions with no arguments`() {
    val tools = anthropicAi.buildTools(agentActionTypes = listOf(GoalAchievedAgentAction), mcpTools = null)

    val required = tools.first().inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content }
    assertFalse(required?.contains("text") == true)
  }

  @Test
  fun `buildTools required array entries are quoted JSON strings, not bare identifiers`() {
    val tools = anthropicAi.buildTools(agentActionTypes = listOf(ClickWithIndex), mcpTools = null)

    val required = tools.first().inputSchema["required"]!!.jsonArray
    required.forEach {
      assertTrue("required entry '$it' should be a JSON string", it.jsonPrimitive.isString)
    }
  }

  // --- Anthropic response -> ArbigentAi action conversion ---

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `extractActionAndArguments reads tool_use input directly as a JSON object`() {
    val response = Json { ignoreUnknownKeys = true }.decodeFromString<AnthropicMessagesResponse>(
      """
      {
        "id": "msg_1",
        "type": "message",
        "role": "assistant",
        "model": "claude-sonnet-4-5",
        "content": [
          {"type": "text", "text": "I will click the button."},
          {"type": "tool_use", "id": "toolu_1", "name": "perform_clickwithindex", "input": {"text": "2", "arbigent-memo": "clicking", "arbigent-image-description": "a screen"}}
        ],
        "stop_reason": "tool_use"
      }
      """.trimIndent()
    )

    val (arguments, action) = anthropicAi.extractActionAndArguments(json, response, listOf(ClickWithIndex))

    assertEquals("ClickWithIndex", action)
    assertEquals("2", arguments["text"]?.jsonPrimitive?.content)
  }

  @Test
  fun `extractActionAndArguments falls back to legacy text-block JSON when there is no tool_use`() {
    val response = AnthropicMessagesResponse(
      content = listOf(
        AnthropicContent(type = "text", text = """{"action": "GoalAchieved"}""")
      )
    )

    val (_, action) = anthropicAi.extractActionAndArguments(json, response, listOf(GoalAchievedAgentAction))

    assertEquals("GoalAchieved", action)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `extractActionAndArguments rejects a tool name without the perform_ or mcp_ prefix`() {
    val response = AnthropicMessagesResponse(
      content = listOf(
        AnthropicContent(type = "tool_use", id = "toolu_1", name = "clickwithindex", input = JsonObject(emptyMap()))
      )
    )

    anthropicAi.extractActionAndArguments(json, response, listOf(ClickWithIndex))
  }

  @Test
  fun `arbigentAgentAction converts tool arguments into concrete agent actions`() {
    val click = anthropicAi.arbigentAgentAction(
      agentActionList = listOf(ClickWithIndex),
      action = "ClickWithIndex",
      argumentsJsonData = buildJsonObject { put("text", "3") },
      elements = fakeElementList(size = 5),
      mcpTools = null,
    )
    assertEquals(ClickWithIndex(index = 3), click)

    val input = anthropicAi.arbigentAgentAction(
      agentActionList = listOf(InputTextAgentAction),
      action = "InputText",
      argumentsJsonData = buildJsonObject { put("text", "hello") },
      elements = fakeElementList(size = 0),
      mcpTools = null,
    )
    assertEquals(InputTextAgentAction("hello"), input)

    val wait = anthropicAi.arbigentAgentAction(
      agentActionList = listOf(WaitAgentAction),
      action = "Wait",
      argumentsJsonData = buildJsonObject { put("text", "500") },
      elements = fakeElementList(size = 0),
      mcpTools = null,
    )
    assertEquals("Wait for 500 ms", wait.stepLogText())
  }

  // --- Error handling ---

  @Test
  fun `rate limit retries stop after the configured limit`() {
    var attempts = 0
    val delays = mutableListOf<Long>()

    try {
      retryOnAnthropicRateLimit<Unit>(waitForRetry = delays::add) {
        attempts++
        throw AnthropicAiRateLimitExceededException()
      }
      fail("Expected the final rate-limit exception")
    } catch (_: AnthropicAiRateLimitExceededException) {
      // Expected after the configured retries are exhausted.
    }

    assertEquals(AnthropicAi.MAX_RATE_LIMIT_RETRIES + 1, attempts)
    assertEquals(
      (0 until AnthropicAi.MAX_RATE_LIMIT_RETRIES).map { 10_000L * (1L shl it) },
      delays,
    )
  }

  @Test
  fun `rate limit retries return a later successful result`() {
    var attempts = 0
    val delays = mutableListOf<Long>()

    val result = retryOnAnthropicRateLimit(waitForRetry = delays::add) {
      attempts++
      if (attempts < 3) throw AnthropicAiRateLimitExceededException()
      "success"
    }

    assertEquals("success", result)
    assertEquals(3, attempts)
    assertEquals(listOf(10_000L, 20_000L), delays)
  }

  @Test
  fun `non-rate-limit failures are not retried`() {
    var attempts = 0
    val delays = mutableListOf<Long>()

    try {
      retryOnAnthropicRateLimit<Unit>(waitForRetry = delays::add) {
        attempts++
        throw IllegalStateException("invalid response")
      }
      fail("Expected the non-rate-limit exception")
    } catch (e: IllegalStateException) {
      assertEquals("invalid response", e.message)
    }

    assertEquals(1, attempts)
    assertTrue(delays.isEmpty())
  }

  @Test
  fun `AnthropicErrorResponse decodes a rate limit error body`() {
    val body = """{"type":"error","error":{"type":"rate_limit_error","message":"Number of request tokens has exceeded your per-minute rate limit"}}"""

    val error = Json { ignoreUnknownKeys = true }.decodeFromString<AnthropicErrorResponse>(body)

    assertEquals("error", error.type)
    assertEquals("rate_limit_error", error.error?.type)
    assertNotNull(error.error?.message)
  }

  @Test
  fun `AnthropicErrorResponse decodes an authentication error body`() {
    val body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""

    val error = Json { ignoreUnknownKeys = true }.decodeFromString<AnthropicErrorResponse>(body)

    assertEquals("authentication_error", error.error?.type)
  }

  @Test
  fun `AnthropicMessagesResponse decodes a stop_reason max_tokens response with no tool_use`() {
    val body = """
      {
        "id": "msg_2",
        "type": "message",
        "role": "assistant",
        "model": "claude-sonnet-4-5",
        "content": [{"type": "text", "text": "(truncated)"}],
        "stop_reason": "max_tokens",
        "usage": {"input_tokens": 500, "output_tokens": 4096}
      }
    """.trimIndent()

    val response = Json { ignoreUnknownKeys = true }.decodeFromString<AnthropicMessagesResponse>(body)

    assertEquals("max_tokens", response.stopReason)
    assertEquals(4096, response.usage?.outputTokens)
    assertNull(response.content.firstOrNull { it.type == "tool_use" })
  }
}

private fun fakeElementList(size: Int): io.github.takahirom.arbigent.ArbigentElementList {
  return io.github.takahirom.arbigent.ArbigentElementList(
    elements = (0 until size).map {
      io.github.takahirom.arbigent.ArbigentElement(
        index = it,
        textForAI = "textForAI",
        rawText = "rawText",
        treeNode = maestro.TreeNode(),
        identifierData = io.github.takahirom.arbigent.ArbigentElement.IdentifierData(listOf(), it),
        x = 0,
        y = 0,
        width = 100,
        height = 100,
        isVisible = true
      )
    },
    screenWidth = 1080,
  )
}
