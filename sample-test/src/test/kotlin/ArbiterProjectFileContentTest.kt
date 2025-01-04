package com.github.takahirom.arbiter.sample.test

import com.github.takahirom.arbiter.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class ArbiterProjectFileContentTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbiterCoroutinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!

    val projectFileContent: ArbiterProjectFileContent = ArbiterProjectSerializer().load(
      File(this::class.java.getResource("/projects/nowinandroidsample.yaml")!!.toURI())
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
      File(this::class.java.getResource("/projects/nowinandroidsample.yaml")!!.toURI())
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
