package io.github.takahirom.arbigent

import com.github.takahirom.roborazzi.AiAssertionOptions
import com.github.takahirom.roborazzi.AnySerializer
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.OpenAiAiAssertionModel
import io.ktor.client.HttpClient
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.Charset
import java.util.Base64
import javax.imageio.ImageIO

class OpenAIAi(
  private val apiKey: String,
  private val baseUrl: String = "https://api.openai.com/v1/",
  private val modelName: String = "gpt-4o-mini",
  private val systemPrompt: String = ArbigentPrompts.systemPrompt,
  private val systemPromptForTv: String = ArbigentPrompts.systemPrompt,
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
  @OptIn(ExperimentalSerializationApi::class)
  override fun decideAgentCommands(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
    val (arbigentContext, formFactor, dumpHierarchy, focusedTree, agentCommandTypes, screenshotFilePath) = decisionInput
    val prompt = buildPrompt(arbigentContext, dumpHierarchy, focusedTree, agentCommandTypes)
    val messages: List<ChatMessage> = listOf(
      ChatMessage(
        role = "system",
        content = listOf(
          Content(
            type = "text",
            text = when (formFactor) {
              ArbigentScenarioDeviceFormFactor.Tv -> systemPromptForTv
              else -> systemPrompt
            }
          )
        )
      ),
      ChatMessage(
        role = "user",
        content = listOf(
//          Content(
//            type = "image_url",
//            imageUrl = ImageUrl(
//              url = "data:image/png;base64,$imageBase64"
//            )
//          ),
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
    } catch (e: AiRateLimitExceededException) {
      arbigentInfoLog("Rate limit exceeded. Waiting for 10 seconds.")
      Thread.sleep(10000)
      return decideAgentCommands(decisionInput)
    }
    try {
      val step = parseResponse(responseText, messages, screenshotFilePath, agentCommandTypes)
      return ArbigentAi.DecisionOutput(listOf(step.agentCommand!!), step)
    } catch (e: MissingFieldException) {
      arbigentInfoLog("Missing required field in OpenAI response: $e $responseText")
      throw e
    }
  }

  private fun buildPrompt(
    arbigentContextHolder: ArbigentContextHolder,
    dumpHierarchy: String,
    focusedTree: String?,
    agentCommandTypes: List<AgentCommandType>
  ): String {
    val contextPrompt = arbigentContextHolder.prompt()
    val templates = agentCommandTypes.joinToString("\nor\n") { it.templateForAI() }
    val focusedTreeText = focusedTree?.let { "\nCurrently focused Tree:\n$it\n\n" } ?: ""
    val prompt = """


New UI Hierarchy:
$dumpHierarchy
$focusedTreeText
Based on the above, decide on the next action to achieve the goal. Please ensure not to repeat the same action. The action must be one of the following:
$templates"""
    return contextPrompt + (prompt.trimIndent())
  }

  private fun parseResponse(
    response: String,
    message: List<ChatMessage>,
    screenshotFilePath: String,
    agentCommandList: List<AgentCommandType>
  ): ArbigentContextHolder.Step {
    val json = Json { ignoreUnknownKeys = true }
    val responseObj = json.decodeFromString<ChatCompletionResponse>(response)
    val content = responseObj.choices.firstOrNull()?.message?.content ?: ""
    return try {
      val jsonElement = json.parseToJsonElement(content)
      val jsonObject = jsonElement.jsonObject
      val action =
        jsonObject["action"]?.jsonPrimitive?.content ?: throw Exception("Action not found")
      val agentCommandMap = agentCommandList.associateBy { it.actionName }
      val commandPrototype = agentCommandMap[action] ?: throw Exception("Unknown action: $action")
      val agentCommand: ArbigentAgentCommand = when (commandPrototype) {
        GoalAchievedAgentCommand -> GoalAchievedAgentCommand()
        ClickWithTextAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          ClickWithTextAgentCommand(text)
        }

        ClickWithIdAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          ClickWithIdAgentCommand(text)
        }

        DpadUpArrowAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          DpadUpArrowAgentCommand(text.toIntOrNull() ?: 1)
        }

        DpadDownArrowAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          DpadDownArrowAgentCommand(text.toIntOrNull() ?: 1)
        }

        DpadLeftArrowAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          DpadLeftArrowAgentCommand(text.toIntOrNull() ?: 1)
        }

        DpadRightArrowAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          DpadRightArrowAgentCommand(text.toIntOrNull() ?: 1)
        }

        DpadCenterAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          DpadCenterAgentCommand(text.toIntOrNull() ?: 1)
        }

        InputTextAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          InputTextAgentCommand(text)
        }

        BackPressAgentCommand -> BackPressAgentCommand()
        KeyPressAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          KeyPressAgentCommand(text)
        }

        WaitAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          WaitAgentCommand(text.toIntOrNull() ?: 1000)
        }

        ScrollAgentCommand -> ScrollAgentCommand()

        else -> throw Exception("Unsupported action: $action")
      }
      ArbigentContextHolder.Step(
        agentCommand = agentCommand,
        action = action,
        memo = jsonObject["memo"]?.jsonPrimitive?.content ?: "",
        whatYouSaw = jsonObject["summary-of-what-you-saw"]?.jsonPrimitive?.content ?: "",
        aiRequest = message.toString(),
        aiResponse = content,
        screenshotFilePath = screenshotFilePath
      )
    } catch (e: Exception) {
      throw Exception("Failed to parse OpenAI response: $e")
    }
  }

  class AiRateLimitExceededException : Exception("Rate limit exceeded")

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
        throw AiRateLimitExceededException()
      } else if (400 <= response.status.value) {
        throw IllegalStateException(
          "Failed to call API: ${response.status} ${
            response.bodyAsText(
              Charset.defaultCharset()
            )
          }"
        )
      }
      val responseBody = response.bodyAsText() ?: ""
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
        "required": ["summary-of-what-you-saw", "memo",  "action", "text"],
        "additionalProperties": false,
        "properties": {
          "summary-of-what-you-saw": {
            "type": "string"
          },
          "memo": {
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

  private fun readFileAsBase64(filename: String): String {
    val file = File(filename)
    val bytes = file.readBytes()
    return Base64.getEncoder().encodeToString(bytes)
  }

  @OptIn(ExperimentalRoborazziApi::class)
  override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
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
  }
}

fun File.getResizedIamgeByteArray(scale: Float): ByteArray {
  val image = ImageIO.read(this)
  val scaledImage = image.getScaledInstance(
    (image.width * scale).toInt(),
    (image.height * scale).toInt(),
    BufferedImage.SCALE_SMOOTH
  )
  val bufferedImage = BufferedImage(
    scaledImage.getWidth(null),
    scaledImage.getHeight(null),
    BufferedImage.TYPE_INT_RGB
  )
  bufferedImage.graphics.drawImage(scaledImage, 0, 0, null)
  val output = File.createTempFile("scaled", ".png")
  ImageIO.write(bufferedImage, "png", output)
  return output.readBytes()
}

