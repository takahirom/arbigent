package io.github.takahirom.arbigent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject


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
  @SerialName("response_format") val responseFormat: ResponseFormat?,
  val temperature: Double? = null,
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
  val content: String
)

@Serializable
public data class Content(
  val type: String,
  val text: String? = null,
  @SerialName("image_url") val imageUrl: ImageUrl? = null
)

@Serializable
public data class ImageUrl(
  val url: String
)

@Serializable
public data class Usage(
  @SerialName("completion_tokens") val completionTokens: Int? = null,
)

@Serializable
public class ApiCall(
  public val requestBody: ChatCompletionRequest,
  public val responseBody: ChatCompletionResponse,
  public val metadata: ApiCallMetadata
)

@Serializable
public class ApiCallMetadata
