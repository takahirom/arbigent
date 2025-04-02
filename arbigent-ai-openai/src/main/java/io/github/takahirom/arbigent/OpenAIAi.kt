package io.github.takahirom.arbigent

import com.github.takahirom.roborazzi.AiAssertionOptions
import com.github.takahirom.roborazzi.AiAssertionOptions.AiAssertionModel.TargetImage
import com.github.takahirom.roborazzi.AiAssertionOptions.AiAssertionModel.TargetImages
import com.github.takahirom.roborazzi.AnySerializer
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.OpenAiAiAssertionModel
import io.github.takahirom.arbigent.ConfidentialInfo.removeConfidentialInfo
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
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
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import java.awt.image.BufferedImage.TYPE_INT_RGB
import java.io.File
import java.nio.charset.Charset

public class ArbigentAiRateLimitExceededException : Exception("Rate limit exceeded")

private enum class ArbigentAiAnswerItems(
  val key: String,
  val type: String,
  val description: String,
) {
  Memo("arbigent-memo", "string", "Memo for the agent"),
  ImageDescription("arbigent-mage-description", "string", "Description of what is visible in the image");

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

@OptIn(ExperimentalRoborazziApi::class, ExperimentalSerializationApi::class)
public class OpenAIAi @OptIn(ArbigentInternalApi::class) constructor(
  private val apiKey: String,
  private val baseUrl: String = "https://api.openai.com/v1/",
  private val modelName: String = "gpt-4o-mini",
  private val requestBuilderModifier: HttpRequestBuilder.() -> Unit = {
    header("Authorization", "Bearer $apiKey")
  },
  @property:ArbigentInternalApi
  public val loggingEnabled: Boolean,
  private val httpClient: HttpClient = HttpClient(OkHttp) {
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
          classDiscriminator = "#class"
          explicitNulls = false
          serializersModule = SerializersModule {
            contextual(Any::class, AnySerializer)
          }
        }
      )
    }
    install(HttpTimeout) {
      requestTimeoutMillis =
        INFINITE_TIMEOUT_MS
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
  private val openAiImageAssertionModel: OpenAiAiAssertionModel = OpenAiAiAssertionModel(
    apiKey = apiKey,
    baseUrl = baseUrl,
    modelName = modelName,
    loggingEnabled = loggingEnabled,
    requestBuilderModifier = requestBuilderModifier,
    seed = null,
    httpClient = httpClient
  ),
) : ArbigentAi {
  init {
    ConfidentialInfo.addStringToBeRemoved(apiKey)
  }

  private var retried = 0

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

    val original = File(screenshotFilePath)
    val canvas = ArbigentCanvas.load(original, elements.screenWidth, TYPE_INT_RGB)
    canvas.draw(elements)
    canvas.save(original.getAnnotatedFilePath(), decisionInput.aiOptions)

    val imageBase64 = File(screenshotFilePath).getResizedIamgeBase64(1.0F)
    val prompt =
      buildPrompt(
        contextHolder = contextHolder,
        dumpHierarchy = uiTreeStrings.optimizedTreeString,
        focusedTree = focusedTree,
        agentActionTypes = agentActionTypes,
        elements = elements,
        aiOptions = decisionInput.aiOptions ?: ArbigentAiOptions(),
        tools = decisionInput.mcpTools
      )
    val imageDetail = decisionInput.aiOptions?.imageDetail?.name?.lowercase()
    arbigentDebugLog { "AI imageDetailOption: $imageDetail" }
    val messages: List<ChatMessage> = listOf(
      ChatMessage(
        role = "system",
        contents = when (formFactor) {
          ArbigentScenarioDeviceFormFactor.Tv -> decisionInput.prompt.systemPromptsForTv
          else -> decisionInput.prompt.systemPrompts
        }.map {
          Content(
            type = "text",
            text = it
          )
        } + decisionInput.prompt.additionalSystemPrompts.map {
          Content(
            type = "text",
            text = it
          )
        }
      ),
      ChatMessage(
        role = "user",
        contents = listOf(
          Content(
            type = "image_url",
            imageUrl = ImageUrl(
              url = "data:${decisionInput.aiOptions?.imageFormat?.mimeType ?: ImageFormat.PNG.mimeType};base64,$imageBase64",
              detail = imageDetail
            )
          ),
          Content(
            type = "text",
            text = prompt
          ),
        )
      )
    )
    val toolDefinitions = buildTools(agentActionTypes = agentActionTypes, mcpTools = decisionInput.mcpTools)
    val completionRequest = ChatCompletionRequest(
      model = modelName,
      messages = messages,
      tools = toolDefinitions,
    )
    val responseText = try {
      chatCompletion(
        completionRequest,
        decisionInput.aiOptions
      )
    } catch (e: ArbigentAiRateLimitExceededException) {
      val waitMs = 10000L * (1 shl retried)
      arbigentInfoLog("Rate limit exceeded. Waiting for ${waitMs / 1000} seconds.")
      ArbigentGlobalStatus.onAiRateLimitWait(waitSec = waitMs / 1000) {
        Thread.sleep(waitMs)
      }
      retried++
      return decideAgentActions(decisionInput)
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
    retried = 0
    val json = Json { ignoreUnknownKeys = true }
    var responseObj: ChatCompletionResponse?
    try {
      val step = try {
        responseObj = json.decodeFromString<ChatCompletionResponse>(responseText)
        val file = File(decisionJsonlFilePath)
        file.parentFile.mkdirs()
        file.writeText(
          json.encodeToString(
            ApiCall(
              requestBody = completionRequest,
              responseBody = responseObj,
              metadata = ApiCallMetadata()
            )
          ).removeConfidentialInfo()
        )
        val step = parseResponse(
          json = json,
          chatCompletionResponse = responseObj,
          messages = messages,
          decisionInput = decisionInput,
          toolDefinitions = toolDefinitions,
        )
        step
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
      arbigentInfoLog("Missing required field in OpenAI response: $e $responseText")
      throw e
    }
  }

  private fun buildPrompt(
    contextHolder: ArbigentContextHolder,
    dumpHierarchy: String,
    focusedTree: String?,
    agentActionTypes: List<AgentActionType>,
    elements: ArbigentElementList,
    aiOptions: ArbigentAiOptions,
    tools: List<MCPTool>? = null,
  ): String {
    val focusedTreeText = focusedTree.orEmpty().ifBlank { "No focused tree" }
    val uiElements = elements.getPromptTexts().ifBlank { "No UI elements to select. Please check the image." }

    return contextHolder.prompt(
      uiElements = uiElements,
      focusedTree = focusedTreeText,
      aiOptions = aiOptions
    )
  }

  private fun parseResponse(
    json: Json,
    chatCompletionResponse: ChatCompletionResponse,
    messages: List<ChatMessage>,
    toolDefinitions: List<ToolDefinition>,
    decisionInput: ArbigentAi.DecisionInput,
  ): ArbigentContextHolder.Step {
    val screenshotFilePath = decisionInput.screenshotFilePath
    val elements = decisionInput.elements
    val agentActionList = decisionInput.agentActionTypes
    arbigentInfoLog {
      "AI usage: ${chatCompletionResponse.usage}"
    }

    return try {
      val message = chatCompletionResponse.choices.firstOrNull()?.message
        ?: throw IllegalArgumentException("No message in response")

      // Parse the response and extract the action and parameters
      val (argumentsJsonData, action) = if (message.toolCalls != null && message.toolCalls.isNotEmpty()) {
        // Handle function calling response
        val toolCall = message.toolCalls.first()
        val functionName = toolCall.function.name

        // Extract action name from function name (e.g., "perform_clickwithindex" -> "ClickWithIndex")
        if (!functionName.startsWith("perform_") && !functionName.startsWith("mcp_")) {
          throw IllegalArgumentException("Unknown function: $functionName")
        }

        val actionKey = functionName.removePrefix("perform_")
        // Convert action key to proper case if needed (e.g., "clickwithindex" -> "ClickWithIndex")
        val actionName = agentActionList.find {
          it.actionName.equals(actionKey, ignoreCase = true)
        }?.actionName ?: actionKey

        json.parseToJsonElement(toolCall.function.arguments).jsonObject to actionName
      } else if (message.content != null) {
        // Handle regular response (legacy format)
        val jsonObj = json.parseToJsonElement(message.content).jsonObject
        val actionName = jsonObj["action"]?.jsonPrimitive?.content
          ?: throw IllegalArgumentException("Action not found in response content")
        jsonObj to actionName
      } else {
        throw IllegalArgumentException("No content or tool calls in response")
      }

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
        aiResponse = message.toString(),
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

  private fun arbigentAgentAction(
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
        if (elements.elements.size <= index) {
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
        if (elements.elements.size <= index) {
          throw IllegalArgumentException("Index out of bounds: $index")
        }
        ClickWithIndex(
          index = index,
        )
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


  private fun chatCompletion(
    chatCompletionRequest: ChatCompletionRequest,
    aiOptions: ArbigentAiOptions? = null
  ): String {
    return runBlocking {
      val response: HttpResponse =
        httpClient.post(baseUrl + "chat/completions") {
          requestBuilderModifier()
          contentType(ContentType.Application.Json)
          setBody(
            aiOptions?.temperature?.let { temp ->
              chatCompletionRequest.copy(temperature = temp)
            } ?: chatCompletionRequest
          )
        }
      if (response.status == HttpStatusCode.TooManyRequests) {
        throw ArbigentAiRateLimitExceededException()
      } else if (400 <= response.status.value) {
        throw IllegalStateException(
          "Failed to call API: ${response.status} ${
            response.bodyAsText(
              Charset.defaultCharset()
            )
          }"
        )
      }
      val responseBody = response.bodyAsText()
      return@runBlocking responseBody
    }
  }

  private fun buildTools(agentActionTypes: List<AgentActionType>, mcpTools: List<MCPTool>?): List<ToolDefinition> {
    return agentActionTypes.map { actionType ->
      val jsonString = """
{
  "type": "object",
  "required": [${ArbigentAiAnswerItems.entries.joinToString(",") { it.key }}${
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
      ToolDefinition(
        type = "function",
        function = FunctionDefinition(
          name = "perform_${actionType.actionName.lowercase()}",
          description = actionType.actionDescription(),
          parameters = parameters.jsonObject,
          strict = true
        )
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

      // Add the "required" field with the required properties
      val requiredList = (tool.inputSchema?.required ?: emptyList()) + ArbigentAiAnswerItems.entries.map { it.key }
      val requiredJsonArray =
        Json.parseToJsonElement(requiredList.joinToString(prefix = "[", postfix = "]") { "\"$it\"" })
      parametersMap["required"] = requiredJsonArray

      // Create the parameters JsonObject
      val parameters = JsonObject(parametersMap)

      ToolDefinition(
        type = "function",
        function = FunctionDefinition(
          name = "mcp_" + tool.name,
          description = tool.description,
          parameters = parameters,
          strict = true
        )
      )
    }
  }

  @OptIn(ExperimentalRoborazziApi::class)
  override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
    fun assert(retry: Int = 0): ArbigentAi.ImageAssertionOutput {
      try {
        val result = openAiImageAssertionModel.assert(
          targetImages = TargetImages(
            images = imageAssertionInput.screenshotFilePaths.map { filePath ->
              TargetImage(
                filePath = filePath
              )
            }
          ),
          aiAssertionOptions = AiAssertionOptions(
            openAiImageAssertionModel,
            aiAssertions = imageAssertionInput.assertions.assertions.map { assertion ->
              AiAssertionOptions.AiAssertion(
                assertionPrompt = assertion.assertionPrompt,
                requiredFulfillmentPercent = assertion.requiredFulfillmentPercent
              )
            },
            systemPrompt = ArbigentPrompts.imageAssertionSystemPrompt
          )
        )
        return ArbigentAi.ImageAssertionOutput(
          results = result.aiAssertionResults.map { aiAssertionResult ->
            ArbigentAi.ImageAssertionResult(
              assertionPrompt = aiAssertionResult.assertionPrompt,
              isPassed = aiAssertionResult.fulfillmentPercent >= aiAssertionResult.requiredFulfillmentPercent!!,
              fulfillmentPercent = aiAssertionResult.fulfillmentPercent,
              explanation = aiAssertionResult.explanation
            )
          }
        )
      } catch (e: Exception) {
        if (retry < 6) {
          // TODO: Implement error handling in Roborazzi
          val waitMs = 10000L * (1 shl retry)
          ArbigentGlobalStatus.onAiRateLimitWait(waitSec = waitMs / 1000) {
            Thread.sleep(waitMs)
          }
          arbigentDebugLog("Retrying assertion: retryCount: $retry. Wait for ${waitMs / 1000}")
          return assert(retry + 1)
        } else {
          throw e
        }
      }
    }
    return assert()
  }
}

private fun File.getResizedIamgeBase64(scale: Float): String {
//  val scale = 0.1F
//  val image = ImageIO.read(this)
//  val scaledImage = image.getScaledInstance(
//    (image.width * scale).toInt(),
//    (image.height * scale).toInt(),
//    BufferedImage.SCALE_SMOOTH
//  )
//  val bufferedImage = BufferedImage(
//    scaledImage.getWidth(null),
//    scaledImage.getHeight(null),
//    BufferedImage.TYPE_INT_RGB
//  )
//  bufferedImage.graphics.drawImage(scaledImage, 0, 0, null)
//  val output = File.createTempFile("scaled", ".png")
//  ImageIO.write(bufferedImage, "png", output)
//  return output.readBytes().encodeBase64()
  return this.readBytes().encodeBase64()
}
