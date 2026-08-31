package io.github.takahirom.arbigent

import com.moczul.ok2curl.CurlCommandGenerator
import io.github.takahirom.arbigent.ConfidentialInfo.removeConfidentialInfo
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.github.takahirom.arbigent.serialization.GenerateJsonSchemaApiType
import io.github.takahirom.arbigent.serialization.generateRootJsonSchema
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.HttpTimeoutConfig.Companion.INFINITE_TIMEOUT_MS
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.awt.image.BufferedImage.TYPE_INT_RGB
import java.io.File
import java.util.Deque
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

public class AnthropicAiRateLimitExceededException : Exception("Rate limit exceeded")

private const val ANTHROPIC_RATE_LIMIT_INITIAL_DELAY_MS: Long = 10_000L

internal fun <T> retryOnAnthropicRateLimit(
  maxRetries: Int = AnthropicAi.MAX_RATE_LIMIT_RETRIES,
  waitForRetry: (Long) -> Unit = { waitMs ->
    ArbigentGlobalStatus.onAiRateLimitWait(waitSec = waitMs / 1000) {
      Thread.sleep(waitMs)
    }
  },
  operation: () -> T,
): T {
  require(maxRetries >= 0) { "maxRetries must not be negative" }
  var retryCount = 0
  while (true) {
    try {
      return operation()
    } catch (e: AnthropicAiRateLimitExceededException) {
      if (retryCount >= maxRetries) {
        throw e
      }
      val waitMs = ANTHROPIC_RATE_LIMIT_INITIAL_DELAY_MS * (1L shl retryCount)
      arbigentInfoLog("Rate limit exceeded. Waiting for ${waitMs / 1000} seconds.")
      waitForRetry(waitMs)
      retryCount++
    }
  }
}

private enum class ArbigentAiAnswerItems(
  val key: String,
  val type: String,
  val description: String,
) {
  Memo("arbigent-memo", "string", "Memo for the agent"),
  ImageDescription("arbigent-image-description", "string", "Description of what is visible in the image");

  fun toJsonString(): String {
    return """"$key": {
  "type": "$type",
  "description": "$description"
}"""
  }

  fun toJsonObject(): JsonObject {
    return JsonObject(
      mapOf(
        key to JsonObject(
          mapOf(
            "type" to JsonPrimitive(type),
            "description" to JsonPrimitive(description)
          )
        )
      )
    )
  }
}

internal class AnthropicCurl(
  val requestUuid: String,
  val command: String,
)

internal val anthropicCurls: Deque<AnthropicCurl> = ConcurrentLinkedDeque()

/**
 * [ArbigentAi] implementation for Anthropic's Messages API (https://docs.anthropic.com/en/api/messages).
 *
 * Unlike [OpenAIAi], Anthropic is not OpenAI-schema-compatible: authentication uses the `x-api-key` /
 * `anthropic-version` headers instead of a bearer token, the system prompt is a top-level request field
 * rather than a `system` message, and tool definitions/tool-use responses use Anthropic's own shapes.
 */
@OptIn(ExperimentalSerializationApi::class)
public class AnthropicAi @OptIn(ArbigentInternalApi::class) constructor(
  private val apiKey: String,
  private val baseUrl: String = DEFAULT_ANTHROPIC_BASE_URL,
  private val modelName: String = DEFAULT_ANTHROPIC_MODEL,
  private val maxTokens: Int = DEFAULT_MAX_TOKENS,
  private val requestBuilderModifier: HttpRequestBuilder.() -> Unit = {
    header("x-api-key", apiKey)
    header("anthropic-version", ANTHROPIC_VERSION)
  },
  @property:ArbigentInternalApi
  public val loggingEnabled: Boolean,
  private val httpClient: HttpClient = HttpClient(OkHttp) {
    engine {
      config {
        this.addNetworkInterceptor(
          object : Interceptor {
            private val curlGenerator = CurlCommandGenerator(com.moczul.ok2curl.Configuration())

            override fun intercept(chain: Interceptor.Chain): Response {
              val request = chain.request()

              // escape '
              val oldBody = request.body
              val contentType = oldBody?.contentType()
              val charset = contentType?.charset() ?: Charsets.UTF_8
              val sink = Buffer()
              oldBody?.writeTo(sink)
              val bodyText = sink.readString(charset)
              val logBodyText = bodyText.replace("'", "'\"'\"'")
              val logRequest = request.newBuilder()
                .method(request.method, body = logBodyText.toRequestBody(contentType))
                .build()
              val curl = curlGenerator.generate(logRequest)
              val log = curl
                .removeConfidentialInfo()
              anthropicCurls.add(
                AnthropicCurl(
                  requestUuid = logRequest.url.queryParameter("requestUuid") ?: "unknown",
                  command = log
                )
              )
              if (anthropicCurls.size > 10) {
                anthropicCurls.removeFirst()
              }

              if (loggingEnabled) {
                arbigentDebugLog(log)
              }

              val response = chain.proceed(
                request
                  .newBuilder()
                  .url(request.url.newBuilder().removeAllQueryParameters("requestUuid").build())
                  .build()
              )
              // Recording here rather than where the API call log is written means every call
              // through this client is counted, whatever code path made it.
              // peekBody keeps the body readable for the caller; string() would consume it.
              runCatching {
                parseApiUsageRecord(
                  requestUuid = logRequest.url.queryParameter("requestUuid"),
                  responseBody = response.peekBody(API_USAGE_PEEK_BYTE_LIMIT).string()
                )?.let(::writeApiUsageRecord)
              }
              return response
            }
          }
        )
      }
    }
    install(HttpRequestRetry) {
      maxRetries = 3
      exponentialDelay()
    }
    install(ContentNegotiation) {
      json(
        json = Json {
          isLenient = true
          encodeDefaults = true
          ignoreUnknownKeys = true
          explicitNulls = false
        }
      )
    }
    install(HttpTimeout) {
      requestTimeoutMillis = INFINITE_TIMEOUT_MS
      socketTimeoutMillis = 80_000
    }
    if (loggingEnabled) {
      install(Logging) {
        logger = object : Logger {
          override fun log(message: String) {
            arbigentInfoLog(message.removeConfidentialInfo())
          }
        }
        level = LogLevel.ALL
      }
    }
  },
) : ArbigentAi {
  // Callers (CLI options, UI dialogs) don't all normalize a trailing slash, so do it once here
  // rather than relying on every caller to get baseUrl + "messages" right.
  internal val normalizedBaseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

  init {
    ConfidentialInfo.addStringToBeRemoved(apiKey, "{{API_KEY}}")
  }

  @OptIn(ExperimentalSerializationApi::class, ArbigentInternalApi::class)
  override fun decideAgentActions(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
    val contextHolder = decisionInput.contextHolder
    val screenshotFilePath = decisionInput.screenshotFilePath
    val decisionJsonlFilePath = decisionInput.apiCallJsonLFilePath
    val formFactor = decisionInput.formFactor
    val uiTreeStrings = decisionInput.uiTreeStrings
    val focusedTree = decisionInput.focusedTreeString
    val agentActionTypes = decisionInput.agentActionTypes
    val elements = decisionInput.elements
    val requestUuid = decisionInput.requestUuid

    val original = File(screenshotFilePath)
    val canvas = ArbigentCanvas.load(original, elements.screenWidth, TYPE_INT_RGB)
    canvas.draw(elements)
    canvas.save(original.getAnnotatedFilePath(), decisionInput.aiOptions)

    val imageBase64 = File(screenshotFilePath).readImageBase64()
    val mimeType = decisionInput.aiOptions?.imageFormat?.mimeType ?: ImageFormat.PNG.mimeType
    val prompt =
      buildPrompt(
        contextHolder = contextHolder,
        focusedTree = focusedTree,
        elements = elements,
        aiOptions = decisionInput.aiOptions ?: ArbigentAiOptions(),
        aiHints = uiTreeStrings.aiHints,
      )
    val systemContents = buildSystemContents(decisionInput.prompt, formFactor)
    val messages: List<AnthropicMessage> = listOf(
      buildUserMessage(imageBase64 = imageBase64, mimeType = mimeType, promptText = prompt)
    )
    val toolDefinitions = buildTools(agentActionTypes = agentActionTypes, mcpTools = decisionInput.mcpTools)
    val messagesRequest = AnthropicMessagesRequest(
      model = modelName,
      maxTokens = maxTokens,
      system = systemContents,
      messages = messages,
      tools = toolDefinitions,
      toolChoice = decisionToolChoice(decisionInput.aiOptions),
    )
    val responseText = try {
      retryOnAnthropicRateLimit {
        createMessage(
          requestUuid = requestUuid,
          request = messagesRequest,
          aiOptions = decisionInput.aiOptions
        )
      }
    } catch (e: AnthropicAiRateLimitExceededException) {
      throw e
    } catch (e: Exception) {
      contextHolder.addStep(
        ArbigentContextHolder.Step(
          stepId = decisionInput.stepId,
          agentAction = FailedAgentAction(),
          feedback = "Failed to execute the task by the exception: ${e.message}.",
          cacheKey = decisionInput.cacheKey,
          screenshotFilePath = decisionInput.screenshotFilePath,
        )
      )
      throw e
    }
    val curlString = anthropicCurls.lastOrNull { it.requestUuid == requestUuid }?.command
      ?: "No curl command available for requestUuid: $requestUuid"
    val json = Json { ignoreUnknownKeys = true }
    var responseObj: AnthropicMessagesResponse?
    try {
      val step = try {
        responseObj = json.decodeFromString<AnthropicMessagesResponse>(responseText)
        val file = File(decisionJsonlFilePath)
        file.parentFile.mkdirs()
        file.writeText(
          json.encodeToString(
            AnthropicApiCall(
              curl = curlString,
              responseBody = responseObj,
              metadata = AnthropicApiCallMetadata()
            )
          ).removeConfidentialInfo()
        )
        parseResponse(
          json = json,
          response = responseObj,
          messages = messages,
          decisionInput = decisionInput,
          toolDefinitions = toolDefinitions,
        )
      } catch (e: ArbigentAi.FailedToParseResponseException) {
        ArbigentContextHolder.Step(
          stepId = decisionInput.stepId,
          feedback = "Failed to parse AI response: ${e.message}",
          screenshotFilePath = screenshotFilePath,
          aiRequest = messages.toHumanReadableString(toolDefinitions),
          aiResponse = responseText,
          uiTreeStrings = uiTreeStrings,
          cacheKey = decisionInput.cacheKey,
        )
      }
      return ArbigentAi.DecisionOutput(listOfNotNull(step.agentAction), step)
    } catch (e: MissingFieldException) {
      arbigentInfoLog("Missing required field in Anthropic response: $e $responseText")
      throw e
    }
  }

  /**
   * Builds the top-level `system` content blocks for a request: Anthropic has no `system` role
   * message like OpenAI, so the system prompt is a dedicated request field instead.
   */
  internal fun buildSystemContents(
    prompt: ArbigentPrompt,
    formFactor: ArbigentScenarioDeviceFormFactor,
  ): List<AnthropicContent> {
    return when (formFactor) {
      ArbigentScenarioDeviceFormFactor.Tv -> prompt.systemPromptsForTv
      else -> prompt.systemPrompts
    }.map {
      AnthropicContent(type = "text", text = it)
    } + prompt.additionalSystemPrompts.map {
      AnthropicContent(type = "text", text = it)
    }
  }

  internal fun decisionToolChoice(aiOptions: ArbigentAiOptions?): AnthropicToolChoice {
    return if (isManualThinkingEnabled(aiOptions)) {
      AnthropicToolChoice.Auto
    } else {
      AnthropicToolChoice.Any
    }
  }

  private fun isManualThinkingEnabled(aiOptions: ArbigentAiOptions?): Boolean {
    val thinking = aiOptions?.extraBody?.get("thinking") as? JsonObject
    val thinkingType = (thinking?.get("type") as? JsonPrimitive)?.contentOrNull
    return thinkingType == "enabled"
  }

  internal fun applyTemperature(
    request: AnthropicMessagesRequest,
    aiOptions: ArbigentAiOptions?,
  ): AnthropicMessagesRequest {
    if (isManualThinkingEnabled(aiOptions)) {
      if (aiOptions?.temperature != null || request.temperature != null) {
        arbigentDebugLog { "Ignoring temperature because Anthropic extended thinking is enabled." }
      }
      return request.copy(temperature = null)
    }
    val temperature = aiOptions?.temperature ?: return request
    return request.copy(temperature = temperature)
  }

  /**
   * Builds the single user turn: a base64-encoded screenshot followed by the text prompt.
   */
  internal fun buildUserMessage(
    imageBase64: String,
    mimeType: String,
    promptText: String,
  ): AnthropicMessage {
    return AnthropicMessage(
      role = "user",
      content = listOf(
        AnthropicContent(
          type = "image",
          source = AnthropicImageSource(
            mediaType = mimeType,
            data = imageBase64
          )
        ),
        AnthropicContent(
          type = "text",
          text = promptText
        ),
      )
    )
  }

  private fun buildPrompt(
    contextHolder: ArbigentContextHolder,
    focusedTree: String?,
    elements: ArbigentElementList,
    aiOptions: ArbigentAiOptions,
    aiHints: List<String> = emptyList(),
  ): String {
    val focusedTreeText = focusedTree.orEmpty().ifBlank { "No focused tree" }
    val uiElements = elements.getPromptTexts().ifBlank { "No UI elements to select. Please check the image." }

    return contextHolder.prompt(
      uiElements = uiElements,
      focusedTree = focusedTreeText,
      aiOptions = aiOptions,
      aiHints = aiHints,
    )
  }

  private fun parseResponse(
    json: Json,
    response: AnthropicMessagesResponse,
    messages: List<AnthropicMessage>,
    toolDefinitions: List<AnthropicToolDefinition>,
    decisionInput: ArbigentAi.DecisionInput,
  ): ArbigentContextHolder.Step {
    val screenshotFilePath = decisionInput.screenshotFilePath
    val elements = decisionInput.elements
    val agentActionList = decisionInput.agentActionTypes
    arbigentInfoLog {
      "AI usage: ${response.usage}"
    }

    return try {
      val (argumentsJsonData, action) = extractActionAndArguments(json, response, agentActionList)

      val agentAction: ArbigentAgentAction = arbigentAgentAction(
        agentActionList = agentActionList,
        action = action,
        argumentsJsonData = argumentsJsonData,
        elements = elements,
        mcpTools = decisionInput.mcpTools,
      )
      ArbigentContextHolder.Step(
        stepId = decisionInput.stepId,
        agentAction = agentAction,
        action = action,
        imageDescription = argumentsJsonData[ArbigentAiAnswerItems.ImageDescription.key]?.jsonPrimitive?.content ?: "",
        memo = argumentsJsonData[ArbigentAiAnswerItems.Memo.key]?.jsonPrimitive?.content ?: "",
        aiRequest = messages.toHumanReadableString(tools = toolDefinitions),
        aiResponse = response.toString(),
        screenshotFilePath = screenshotFilePath,
        apiCallJsonLFilePath = decisionInput.apiCallJsonLFilePath,
        uiTreeStrings = decisionInput.uiTreeStrings,
        cacheKey = decisionInput.cacheKey,
      )
    } catch (e: Exception) {
      throw ArbigentAi.FailedToParseResponseException(
        "Failed to parse AI response: ${e.message}",
        e
      )
    }
  }

  /**
   * Extracts the action name and its JSON arguments from an Anthropic response: a `tool_use`
   * content block (Anthropic's `input` is already a JSON object, unlike OpenAI's stringified
   * `function.arguments`), falling back to parsing a plain `text` block as JSON (legacy format).
   */
  internal fun extractActionAndArguments(
    json: Json,
    response: AnthropicMessagesResponse,
    agentActionList: List<AgentActionType>,
  ): Pair<JsonObject, String> {
    val toolUse = response.content.firstOrNull { it.type == "tool_use" }
    val textBlock = response.content.firstOrNull { it.type == "text" }

    return if (toolUse != null) {
      // Handle tool-use response
      val functionName = toolUse.name
        ?: throw IllegalArgumentException("Tool use block has no name")

      // Extract action name from tool name (e.g., "perform_clickwithindex" -> "ClickWithIndex")
      if (!functionName.startsWith("perform_") && !functionName.startsWith("mcp_")) {
        throw IllegalArgumentException("Unknown function: $functionName")
      }

      val actionKey = functionName.removePrefix("perform_")
      // Convert action key to proper case if needed (e.g., "clickwithindex" -> "ClickWithIndex")
      val actionName = agentActionList.find {
        it.actionName.equals(actionKey, ignoreCase = true)
      }?.actionName ?: actionKey

      (toolUse.input ?: JsonObject(emptyMap())) to actionName
    } else if (textBlock?.text != null) {
      // Handle regular response (legacy format)
      val jsonObj = json.parseToJsonElement(textBlock.text).jsonObject
      val actionName = jsonObj["action"]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("Action not found in response content")
      jsonObj to actionName
    } else {
      throw IllegalArgumentException("No tool use or text content in response")
    }
  }

  internal fun arbigentAgentAction(
    agentActionList: List<AgentActionType>,
    action: String,
    argumentsJsonData: JsonObject,
    elements: ArbigentElementList,
    mcpTools: List<MCPTool>?
  ): ArbigentAgentAction {
    if (action.startsWith("mcp_")) {
      val mcpAction = action.removePrefix("mcp_")
      val mcpTool = mcpTools?.firstOrNull { it.name == mcpAction }
        ?: throw IllegalArgumentException("Unknown MCP action: $action. Available actions: ${mcpTools?.joinToString { it.name }}")
      return ExecuteMcpToolAgentAction(
        tool = mcpTool,
        executeToolArgs = ExecuteToolArgs(
          arguments = argumentsJsonData.let {
            // Remove arbigent parameters
            JsonObject(it.filterKeys { key ->
              !ArbigentAiAnswerItems.entries.map { it.key }.contains(key)
            }.toMap())
          },
        )
      )
    }
    val agentActionMap = agentActionList.associateBy { it.actionName }
    val actionPrototype = agentActionMap[action]
      ?: throw IllegalArgumentException("Unknown action: $action. Available actions: ${agentActionMap.keys.joinToString()}")
    val agentAction: ArbigentAgentAction = when (actionPrototype) {
      GoalAchievedAgentAction -> GoalAchievedAgentAction()
      FailedAgentAction -> FailedAgentAction()
      ClickWithTextAgentAction -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        ClickWithTextAgentAction(text)
      }

      ClickWithIdAgentAction -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        ClickWithIdAgentAction(text)
      }

      DpadUpArrowAgentAction -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        DpadUpArrowAgentAction(text.toIntOrNull() ?: 1)
      }

      DpadDownArrowAgentAction -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        DpadDownArrowAgentAction(text.toIntOrNull() ?: 1)
      }

      DpadLeftArrowAgentAction -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        DpadLeftArrowAgentAction(text.toIntOrNull() ?: 1)
      }

      DpadRightArrowAgentAction -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        DpadRightArrowAgentAction(text.toIntOrNull() ?: 1)
      }

      DpadCenterAgentAction -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        DpadCenterAgentAction(text.toIntOrNull() ?: 1)
      }

      DpadAutoFocusWithIdAgentAction -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        DpadAutoFocusWithIdAgentAction(text)
      }

      DpadAutoFocusWithTextAgentAction -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        DpadAutoFocusWithTextAgentAction(text)
      }

      DpadAutoFocusWithIndexAgentAction -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        val index = text.toIntOrNull()
          ?: throw IllegalArgumentException("text should be a number for ${DpadAutoFocusWithIndexAgentAction.actionName}")
        if (index < 0 || elements.elements.size <= index) {
          throw IllegalArgumentException("Index out of bounds: $index")
        }
        DpadAutoFocusWithIndexAgentAction(index)
      }

      InputTextAgentAction -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        InputTextAgentAction(text)
      }

      ClickWithIndex -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        val index = text.toIntOrNull()
          ?: throw IllegalArgumentException("text should be a number for ${ClickWithIndex.actionName}")
        if (index < 0 || elements.elements.size <= index) {
          throw IllegalArgumentException("Index out of bounds: $index")
        }
        ClickWithIndex(
          index = index,
        )
      }

      ClickAtCoordinates -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        val parts = text.split(",").map { it.trim() }
        if (parts.size != 2) {
          throw IllegalArgumentException("text should be \"x,y\" for ${ClickAtCoordinates.actionName}, got: \"$text\"")
        }
        val x = parts[0].toIntOrNull()
          ?: throw IllegalArgumentException("x is not an integer for ${ClickAtCoordinates.actionName}: \"${parts[0]}\"")
        val y = parts[1].toIntOrNull()
          ?: throw IllegalArgumentException("y is not an integer for ${ClickAtCoordinates.actionName}: \"${parts[1]}\"")
        ClickAtCoordinates(x = x, y = y)
      }

      BackPressAgentAction -> BackPressAgentAction()

      KeyPressAgentAction -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        KeyPressAgentAction(text)
      }

      WaitAgentAction -> {
        val text = argumentsJsonData["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
        WaitAgentAction(text.toIntOrNull() ?: 1000)
      }

      ScrollAgentAction -> ScrollAgentAction()

      else -> throw IllegalArgumentException("Unsupported action: $action")
    }
    return agentAction
  }

  @OptIn(ArbigentInternalApi::class)
  private fun createMessage(
    requestUuid: String,
    request: AnthropicMessagesRequest,
    aiOptions: ArbigentAiOptions? = null
  ): String {
    return runBlocking {
      val requestWithTemp = applyTemperature(request, aiOptions)
      val response: HttpResponse =
        httpClient.post(normalizedBaseUrl + "messages") {
          url {
            parameters.append("requestUuid", requestUuid)
          }
          requestBuilderModifier()
          contentType(ContentType.Application.Json)
          setBody(buildRequestBody(requestWithTemp, aiOptions?.extraBody))
        }
      val responseBody = response.bodyAsText()
      if (response.status == HttpStatusCode.TooManyRequests) {
        throw AnthropicAiRateLimitExceededException()
      } else if (400 <= response.status.value) {
        val apiErrorMessage = try {
          Json { ignoreUnknownKeys = true }
            .decodeFromString<AnthropicErrorResponse>(responseBody).error?.message
        } catch (e: Exception) {
          arbigentDebugLog { "Anthropic error response was not the expected shape: ${e.message}" }
          null
        }
        // The raw body reaches step feedback/reports, so redact and cap it first.
        val errorDetail = (apiErrorMessage ?: responseBody).removeConfidentialInfo().take(1_000)
        throw IllegalStateException(
          "Failed to call API: ${response.status} $errorDetail"
        )
      }
      return@runBlocking responseBody
    }
  }

  /**
   * Builds the final request body by merging the base request with extra parameters.
   *
   * Protected fields (model, messages, system, tools, tool_choice) cannot be overridden
   * via extraParams and will be silently ignored if present.
   *
   * For non-protected fields, extra params use last-write-wins strategy,
   * meaning extraParams will override any existing field in the request.
   *
   * @param request The base AnthropicMessagesRequest
   * @param extraParams Optional JSON object with additional API parameters
   * @return JsonElement representing the complete request body
   */
  internal fun buildRequestBody(request: AnthropicMessagesRequest, extraParams: JsonObject?): JsonElement {
    val json = Json { encodeDefaults = true; explicitNulls = false }
    if (extraParams == null) return json.encodeToJsonElement(request)

    val requestJson = json.encodeToJsonElement(request).jsonObject.toMutableMap()
    extraParams.forEach { (key, value) ->
      if (key in protectedFields) {
        // Silently ignore protected field override attempt to prevent information disclosure
      } else {
        // Extra params override existing non-protected fields (last-write-wins)
        requestJson[key] = value
      }
    }
    return normalizeExtendedThinking(JsonObject(requestJson))
  }

  /**
   * With extended thinking, `temperature` must stay unset and `max_tokens` must exceed
   * `thinking.budget_tokens`; runs after the extraParams merge so overrides can't break this.
   */
  internal fun normalizeExtendedThinking(body: JsonObject): JsonObject {
    val thinking = body["thinking"] as? JsonObject ?: return body
    if ((thinking["type"] as? JsonPrimitive)?.contentOrNull != "enabled") return body
    val fields = body.toMutableMap()
    if (fields.remove("temperature") != null) {
      arbigentDebugLog { "Ignoring temperature because Anthropic extended thinking is enabled." }
    }
    val budgetTokens = (thinking["budget_tokens"] as? JsonPrimitive)?.intOrNull
    val requestMaxTokens = (fields["max_tokens"] as? JsonPrimitive)?.intOrNull
    if (budgetTokens != null && (requestMaxTokens == null || requestMaxTokens <= budgetTokens)) {
      val raisedMaxTokens = budgetTokens + DEFAULT_MAX_TOKENS
      arbigentInfoLog(
        "max_tokens ($requestMaxTokens) must exceed thinking.budget_tokens ($budgetTokens); raising max_tokens to $raisedMaxTokens."
      )
      fields["max_tokens"] = JsonPrimitive(raisedMaxTokens)
    }
    return JsonObject(fields)
  }

  internal fun buildTools(agentActionTypes: List<AgentActionType>, mcpTools: List<MCPTool>?): List<AnthropicToolDefinition> {
    return agentActionTypes.map { actionType ->
      val jsonString = """
{
  "type": "object",
  "required": [${ArbigentAiAnswerItems.entries.joinToString(",") { "\"${it.key}\"" }}${
        if (actionType.arguments().isNotEmpty()) ", \"text\"" else ""
      }],
"additionalProperties": false,
"properties": {${
        ArbigentAiAnswerItems.entries.joinToString(",\n") { entry ->
          entry.toJsonString()
        }
      }${
        if (actionType.arguments().isNotEmpty()) {
          ",\n" + actionType.arguments().joinToString(",\n") { it.toJson() }
        } else ""
      }
            }
          }
          """
      val parameters = Json.parseToJsonElement(
        jsonString
      )
      AnthropicToolDefinition(
        name = "perform_${actionType.actionName.lowercase()}",
        description = actionType.actionDescription(),
        inputSchema = parameters.jsonObject
      )
    } + mcpTools.orEmpty().map { tool ->
      // Create a map for the parameters JsonObject
      val parametersMap = mutableMapOf<String, JsonElement>()

      // Add the "type" field
      parametersMap["type"] = JsonPrimitive("object")
      parametersMap["additionalProperties"] = JsonPrimitive(false)

      // Add the "properties" field with the original properties
      parametersMap["properties"] = (tool.inputSchema?.properties ?: JsonObject(emptyMap())).let {
        val entries: List<Map.Entry<String, JsonElement>> = it.entries.toList()
        val additional: List<Map.Entry<String, JsonElement>> = ArbigentAiAnswerItems.entries.flatMap { entry ->
          entry.toJsonObject().entries
        }
        JsonObject(
          (entries + additional).map { (key, value) ->
            key to value
          }.toMap()
        )
      }

      // Add the "required" field; MCP-provided names may need escaping and must be unique.
      val requiredList =
        ((tool.inputSchema?.required ?: emptyList()) + ArbigentAiAnswerItems.entries.map { it.key }).distinct()
      parametersMap["required"] = JsonArray(requiredList.map { JsonPrimitive(it) })

      AnthropicToolDefinition(
        name = "mcp_" + tool.name,
        description = tool.description,
        inputSchema = JsonObject(parametersMap)
      )
    }
  }

  override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
    return retryOnAnthropicRateLimit {
      performAssertImage(imageAssertionInput)
    }
  }

  private fun performAssertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
    val assertions = imageAssertionInput.assertions.assertions
    val imageContents = imageAssertionInput.screenshotFilePaths.map { filePath ->
      AnthropicContent(
        type = "image",
        source = AnthropicImageSource(
          // Screenshots may be WebP depending on ArbigentAiOptions.imageFormat, not always PNG.
          mediaType = mediaTypeForImageFile(filePath),
          data = File(filePath).readImageBase64()
        )
      )
    }
    val assertionPromptsText = assertions.withIndex().joinToString("\n") { (index, assertion) ->
      "${index + 1}. ${assertion.assertionPrompt} (required fulfillment percent: ${assertion.requiredFulfillmentPercent})"
    }
    val textContent = AnthropicContent(
      type = "text",
      text = "Evaluate the following assertions against the provided image(s), in order:\n$assertionPromptsText"
    )
    val schema = generateRootJsonSchema(
      descriptor = serializer<AnthropicAssertionEvaluation>().descriptor,
      apiType = GenerateJsonSchemaApiType.OpenAI
    )["schema"]!!.jsonObject
    val toolDefinition = AnthropicToolDefinition(
      name = "report_assertion_results",
      description = "Report the fulfillment evaluation for each assertion, in the same order they were given.",
      inputSchema = schema
    )
    val request = AnthropicMessagesRequest(
      model = modelName,
      maxTokens = maxTokens,
      system = listOf(AnthropicContent(type = "text", text = ArbigentPrompts.imageAssertionSystemPrompt)),
      messages = listOf(
        AnthropicMessage(role = "user", content = imageContents + textContent)
      ),
      tools = listOf(toolDefinition),
      toolChoice = AnthropicToolChoice(type = "tool", name = toolDefinition.name),
    )
    val requestUuid = "image-assertion-" + UUID.randomUUID()
    val responseText = createMessage(requestUuid, request, aiOptions = null)
    val response = Json { ignoreUnknownKeys = true }.decodeFromString<AnthropicMessagesResponse>(responseText)
    val toolUse = response.content.firstOrNull { it.type == "tool_use" }
      ?: throw ArbigentAi.FailedToParseResponseException(
        "No tool use in assertion response", IllegalStateException("No tool use in response: $responseText")
      )
    val resultsJson = toolUse.input
      ?: throw ArbigentAi.FailedToParseResponseException(
        "Tool use has no input", IllegalStateException("No input in tool use: $responseText")
      )
    val evaluation = try {
      Json { ignoreUnknownKeys = true }.decodeFromJsonElement<AnthropicAssertionEvaluation>(resultsJson)
    } catch (e: Exception) {
      throw ArbigentAi.FailedToParseResponseException("Failed to parse assertion response: ${e.message}", e)
    }
    if (evaluation.results.size != assertions.size) {
      throw ArbigentAi.FailedToParseResponseException(
        "Expected ${assertions.size} assertion results but got ${evaluation.results.size}",
        IllegalStateException("Assertion result count mismatch")
      )
    }
    return ArbigentAi.ImageAssertionOutput(
      results = evaluation.results.mapIndexed { index, result ->
        val requiredPercent = assertions.getOrNull(index)?.requiredFulfillmentPercent ?: 80
        ArbigentAi.ImageAssertionResult(
          assertionPrompt = result.assertionPrompt,
          isPassed = result.fulfillmentPercent >= requiredPercent,
          fulfillmentPercent = result.fulfillmentPercent,
          explanation = result.explanation
        )
      }
    )
  }

  internal fun mediaTypeForImageFile(filePath: String): String {
    val extension = File(filePath).extension.lowercase()
    return ImageFormat.entries.firstOrNull { it.fileExtension == extension }?.mimeType
      ?: ImageFormat.PNG.mimeType
  }

  override fun generateScenarios(
    scenarioGenerationInput: ArbigentAi.ScenarioGenerationInput,
  ): GeneratedScenariosContent = retryOnAnthropicRateLimit {
    generateScenariosOnce(scenarioGenerationInput)
  }

  private fun generateScenariosOnce(
    scenarioGenerationInput: ArbigentAi.ScenarioGenerationInput,
  ): GeneratedScenariosContent {
    val scenariosToGenerate = scenarioGenerationInput.scenariosToGenerate
    val appUiStructure = scenarioGenerationInput.appUiStructure
    val customInstruction = scenarioGenerationInput.customInstruction
    val scenariosToBeUsedAsContext = scenarioGenerationInput.scenariosToBeUsedAsContext

    val descriptor = serializer<GeneratedScenariosContent>().descriptor
    val serializersModule = SerializersModule {
      contextual(
        kClass = ArbigentScenarioType::class,
        serializer = ArbigentScenarioType.Scenario.serializer() as KSerializer<ArbigentScenarioType>
      )
    }
    val json = Json {
      ignoreUnknownKeys = true
      isLenient = true
      coerceInputValues = true
      this.serializersModule = serializersModule
    }

    val jsonSchema = generateRootJsonSchema(descriptor, apiType = GenerateJsonSchemaApiType.OpenAI)

    arbigentDebugLog("Generate scenarios: $scenariosToGenerate")
    arbigentDebugLog("App UI structure: $appUiStructure")
    arbigentDebugLog("JsonSchema: $jsonSchema")

    val systemContents = listOf(
      AnthropicContent(
        type = "text",
        text = "You are an AI assistant that generates test scenarios for Android applications. " +
          "Generate scenarios based on the app UI structure and the user's request. " +
          "Each scenario should have a clear goal and be executable by an automated testing system. " +
          "Please split scenarios into appropriately sized chunks that won't confuse the AI. " +
          "Set any unrelated items to null. " +
          "Note: When a scenario depends on another scenario (using scenario.dependency), " +
          "you cannot check the execution content of the dependent scenario. For example, " +
          "if scenario B includes user interactions like button clicks or data entry and scenario C depends on B, " +
          "you cannot verify the specific interactions or data from scenario B in scenario C."
      )
    )

    val userContents = mutableListOf<AnthropicContent>()
    if (customInstruction.isNotEmpty()) {
      userContents.add(AnthropicContent(type = "text", text = "Custom instruction: $customInstruction"))
    }
    userContents.add(AnthropicContent(type = "text", text = "Scenarios to generate: $scenariosToGenerate"))
    if (appUiStructure.isNotBlank()) {
      userContents.add(AnthropicContent(type = "text", text = "App UI structure: $appUiStructure"))
    }
    if (scenariosToBeUsedAsContext.isNotEmpty()) {
      userContents.add(
        AnthropicContent(
          type = "text",
          text = "Here are some existing scenarios for reference:\n\n" +
            scenariosToBeUsedAsContext.joinToString("\n\n") { json.encodeToString(it) }
        )
      )
    }

    val toolDefinition = AnthropicToolDefinition(
      name = "return_generated_scenarios",
      description = "Return the generated test scenarios",
      inputSchema = jsonSchema["schema"]!!.jsonObject
    )
    val request = AnthropicMessagesRequest(
      model = modelName,
      maxTokens = maxTokens,
      system = systemContents,
      messages = listOf(AnthropicMessage(role = "user", content = userContents)),
      tools = listOf(toolDefinition),
      toolChoice = AnthropicToolChoice(type = "tool", name = toolDefinition.name),
    )

    try {
      val requestUuid = scenarioGenerationInput.requestUuid
      val responseText = createMessage(requestUuid, request)

      try {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<AnthropicMessagesResponse>(responseText)
        val toolUse = response.content.firstOrNull { it.type == "tool_use" }
        val input = toolUse?.input

        if (input != null) {
          arbigentDebugLog { "Generated scenarios content: $input" }
          return json.decodeFromJsonElement<GeneratedScenariosContent>(input)
        } else {
          arbigentDebugLog("No tool use in response")
          throw ArbigentAi.FailedToParseResponseException(
            "No content in response",
            IllegalStateException("No content in response")
          )
        }
      } catch (e: Exception) {
        arbigentDebugLog("Failed to parse response: ${e.message}")
        throw ArbigentAi.FailedToParseResponseException("Failed to parse response: ${e.message}", e)
      }
    } catch (e: AnthropicAiRateLimitExceededException) {
      throw e
    } catch (e: Exception) {
      arbigentDebugLog("Error calling Anthropic API: ${e.message}")
      throw ArbigentAi.FailedToParseResponseException("Error calling Anthropic API: ${e.message}", e)
    }
  }

  public companion object {
    /**
     * Default model for the Anthropic API.
     */
    public const val DEFAULT_ANTHROPIC_MODEL: String = "claude-sonnet-4-5"

    /**
     * Default base URL for the Anthropic API.
     */
    public const val DEFAULT_ANTHROPIC_BASE_URL: String = "https://api.anthropic.com/v1/"

    /**
     * `anthropic-version` header value required by the Messages API.
     * See https://docs.anthropic.com/en/api/versioning
     */
    public const val ANTHROPIC_VERSION: String = "2023-06-01"

    /**
     * Default max_tokens sent to the Messages API. Unlike OpenAI, Anthropic requires this field.
     */
    public const val DEFAULT_MAX_TOKENS: Int = 4096

    /**
     * Protected fields that cannot be overridden via extraBody.
     * These are critical API fields that could break functionality or cause security issues.
     */
    internal val protectedFields: Set<String> = setOf("model", "messages", "system", "tools", "tool_choice")

    /**
     * Caps rate-limit backoff retries so a persistently rate-limited or overloaded API can't
     * recurse forever (each retry doubles the wait, so this is already tens of minutes total).
     */
    internal const val MAX_RATE_LIMIT_RETRIES: Int = 6
  }
}

@Serializable
private data class AnthropicAssertionEvaluation(
  val results: List<AnthropicAssertionResultItem>
)

@Serializable
private data class AnthropicAssertionResultItem(
  val assertionPrompt: String,
  val fulfillmentPercent: Int,
  val explanation: String
)

private fun File.readImageBase64(): String {
  return this.readBytes().encodeBase64()
}
