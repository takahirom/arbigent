package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.AgentConfig
import io.github.takahirom.arbigent.ArbigentAgent
import io.github.takahirom.arbigent.ArbigentAgentTask
import io.github.takahirom.arbigent.ArbigentCoroutinesDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ArbigentAgentExecutorTest {
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
      .execute(task)

    advanceUntilIdle()
  }
}
