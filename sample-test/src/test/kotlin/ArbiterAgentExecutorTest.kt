package io.github.takahirom.arbiter.sample.test

import io.github.takahirom.arbiter.AgentConfig
import io.github.takahirom.arbiter.ArbiterAgent
import io.github.takahirom.arbiter.ArbiterAgentTask
import io.github.takahirom.arbiter.ArbiterCoroutinesDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ArbiterAgentExecutorTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbiterCoroutinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    val agentConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      ai(FakeAi())
    }

    val task = ArbiterAgentTask("id1", "goal1", agentConfig)
    ArbiterAgent(agentConfig)
      .execute(task)

    advanceUntilIdle()
  }
}
