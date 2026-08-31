package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.ConfidentialInfo.removeConfidentialInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Token usage of a single API response.
 *
 * This is recorded for every response that goes through a provider's HTTP client, so it covers
 * calls that are not written to the jsonls directory - image assertions in particular, which are
 * delegated to Roborazzi and therefore have no API call file of their own.
 *
 * The field names are provider neutral because the APIs disagree: Chat Completions reports
 * `prompt_tokens` / `completion_tokens`, while the Responses API and Anthropic report
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
  /**
   * Cached part of [inputTokens], read from the OpenAI style nested `cached_tokens`. Left null for
   * providers that count cache reads separately from input tokens instead of as a subset of them,
   * because reporting those here would mean something different.
   */
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
@ArbigentInternalApi
public fun parseApiUsageRecord(requestUuid: String?, responseBody: String): ApiUsageRecord? {
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
    // A usage object with no field this code knows about carries no number worth recording, and a
    // file of nulls would be counted as a call by a consumer.
  ).takeIf { it.inputTokens != null || it.outputTokens != null || it.totalTokens != null }
}

/**
 * Same as [parseApiUsageRecord], but takes the raw response bytes and the `Content-Encoding` header.
 *
 * An OkHttp network interceptor sits below the bridge that performs transparent gzip, so the bytes
 * it sees are still compressed whenever the server compressed them. Parsing those directly finds no
 * usage and silently records nothing, so decode first.
 */
@ArbigentInternalApi
public fun parseApiUsageRecord(
  requestUuid: String?,
  responseBytes: ByteArray,
  contentEncoding: String?,
): ApiUsageRecord? {
  val decoded = if (contentEncoding?.contains("gzip", ignoreCase = true) == true) {
    runCatching {
      GZIPInputStream(ByteArrayInputStream(responseBytes)).use { it.readBytes() }
    }.getOrNull() ?: return null
  } else {
    responseBytes
  }
  return parseApiUsageRecord(requestUuid, decoded.toString(Charsets.UTF_8))
}

/** Response bytes to inspect. Usage sits at the end of the body, so this has to fit the whole body. */
@ArbigentInternalApi
public const val API_USAGE_PEEK_BYTE_LIMIT: Long = 8L * 1024 * 1024

/**
 * Writes one file per recorded call. Recording is observational, so every failure is dropped
 * silently rather than breaking the API call that is being observed.
 *
 * The record holds only a uuid, a model name and token counts, so it is not expected to contain
 * the API key. It still goes through [removeConfidentialInfo] because every other file this class
 * writes does, and a file that leaks a key is far worse than one redundant string replacement.
 */
@ArbigentInternalApi
public fun writeApiUsageRecord(record: ApiUsageRecord) {
  runCatching {
    val dir = ArbigentFiles.usagesDir
    dir.mkdirs()
    File(dir, "${UUID.randomUUID()}.json")
      .writeText(apiUsageJson.encodeToString(record).removeConfidentialInfo())
  }
}
