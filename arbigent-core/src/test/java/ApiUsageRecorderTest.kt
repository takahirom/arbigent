package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.ApiUsageRecord
import io.github.takahirom.arbigent.ArbigentFiles
import io.github.takahirom.arbigent.ConfidentialInfo
import io.github.takahirom.arbigent.parseApiUsageRecord
import io.github.takahirom.arbigent.writeApiUsageRecord
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiUsageRecorderTest {

  private fun usagesDir() = createTempDirectory("usages").toFile().also {
    ArbigentFiles.usagesDir = it
  }

  @Test
  fun parsesChatCompletionsUsage() {
    val record = parseApiUsageRecord(
      requestUuid = "uuid-1",
      responseBody = """
        {
          "object": "chat.completion",
          "model": "test-model",
          "choices": [],
          "usage": {
            "prompt_tokens": 1200,
            "completion_tokens": 34,
            "total_tokens": 1234,
            "prompt_tokens_details": { "cached_tokens": 1024 }
          }
        }
      """.trimIndent()
    )
    requireNotNull(record)
    assertEquals("uuid-1", record.requestUuid)
    assertEquals("test-model", record.model)
    assertEquals(1200, record.inputTokens)
    assertEquals(1024, record.cachedInputTokens)
    assertEquals(34, record.outputTokens)
    assertEquals(1234, record.totalTokens)
  }

  @Test
  fun parsesResponsesApiUsage() {
    // The Responses API names the same numbers differently, and nests the cached count under
    // input_tokens_details instead of prompt_tokens_details.
    val record = parseApiUsageRecord(
      requestUuid = null,
      responseBody = """
        {
          "object": "response",
          "model": "test-model",
          "usage": {
            "input_tokens": 900,
            "output_tokens": 12,
            "total_tokens": 912,
            "input_tokens_details": { "cached_tokens": 512 }
          }
        }
      """.trimIndent()
    )
    requireNotNull(record)
    assertNull(record.requestUuid)
    assertEquals(900, record.inputTokens)
    assertEquals(512, record.cachedInputTokens)
    assertEquals(12, record.outputTokens)
    assertEquals(912, record.totalTokens)
  }

  @Test
  fun parsesAnthropicUsage() {
    // Anthropic reports input_tokens / output_tokens like the Responses API, but has no nested
    // details object. Its cache counters are not a subset of input_tokens, so they are left out
    // rather than reported as cached_input_tokens, which would mean something else.
    val record = parseApiUsageRecord(
      requestUuid = null,
      responseBody = """
        {
          "type": "message",
          "model": "test-model",
          "usage": {
            "input_tokens": 700,
            "output_tokens": 25,
            "cache_read_input_tokens": 400
          }
        }
      """.trimIndent()
    )
    requireNotNull(record)
    assertEquals("test-model", record.model)
    assertEquals(700, record.inputTokens)
    assertEquals(25, record.outputTokens)
    assertNull(record.cachedInputTokens)
  }

  @Test
  fun keepsUsageWithoutCachedTokenDetails() {
    val record = parseApiUsageRecord(
      requestUuid = null,
      responseBody = """{"model":"test-model","usage":{"prompt_tokens":10,"completion_tokens":2}}"""
    )
    requireNotNull(record)
    assertEquals(10, record.inputTokens)
    assertEquals(2, record.outputTokens)
    assertNull(record.cachedInputTokens)
    assertNull(record.totalTokens)
  }

  @Test
  fun returnsNullWhenThereIsNoUsage() {
    // Error responses such as rate limits carry no usage and are not billed, so they must not
    // produce a record. Otherwise the recorded file count would stop matching billed calls.
    assertNull(
      parseApiUsageRecord(
        requestUuid = "uuid-1",
        responseBody = """{"error":{"message":"Rate limit reached","type":"rate_limit_error"}}"""
      )
    )
  }

  @Test
  fun returnsNullForMalformedBody() {
    assertNull(parseApiUsageRecord(requestUuid = null, responseBody = "not json{"))
    assertNull(parseApiUsageRecord(requestUuid = null, responseBody = ""))
  }

  @Test
  fun returnsNullWhenUsageIsNotAnObject() {
    assertNull(parseApiUsageRecord(requestUuid = null, responseBody = """{"usage":"unexpected"}"""))
  }

  @Test
  fun writesOneFilePerRecord() {
    val dir = usagesDir()

    writeApiUsageRecord(ApiUsageRecord(requestUuid = "uuid-1", model = "test-model", inputTokens = 5))
    writeApiUsageRecord(ApiUsageRecord(requestUuid = "uuid-2", model = "test-model", inputTokens = 7))

    val files = dir.listFiles().orEmpty()
    assertEquals(2, files.size)
    assertTrue(files.all { it.readText().contains("test-model") })
  }

  @Test
  fun doesNotWriteConfidentialInfoToTheRecord() {
    val dir = usagesDir()
    val apiKey = "sk-test-should-never-be-written"
    ConfidentialInfo.addStringToBeRemoved(apiKey, "{{API_KEY}}")

    // A record is not supposed to carry the key at all. This asserts that even if a value from the
    // response were to contain it, the written file would not.
    writeApiUsageRecord(ApiUsageRecord(requestUuid = apiKey, model = apiKey, inputTokens = 1))

    val written = dir.listFiles().orEmpty().single().readText()
    assertFalse(written.contains(apiKey))
    assertTrue(written.contains("{{API_KEY}}"))
  }
}
