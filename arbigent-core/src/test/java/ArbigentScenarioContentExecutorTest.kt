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
      tags = setOf(ArbigentContentTag("tag1"), ArbigentContentTag("tag2")),
      isLeaf = true,
    )
    arbigentScenarioExecutor.execute(
      arbigentScenario,
      MCPClient("{}")
    )
    advanceUntilIdle()
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun lastInitializerInterceptorCalledFirst() = runTest {
    ArbigentCoroutinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    val order = mutableListOf<String>()
    val agentConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      ai(FakeAi())
      addInterceptor(
        object : ArbigentInitializerInterceptor {
          override fun intercept(
            device: ArbigentDevice,
            chain: ArbigentInitializerInterceptor.Chain
          ) {
            order.add("initializer interceptor1 start")
            chain.proceed(device)
            order.add("initializer interceptor1 end")
          }
        }
      )
      addInterceptor(
        object : ArbigentInitializerInterceptor {
          override fun intercept(
            device: ArbigentDevice,
            chain: ArbigentInitializerInterceptor.Chain
          ) {
            order.add("initializer interceptor2 start")
            chain.proceed(device)
            order.add("initializer interceptor2 end")
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
      tags = setOf(ArbigentContentTag("tag1"), ArbigentContentTag("tag2")),
      isLeaf = true
    )
    arbigentScenarioExecutor.execute(
      arbigentScenario,
      MCPClient("{}")
    )
    advanceUntilIdle()
    fun List<String>.repeat(times: Int): List<String> {
      return buildList {
        repeat(times) {
          addAll(this@repeat)
        }
      }
    }
    assertEquals(
      listOf(
        "initializer interceptor2 start",
        "initializer interceptor1 start",
        "initializer interceptor1 end",
        "initializer interceptor2 end",
      ).repeat(2),
      order
    )
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun lastStepInterceptorCalledFirst() = runTest {
    ArbigentCoroutinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    val order = mutableListOf<String>()
    val agentConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      ai(FakeAi())
      addInterceptor(
        object : ArbigentStepInterceptor {
          override suspend fun intercept(
            stepInput: ArbigentAgent.StepInput,
            chain: ArbigentStepInterceptor.Chain
          ): ArbigentAgent.StepResult {
            order.add("step interceptor3 start")
            val result = chain.proceed(stepInput)
            order.add("step interceptor3 end")
            return result
          }
        }
      )
      addInterceptor(
        object : ArbigentStepInterceptor {
          override suspend fun intercept(
            stepInput: ArbigentAgent.StepInput,
            chain: ArbigentStepInterceptor.Chain
          ): ArbigentAgent.StepResult {
            order.add("step interceptor4 start")
            val result = chain.proceed(stepInput)
            order.add("step interceptor4 end")
            return result
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
      tags = setOf(ArbigentContentTag("tag1"), ArbigentContentTag("tag2")),
      isLeaf = true
    )
    arbigentScenarioExecutor.execute(
      arbigentScenario,
      MCPClient("{}")
    )
    advanceUntilIdle()
    fun List<String>.repeat(times: Int): List<String> {
      return buildList {
        repeat(times) {
          addAll(this@repeat)
        }
      }
    }
    assertEquals(
      listOf(
        "step interceptor4 start",
        "step interceptor3 start",
        "step interceptor3 end",
        "step interceptor4 end",
        "step interceptor4 start",
        "step interceptor3 start",
        "step interceptor3 end",
        "step interceptor4 end",
      ).repeat(2),
      order
    )
  }
}
