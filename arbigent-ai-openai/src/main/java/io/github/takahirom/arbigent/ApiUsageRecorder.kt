package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.ConfidentialInfo.removeConfidentialInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID

/**
 * Token usage of a single API response.
 *
 * This is recorded for every response that goes through the shared HTTP client, so it covers
 * calls that are not written to the jsonls directory - image assertions in particular, which are
 * delegated to Roborazzi and therefore have no [ApiCall] file of their own.
 *
 * The field names are provider neutral because the two OpenAI APIs disagree: Chat Completions
 * reports `prompt_tokens` / `completion_tokens`, the Responses API reports
 * `input_tokens` / `output_tokens`.
 */
@Serializable
public class ApiUsageRecord(
  /**
   * Set only for calls that carry a `requestUuid` query parameter, which today means decision
   * calls. Image assertion calls have no uuid, so a consumer can use this to tell them apart.
   */
  @SerialName("request_uuid") public val requestUuid: String? = null,
  public val model: String? = null,
  @SerialName("input_tokens") public val inputTokens: Int? = null,
  @SerialName("cached_input_tokens") public val cachedInputTokens: Int? = null,
  @SerialName("output_tokens") public val outputTokens: Int? = null,
  @SerialName("total_tokens") public val totalTokens: Int? = null,
)

private val apiUsageJson = Json {
  ignoreUnknownKeys = true
  isLenient = true
  encodeDefaults = true
  explicitNulls = false
}

/**
 * Reads the token usage out of a raw response body.
 *
 * Returns null when the body has no `usage` object. Error responses such as rate limits have no
 * usage and are not billed, so skipping them keeps "one file per recorded call" equal to "one
 * billed call".
 */
internal fun parseApiUsageRecord(requestUuid: String?, responseBody: String): ApiUsageRecord? {
  val root = runCatching { apiUsageJson.parseToJsonElement(responseBody).jsonObject }
    .getOrNull() ?: return null
  val usage = runCatching { root["usage"]?.jsonObject }.getOrNull() ?: return null
  fun int(key: String): Int? = runCatching { usage[key]?.jsonPrimitive?.intOrNull }.getOrNull()
  // Chat Completions nests the cached count under prompt_tokens_details, the Responses API under
  // input_tokens_details. Look in both instead of guessing which API produced this body.
  fun cachedInt(): Int? = listOf("prompt_tokens_details", "input_tokens_details")
    .firstNotNullOfOrNull { key ->
      runCatching { usage[key]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull }
        .getOrNull()
    }
  return ApiUsageRecord(
    requestUuid = requestUuid,
    model = runCatching { root["model"]?.jsonPrimitive?.contentOrNull }.getOrNull(),
    inputTokens = int("prompt_tokens") ?: int("input_tokens"),
    cachedInputTokens = cachedInt(),
    outputTokens = int("completion_tokens") ?: int("output_tokens"),
    totalTokens = int("total_tokens"),
  )
}

/** Response bytes to inspect. Usage sits at the end of the body, so this has to fit the whole body. */
internal const val API_USAGE_PEEK_BYTE_LIMIT: Long = 8L * 1024 * 1024

/**
 * Writes one file per recorded call. Recording is observational, so every failure is dropped
 * silently rather than breaking the API call that is being observed.
 *
 * The record holds only a uuid, a model name and token counts, so it is not expected to contain
 * the API key. It still goes through [removeConfidentialInfo] because every other file this class
 * writes does, and a file that leaks a key is far worse than one redundant string replacement.
 */
internal fun writeApiUsageRecord(record: ApiUsageRecord) {
  runCatching {
    val dir = ArbigentFiles.usagesDir
    dir.mkdirs()
    File(dir, "${UUID.randomUUID()}.json")
      .writeText(apiUsageJson.encodeToString(record).removeConfidentialInfo())
  }
}
