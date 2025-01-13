package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ArbigentScenarioContentExecutorTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbigentCoroutinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    val agentConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      ai(FakeAi())
    }
    val arbigentScenarioExecutor = ArbigentScenarioExecutor {
    }
    val arbigentScenario = ArbigentScenario(
      id = "id2",
      agentTasks = listOf(
        ArbigentAgentTask("id1", "goal1", agentConfig),
        ArbigentAgentTask("id2", "goal2", agentConfig)
      ),
      maxStepCount = 10,
      isLeaf = true,
    )
    arbigentScenarioExecutor.execute(
      arbigentScenario
    )
    advanceUntilIdle()
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun lastInterceptorCalledFirst() = runTest {
    ArbigentCoroutinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    val order = mutableListOf<Int>()
    val agentConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      ai(FakeAi())
      addInterceptor(
        object : ArbigentInitializerInterceptor {
          override fun intercept(
            device: ArbigentDevice,
            chain: ArbigentInitializerInterceptor.Chain
          ) {
            order.add(200)
            chain.proceed(device)
            order.add(300)
          }
        }
      )
      addInterceptor(
        object : ArbigentInitializerInterceptor {
          override fun intercept(
            device: ArbigentDevice,
            chain: ArbigentInitializerInterceptor.Chain
          ) {
            order.add(100)
            chain.proceed(device)
            order.add(400)
          }
        }
      )
    }
    val arbigentScenarioExecutor = ArbigentScenarioExecutor {
    }
    val arbigentScenario = ArbigentScenario(
      id = "id2",
      listOf(
        ArbigentAgentTask("id1", "goal1", agentConfig),
        ArbigentAgentTask("id2", "goal2", agentConfig)
      ),
      maxStepCount = 10,
      isLeaf = true
    )
    arbigentScenarioExecutor.execute(
      arbigentScenario
    )
    advanceUntilIdle()
    assertEquals(listOf(100, 200, 300, 400, 100, 200, 300, 400), order)
  }
}
