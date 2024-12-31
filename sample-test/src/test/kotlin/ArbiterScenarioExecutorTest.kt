package com.github.takahirom.arbiter.sample.test

import com.github.takahirom.arbiter.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import maestro.orchestra.MaestroCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class ArbiterScenarioExecutorTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbiterCorotuinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    val agentConfig = AgentConfig {
      device(FakeDevice())
      ai(FakeAi())
      addInterceptor(
        object : ArbiterInitializerInterceptor {
          override fun intercept(
            device: ArbiterDevice,
            chain: ArbiterInitializerInterceptor.Chain
          ) {
            println("intercept device: $device")
            chain.proceed(device)
          }
        }
      )
    }
    val arbiterScenarioExecutor = ArbiterScenarioExecutor {
    }
    val arbiterExecutorScenario = ArbiterScenarioExecutor.ArbiterExecutorScenario(
      listOf(
        ArbiterScenarioExecutor.ArbiterAgentTask("goal1", agentConfig),
        ArbiterScenarioExecutor.ArbiterAgentTask("goal2", agentConfig)
      ),
      maxStepCount = 10,
    )
    arbiterScenarioExecutor.executeAsync(
      arbiterExecutorScenario
    )
    advanceUntilIdle()
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun lastInterceptorCalledFirst() = runTest {
    ArbiterCorotuinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    val order = mutableListOf<Int>()
    val agentConfig = AgentConfig {
      device(FakeDevice())
      ai(FakeAi())
      addInterceptor(
        object : ArbiterInitializerInterceptor {
          override fun intercept(
            device: ArbiterDevice,
            chain: ArbiterInitializerInterceptor.Chain
          ) {
            order.add(200)
            chain.proceed(device)
            order.add(300)
          }
        }
      )
      addInterceptor(
        object : ArbiterInitializerInterceptor {
          override fun intercept(
            device: ArbiterDevice,
            chain: ArbiterInitializerInterceptor.Chain
          ) {
            order.add(100)
            chain.proceed(device)
            order.add(400)
          }
        }
      )
    }
    val arbiterScenarioExecutor = ArbiterScenarioExecutor {
    }
    val arbiterExecutorScenario = ArbiterScenarioExecutor.ArbiterExecutorScenario(
      listOf(
        ArbiterScenarioExecutor.ArbiterAgentTask("goal1", agentConfig),
        ArbiterScenarioExecutor.ArbiterAgentTask("goal2", agentConfig)
      ),
      maxStepCount = 10,
    )
    arbiterScenarioExecutor.executeAsync(
      arbiterExecutorScenario
    )
    advanceUntilIdle()
    assertEquals(listOf(100, 200, 300, 400, 100, 200, 300, 400), order)
  }
}
