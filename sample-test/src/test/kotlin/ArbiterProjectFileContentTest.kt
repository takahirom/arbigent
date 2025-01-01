package com.github.takahirom.arbiter.sample.test

import com.github.takahirom.arbiter.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class ArbiterProjectFileContentTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbiterCorotuinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!

    val projectFileContent: ArbiterProjectFileContent = ArbiterProjectSerializer().load(
      this::class.java.getResourceAsStream("/projects/nowinandroidsample.yaml")
    )
    projectFileContent.scenarioContents.forEach { scenarioContent ->
      val executorScenario = projectFileContent.scenarioContents.createArbiterScenario(
        scenario = scenarioContent,
        aiFactory = { FakeAi() },
        deviceFactory = { FakeDevice() }
      )
      val executor = ArbiterScenarioExecutor()
      executor.execute(executorScenario)
      assertTrue {
        executor.isGoalArchived()
      }
    }
  }

  @Test
  fun loadProjectTest() {
    val project = ArbiterProjectSerializer().load(
      this::class.java.getResourceAsStream("/projects/nowinandroidsample.yaml")
    )
    assertEquals(2, project.scenarioContents.size)
    val firstTask = project.scenarioContents.createArbiterScenario(
      scenario = project.scenarioContents[0],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() }
    ).agentTasks
    assertEquals(1, firstTask.size)

    val secondTask = project.scenarioContents.createArbiterScenario(
      scenario = project.scenarioContents[1],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() }
    ).agentTasks
    assertEquals(2, secondTask.size)
  }
}
