package io.github.takahirom.arbigent.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliExtensionsTest {

  @Test
  fun `api key option keys are detected as sensitive`() {
    assertTrue(isSensitiveOptionKey("openai-api-key"))
    assertTrue(isSensitiveOptionKey("openai-key"))
    assertTrue(isSensitiveOptionKey("gemini-api-key"))
    assertTrue(isSensitiveOptionKey("azure-openai-api-key"))
    assertTrue(isSensitiveOptionKey("azure-openai-key"))
  }

  @Test
  fun `non-secret option keys are not detected as sensitive`() {
    assertFalse(isSensitiveOptionKey("os"))
    assertFalse(isSensitiveOptionKey("max-step"))
    assertFalse(isSensitiveOptionKey("openai-endpoint"))
    assertFalse(isSensitiveOptionKey("openai-model-name"))
    assertFalse(isSensitiveOptionKey("project-file"))
  }

  @Test
  fun `masking fully hides the value regardless of length`() {
    assertEquals("****", maskSensitiveValue("ab"))
    assertEquals("****", maskSensitiveValue("abcd"))
    assertEquals("****", maskSensitiveValue("abcde"))
    assertEquals(
      "****",
      maskSensitiveValue("sk-test-0000000000000000000000000000000000000000000000000000000000000067f")
    )
  }

  @Test
  fun `masking an empty value returns empty`() {
    assertEquals("", maskSensitiveValue(""))
  }

  @Test
  fun `masked output is a fixed mask containing no part of the secret`() {
    val secret = "sk-super-secret-1234567890"
    val masked = maskSensitiveValue(secret)
    assertEquals("****", masked)
    assertFalse(secret.contains(masked))
  }
}
