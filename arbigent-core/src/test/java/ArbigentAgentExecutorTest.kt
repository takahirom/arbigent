package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ArbigentAgentExecutorTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun testCacheKeyFormat() = runTest {
    ArbigentCoroutinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!

    val testDevice = FakeDevice()
    val cacheKeyCapture = FakeAi.Status.CacheKeyCapture()
    val testAi = FakeAi().apply {
      status = cacheKeyCapture
    }

    val agentConfig = AgentConfig {
      deviceFactory { testDevice }
      ai(testAi)
    }

    val task = ArbigentAgentTask("id1", "Test goal", agentConfig)
    ArbigentAgent(agentConfig).execute(task, MCPClient())
    advanceUntilIdle()

    // Verify cache key format
    val cacheKey = assertNotNull(cacheKeyCapture.capturedCacheKey, "Cache key should not be null")

    // Verify essential components are present and in correct order
    val keyPattern = Regex("v.+?-uitree-[^-]+-context-[^-]+")
    assertTrue(cacheKey.matches(keyPattern), 
      """
      Cache key should match pattern: v{version}-uitree-{hash}-context-{hash}
      Actual: $cacheKey
      """.trimIndent())
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbigentCoroutinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    val agentConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      ai(FakeAi())
    }

    val task = ArbigentAgentTask("id1", "goal1", agentConfig)
    ArbigentAgent(agentConfig)
      .execute(task, MCPClient())

    advanceUntilIdle()
  }
}
