package io.github.takahirom.arbigent

import org.junit.Assert.*
import org.junit.Test

class OpenAIAiTest {
  @OptIn(ArbigentInternalApi::class)
  @Test
  fun test() {
    assertEquals(false, OpenAIAi("apiKey").loggingEnabled)
  }
}