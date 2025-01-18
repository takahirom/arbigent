package io.github.takahirom.arbigent

import com.github.takahirom.roborazzi.AiAssertionOptions
import com.github.takahirom.roborazzi.AnySerializer
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.OpenAiAiAssertionModel
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeout.Plugin.INFINITE_TIMEOUT_MS
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.encodeBase64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import java.awt.image.BufferedImage.TYPE_INT_RGB
import java.io.File
import java.nio.charset.Charset

public class ArbigentAiRateLimitExceededException : Exception("Rate limit exceeded")

@OptIn(ExperimentalRoborazziApi::class, ExperimentalSerializationApi::class)
public class OpenAIAi(
  private val apiKey: String,
  private val baseUrl: String = "https://api.openai.com/v1/",
  private val modelName: String = "gpt-4o-mini",
  private val requestBuilderModifier: HttpRequestBuilder.() -> Unit = {
    header("Authorization", "Bearer $apiKey")
  },
  loggingEnabled: Boolean = false,
  private val openAiImageAssertionModel: OpenAiAiAssertionModel = OpenAiAiAssertionModel(
    apiKey = apiKey,
    baseUrl = baseUrl,
    modelName = modelName,
    loggingEnabled = loggingEnabled,
    requestBuilderModifier = requestBuilderModifier,
    seed = null
  ),
  private val httpClient: HttpClient = HttpClient {
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
            Logger.SIMPLE.log(
              message.replace(
                apiKey,
                "****"
              )
            )
          }
        }
        level = LogLevel.ALL
      }
    }
  },
) : ArbigentAi {
  private var retried = 0

  @OptIn(ExperimentalSerializationApi::class)
  override fun decideAgentCommands(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
    val arbigentContext = decisionInput.arbigentContextHolder
    val screenshotFilePath = decisionInput.screenshotFilePath
    val formFactor = decisionInput.formFactor
    val uiTreeStrings = decisionInput.uiTreeStrings
    val focusedTree = decisionInput.focusedTreeString
    val agentCommandTypes = decisionInput.agentCommandTypes
    val elements = decisionInput.elements

    val original = File(screenshotFilePath)
    val canvas = ArbigentCanvas.load(original, elements.screenWidth, TYPE_INT_RGB)
    canvas.draw(elements)
    canvas.save(original.getAnnotatedFilePath())

    val imageBase64 = File(screenshotFilePath).getResizedIamgeBase64(1.0F)
    val prompt =
      buildPrompt(arbigentContext, uiTreeStrings.optimizedTreeString, focusedTree, agentCommandTypes, elements)
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
              url = "data:image/png;base64,$imageBase64"
            )
          ),
          Content(
            type = "text",
            text = prompt
          ),
        )
      )
    )
    val responseText = try {
      chatCompletion(
        agentCommandTypes = agentCommandTypes,
        messages = messages,
      )
    } catch (e: ArbigentAiRateLimitExceededException) {
      arbigentInfoLog("Rate limit exceeded. Waiting for ${10L * (1 shl retried)} seconds.")
      Thread.sleep(10000L * (1 shl retried))
      retried++
      return decideAgentCommands(decisionInput)
    }
    retried = 0
    try {
      val step = try {
        parseResponse(responseText, messages, decisionInput)
      } catch (e: ArbigentAi.FailedToParseResponseException) {
        ArbigentContextHolder.Step(
          feedback = "Failed to parse AI response: ${e.message}",
          screenshotFilePath = screenshotFilePath,
          aiRequest = messages.toHumanReadableString(),
          aiResponse = responseText,
          uiTreeStrings = uiTreeStrings,
        )
      }
      return ArbigentAi.DecisionOutput(listOfNotNull(step.agentCommand), step)
    } catch (e: MissingFieldException) {
      arbigentInfoLog("Missing required field in OpenAI response: $e $responseText")
      throw e
    }
  }

  private fun buildPrompt(
    arbigentContextHolder: ArbigentContextHolder,
    dumpHierarchy: String,
    focusedTree: String?,
    agentCommandTypes: List<AgentCommandType>,
    elements: ArbigentElementList
  ): String {
    val contextPrompt = arbigentContextHolder.prompt()
    val templates = agentCommandTypes.joinToString("\nor\n") { it.templateForAI() }
    val focusedTreeText = focusedTree?.let { "\nCurrently focused Tree:\n$it\n\n" } ?: ""
    val prompt = """

UI Index to Element Map:
${elements.getAiTexts()}
$focusedTreeText
Based on the above, decide on the next action to achieve the goal. Please ensure not to repeat the same action. The action must be one of the following:
$templates"""
    return contextPrompt + (prompt.trimIndent())
  }

  private fun parseResponse(
    response: String,
    messages: List<ChatMessage>,
    decisionInput: ArbigentAi.DecisionInput,
  ): ArbigentContextHolder.Step {
    val screenshotFilePath = decisionInput.screenshotFilePath
    val elements = decisionInput.elements
    val agentCommandList = decisionInput.agentCommandTypes

    val json = Json { ignoreUnknownKeys = true }
    val responseObj = json.decodeFromString<ChatCompletionResponse>(response)
    val content = responseObj.choices.firstOrNull()?.message?.content ?: ""
    return try {
      val jsonElement = json.parseToJsonElement(content)
      val jsonObject = jsonElement.jsonObject
      val action =
        jsonObject["action"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Action not found")
      val agentCommandMap = agentCommandList.associateBy { it.actionName }
      val commandPrototype = agentCommandMap[action] ?: throw IllegalArgumentException("Unknown action: $action")
      val agentCommand: ArbigentAgentCommand = when (commandPrototype) {
        GoalAchievedAgentCommand -> GoalAchievedAgentCommand()
        FailedAgentCommand -> FailedAgentCommand()
        ClickWithTextAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          ClickWithTextAgentCommand(text)
        }

        ClickWithIdAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          ClickWithIdAgentCommand(text)
        }

        DpadUpArrowAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          DpadUpArrowAgentCommand(text.toIntOrNull() ?: 1)
        }

        DpadDownArrowAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          DpadDownArrowAgentCommand(text.toIntOrNull() ?: 1)
        }

        DpadLeftArrowAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          DpadLeftArrowAgentCommand(text.toIntOrNull() ?: 1)
        }

        DpadRightArrowAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          DpadRightArrowAgentCommand(text.toIntOrNull() ?: 1)
        }

        DpadCenterAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          DpadCenterAgentCommand(text.toIntOrNull() ?: 1)
        }

        DpadAutoFocusWithIdAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          DpadAutoFocusWithIdAgentCommand(text)
        }

        DpadAutoFocusWithTextAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          DpadAutoFocusWithTextAgentCommand(text)
        }

        DpadAutoFocusWithIndexAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          val index = text.toIntOrNull()
            ?: throw IllegalArgumentException("text should be a number for ${DpadAutoFocusWithIndexAgentCommand.actionName}")
          if (elements.elements.size <= index) {
            throw IllegalArgumentException("Index out of bounds: $index")
          }
          DpadAutoFocusWithIndexAgentCommand(index, elements)
        }

        InputTextAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          InputTextAgentCommand(text)
        }

        ClickWithIndex -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          val index = text.toIntOrNull()
            ?: throw IllegalArgumentException("text should be a number for ${ClickWithIndex.actionName}")
          if (elements.elements.size <= index) {
            throw IllegalArgumentException("Index out of bounds: $index")
          }
          ClickWithIndex(
            index = index,
            elements = elements
          )
        }

        BackPressAgentCommand -> BackPressAgentCommand()

        KeyPressAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          KeyPressAgentCommand(text)
        }

        WaitAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Text not found")
          WaitAgentCommand(text.toIntOrNull() ?: 1000)
        }

        ScrollAgentCommand -> ScrollAgentCommand()

        else -> throw IllegalArgumentException("Unsupported action: $action")
      }
      ArbigentContextHolder.Step(
        agentCommand = agentCommand,
        action = action,
        imageDescription = jsonObject["image-description"]?.jsonPrimitive?.content ?: "",
        thought = jsonObject["thought"]?.jsonPrimitive?.content ?: "",
        aiRequest = messages.toHumanReadableString(),
        aiResponse = content,
        screenshotFilePath = screenshotFilePath,
        uiTreeStrings = decisionInput.uiTreeStrings
      )
    } catch (e: Exception) {
      throw ArbigentAi.FailedToParseResponseException(
        "Failed to parse AI response: ${e.message}",
        e
      )
    }
  }

  private fun chatCompletion(
    agentCommandTypes: List<AgentCommandType>,
    messages: List<ChatMessage>
  ): String {
    return runBlocking {
      val response: HttpResponse =
        httpClient.post(baseUrl + "chat/completions") {
          requestBuilderModifier()
          contentType(ContentType.Application.Json)
          setBody(
            ChatCompletionRequest(
              model = modelName,
              messages = messages,
              ResponseFormat(
                type = "json_schema",
                jsonSchema = buildActionSchema(agentCommandTypes = agentCommandTypes),
              ),
            )
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

  private fun buildActionSchema(agentCommandTypes: List<AgentCommandType>): JsonObject {
    val actions = agentCommandTypes.map { it.actionName }.joinToString { "\"$it\"" }
    val schemaJson = """
    {
      "name": "ActionSchema",
      "description": "Schema for user actions",
      "strict": true,
      "schema": {
        "type": "object",
        "required": ["image-description","thought",  "action", "text"],
        "additionalProperties": false,
        "properties": {
          "image-description": {
            "type": "string"
          },
          "thought": {
            "type": "string"
          },
          "action": {
            "type": "string",
            "enum": [$actions]
          },
          "text": {
            "type": "string",
            "nullable": true
          }
        }
      }
    }
    """.trimIndent()
    return Json.parseToJsonElement(schemaJson).jsonObject
  }

  @OptIn(ExperimentalRoborazziApi::class)
  override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
    fun assert(retry: Int = 0): ArbigentAi.ImageAssertionOutput {
      try {
        val result = openAiImageAssertionModel.assert(
          referenceImageFilePath = imageAssertionInput.screenshotFilePath,
          comparisonImageFilePath = imageAssertionInput.screenshotFilePath,
          actualImageFilePath = imageAssertionInput.screenshotFilePath,
          aiAssertionOptions = AiAssertionOptions(
            openAiImageAssertionModel,
            aiAssertions = imageAssertionInput.assertions.map { assertion ->
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
          Thread.sleep(10000L * (1 shl retry))
          arbigentDebugLog("Retrying assertion: retryCount: $retry. Wait for ${10L * (1 shl retried)}")
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


