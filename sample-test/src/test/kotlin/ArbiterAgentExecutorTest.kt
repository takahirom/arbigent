package com.github.takahirom.arbiter.sample.test

import com.github.takahirom.arbiter.AgentConfig
import com.github.takahirom.arbiter.ArbiterAgent
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.ArbiterScenarioExecutor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ArbiterAgentExecutorTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbiterCorotuinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    val agentConfig = AgentConfig {
      device(FakeDevice())
      ai(FakeAi())
    }

    val task = ArbiterScenarioExecutor.ArbiterAgentTask("id1", "goal1", agentConfig)
    ArbiterAgent(agentConfig)
      .execute(task)

    advanceUntilIdle()
  }
}
