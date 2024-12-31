package com.github.takahirom.arbiter

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.image.BufferedImage
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class OpenAIAi(
  private val apiKey: String,
  private val model: String = "gpt-4o-mini",
  private val requestBuilder: (String) -> Request = { requestBodyJson ->
    createRequest(requestBodyJson, apiKey)
  }
) : Ai {
  override fun decideWhatToDo(decisionInput: Ai.DecisionInput): Ai.DecisionOutput {
    val (arbiterContext, dumpHierarchy, focusedTree, agentCommandTypes, screenshotFileName) = decisionInput
    val prompt = buildPrompt(arbiterContext, dumpHierarchy, focusedTree, agentCommandTypes)
    val messages: List<ChatMessage> = listOf(
      ChatMessage(
        role = "system",
        content = listOf(
          Content(
            type = "text",
            text = "You are an agent that achieve the user's goal automatically. Please be careful not to repeat the same action."
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
    val responseText = chatCompletion(
      agentCommandTypes = agentCommandTypes,
      messages = messages,
    )
    val step = parseResponse(responseText, messages, screenshotFileName, agentCommandTypes)
    return Ai.DecisionOutput(listOf(step.agentCommand!!), step)
  }

  private fun buildPrompt(
    arbiterContextHolder: ArbiterContextHolder,
    dumpHierarchy: String,
    focusedTree: String?,
    agentCommandTypes: List<AgentCommandType>
  ): String {
    val contextPrompt = arbiterContextHolder.prompt()
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
    screenshotFileName: String,
    agentCommandList: List<AgentCommandType>
  ): ArbiterContextHolder.Step {
    val json = Json { ignoreUnknownKeys = true }
    val responseObj = json.decodeFromString<ChatCompletionResponse>(response)
    val content = responseObj.choices.firstOrNull()?.message?.content ?: ""
    println("OpenAI content: $content")
    return try {
      val jsonElement = json.parseToJsonElement(content)
      val jsonObject = jsonElement.jsonObject
      val action =
        jsonObject["action"]?.jsonPrimitive?.content ?: throw Exception("Action not found")
      val agentCommandMap = agentCommandList.associateBy { it.actionName }
      val commandPrototype = agentCommandMap[action] ?: throw Exception("Unknown action: $action")
      val agentCommand: AgentCommand = when (commandPrototype) {
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

        ScrollAgentCommand -> ScrollAgentCommand()

        else -> throw Exception("Unsupported action: $action")
      }
      ArbiterContextHolder.Step(
        agentCommand = agentCommand,
        action = action,
        memo = jsonObject["memo"]?.jsonPrimitive?.content ?: "",
        whatYouSaw = jsonObject["summary-of-what-you-saw"]?.jsonPrimitive?.content ?: "",
        aiRequest = message.toString(),
        aiResponse = content,
        screenshotFileName = screenshotFileName
      )
    } catch (e: Exception) {
      throw Exception("Failed to parse OpenAI response: $e")
    }
  }

  private fun chatCompletion(
    agentCommandTypes: List<AgentCommandType>,
    messages: List<ChatMessage>
  ): String {
    val client = OkHttpClient.Builder()
      .readTimeout(60, TimeUnit.SECONDS)
      .build()
    val json = Json { ignoreUnknownKeys = true }
    val requestBodyJson = json.encodeToString(
      ChatCompletionRequest(
        model = model,
        messages = messages,
        ResponseFormat(
          type = "json_schema",
          jsonSchema = buildActionSchema(agentCommandTypes = agentCommandTypes),
        ),
      )
    )
    val request = requestBuilder(requestBodyJson)
    println(
      "OpenAI request: ${
        messages.flatMap { it.content }.filter { it.type == "text" }.joinToString("\n")
      }"
    )
    val response = client.newCall(request).execute()
    val responseBody = response.body?.string() ?: ""
    println("OpenAI response: $responseBody")
    return responseBody
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

private fun createRequest(requestBodyJson: String, apiKey: String): Request {
  val requestBody = requestBodyJson
    .toRequestBody("application/json".toMediaType())
  val request = Request.Builder()
    .url("https://api.openai.com/v1/chat/completions")
    .header("Authorization", "Bearer $apiKey")
    .post(requestBody)
    .build()
  return request
}
