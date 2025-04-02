package io.github.takahirom.arbigent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement


@Serializable
public data class ChatMessage(
  val role: String,
  @SerialName("content")
  val contents: List<Content>
)

public fun List<ChatMessage>.toHumanReadableString(): String {
  return buildString {
    for (chatMessage in this@toHumanReadableString) {
      append(chatMessage.role + ": ")
      for (content in chatMessage.contents) {
        appendLine("type:" + content.type + " ")
        when (content.type) {
          "text" -> appendLine(content.text ?: "")
          "image_url" -> appendLine("size:"+ content.imageUrl?.url?.length + " content:" + content.imageUrl?.url?.take(20) + "...")
          else -> appendLine("")
        }
        appendLine()
      }
      appendLine("----")
    }
  }
}

@Serializable
public data class ChatCompletionRequest(
  val model: String,
  val messages: List<ChatMessage>,
  @SerialName("response_format") val responseFormat: ResponseFormat? = null,
  val temperature: Double? = null,
  val tools: List<ToolDefinition>? = null,
  @SerialName("tool_choice") val toolChoice: String = "required",
)

@Serializable
public data class ResponseFormat(
  val type: String,
  @SerialName("json_schema") val jsonSchema: JsonObject
)

@Serializable
public data class ChatCompletionResponse(
  val `object`: String,
  val created: Long,
  val model: String,
  val choices: List<Choice>,
  val usage: Usage? = null
)

@Serializable
public data class Choice(
  val index: Int,
  val message: MessageContent,
  @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
public data class MessageContent(
  val role: String,
  val content: String? = null,
  @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null
)

@Serializable
public data class Content(
  val type: String,
  val text: String? = null,
  @SerialName("image_url") val imageUrl: ImageUrl? = null
)

@Serializable
public data class ImageUrl(
  val url: String,
  val detail: String? = null
)

@Serializable
public data class Usage(
  @SerialName("completion_tokens") val completionTokens: Int? = null,
  @SerialName("prompt_tokens") val promptTokens: Int? = null,
  @SerialName("total_tokens") val totalTokens: Int? = null
)

@Serializable
public class ApiCall(
  public val requestBody: ChatCompletionRequest,
  public val responseBody: ChatCompletionResponse,
  public val metadata: ApiCallMetadata
)

@Serializable
public class ApiCallMetadata

@Serializable
public data class ToolDefinition(
  val type: String = "function",
  val function: FunctionDefinition
)

@Serializable
public data class FunctionDefinition(
  val name: String,
  val description: String? = null,
  val parameters: JsonObject,
  val strict: Boolean = true
)

@Serializable
public sealed class ToolChoice {
  @Serializable
  public data class Auto(
    val type: String = "auto"
  ) : ToolChoice()

  @Serializable
  public data class Required(
    val type: String = "required"
  ) : ToolChoice()

  @Serializable
  public data class None(
    val type: String = "none"
  ) : ToolChoice()

  @Serializable
  public data class Function(
    val type: String = "function",
    val function: FunctionChoice
  ) : ToolChoice()

  public companion object {
    public val Auto: ToolChoice = Auto()
    public val Required: ToolChoice = Required()
    public val None: ToolChoice = None()
  }
}

@Serializable
public data class FunctionChoice(
  val name: String
)

@Serializable
public data class FunctionCall(
  val name: String,
  val arguments: String
)

@Serializable
public data class ToolCall(
  val id: String,
  val type: String = "function_call",
  val function: FunctionCall
)
