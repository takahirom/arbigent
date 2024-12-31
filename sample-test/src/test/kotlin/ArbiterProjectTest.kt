package com.github.takahirom.arbiter.sample.test

import com.github.takahirom.arbiter.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class ArbiterProjectTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbiterCorotuinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!

    val projectConfig: ArbiterProjectConfig = ArbiterProjectSerializer().load(
      this::class.java.getResourceAsStream("/projects/nowinandroidsample.yaml")
    )
    projectConfig.scenarios.forEach { scenario ->
      val executorScenario = projectConfig.cerateExecutorScenario(
        scenario = scenario,
        aiFactory = { FakeAi() },
        deviceFactory = { FakeDevice() }
      )
      val executor = ArbiterScenarioExecutor()
      executor.execute(executorScenario)
      assertTrue {
        executor.isArchived()
      }
    }
  }

  @Test
  fun loadProjectTest() {
    val project = ArbiterProjectSerializer().load(
      this::class.java.getResourceAsStream("/projects/nowinandroidsample.yaml")
    )
    assertEquals(2, project.scenarios.size)
    val firstTask = project.cerateExecutorScenario(
      scenario = project.scenarios[0],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() }
    ).arbiterAgentTasks
    assertEquals(1, firstTask.size)

    val secondTask = project.cerateExecutorScenario(
      scenario = project.scenarios[1],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() }
    ).arbiterAgentTasks
    assertEquals(2, secondTask.size)
  }
}
