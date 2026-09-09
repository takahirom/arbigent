package io.github.takahirom.arbigent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
public data class AnthropicMessage(
  val role: String,
  val content: List<AnthropicContent>
)

public fun List<AnthropicMessage>.toHumanReadableString(tools: List<AnthropicToolDefinition>): String {
  return buildString {
    for (message in this@toHumanReadableString) {
      append(message.role + ": ")
      for (content in message.content) {
        appendLine("type:" + content.type + " ")
        when (content.type) {
          "text" -> appendLine(content.text ?: "")
          "image" -> appendLine("size:" + content.source?.data?.length + " media_type:" + content.source?.mediaType)
          else -> appendLine("")
        }
        appendLine()
      }
      appendLine("----")
    }
    appendLine("Tools:")
    for (tool in tools) {
      appendLine("Tool name: " + tool.name)
      appendLine("Tool description: " + tool.description)
      appendLine("Tool parameters: " + tool.inputSchema)
      appendLine("----")
    }
  }
}

@Serializable
public data class AnthropicContent(
  val type: String,
  val text: String? = null,
  val source: AnthropicImageSource? = null,
  val id: String? = null,
  val name: String? = null,
  val input: JsonObject? = null,
  @SerialName("tool_use_id") val toolUseId: String? = null,
  /** Prompt cache breakpoint. Anthropic caches only the prefix up to a block that has one. */
  @SerialName("cache_control") val cacheControl: AnthropicCacheControl? = null,
)

@Serializable
public data class AnthropicCacheControl(
  val type: String,
) {
  public companion object {
    public val Ephemeral: AnthropicCacheControl = AnthropicCacheControl(type = "ephemeral")
  }
}

@Serializable
public data class AnthropicImageSource(
  val type: String = "base64",
  @SerialName("media_type") val mediaType: String,
  val data: String
)

@Serializable
public data class AnthropicMessagesRequest(
  val model: String,
  @SerialName("max_tokens") val maxTokens: Int,
  val system: List<AnthropicContent>? = null,
  val messages: List<AnthropicMessage>,
  val temperature: Double? = null,
  val tools: List<AnthropicToolDefinition>? = null,
  @SerialName("tool_choice") val toolChoice: AnthropicToolChoice? = null,
)

@Serializable
public data class AnthropicToolDefinition(
  val name: String,
  val description: String? = null,
  @SerialName("input_schema") val inputSchema: JsonObject
)

@Serializable
public data class AnthropicToolChoice(
  val type: String,
  val name: String? = null
) {
  public companion object {
    public val Auto: AnthropicToolChoice = AnthropicToolChoice(type = "auto")
    public val Any: AnthropicToolChoice = AnthropicToolChoice(type = "any")
  }
}

@Serializable
public data class AnthropicMessagesResponse(
  val id: String? = null,
  val type: String? = null,
  val role: String? = null,
  val model: String? = null,
  val content: List<AnthropicContent> = emptyList(),
  @SerialName("stop_reason") val stopReason: String? = null,
  val usage: AnthropicUsage? = null
)

/**
 * Anthropic counts cache reads and writes separately from [inputTokens], which holds only the
 * uncached part, unlike OpenAI where the cached count is a subset of the prompt tokens.
 */
@Serializable
public data class AnthropicUsage(
  @SerialName("input_tokens") val inputTokens: Int? = null,
  @SerialName("output_tokens") val outputTokens: Int? = null,
  @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int? = null,
  @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int? = null,
)

@Serializable
public data class AnthropicErrorResponse(
  val type: String? = null,
  val error: AnthropicErrorDetail? = null
)

@Serializable
public data class AnthropicErrorDetail(
  val type: String? = null,
  val message: String? = null
)

@Serializable
public class AnthropicApiCall(
  public val curl: String,
  public val responseBody: AnthropicMessagesResponse,
  public val metadata: AnthropicApiCallMetadata
)

@Serializable
public class AnthropicApiCallMetadata
