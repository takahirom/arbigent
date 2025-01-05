package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class ArbigentProjectFileContentTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbigentCoroutinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!

    val projectFileContent: ArbigentProjectFileContent = ArbigentProjectSerializer().load(
      File(this::class.java.getResource("/projects/nowinandroidsample.yaml")!!.toURI())
    )
    projectFileContent.scenarioContents.forEach { scenarioContent ->
      val executorScenario = projectFileContent.scenarioContents.createArbigentScenario(
        scenario = scenarioContent,
        aiFactory = { FakeAi() },
        deviceFactory = { FakeDevice() }
      )
      val executor = ArbigentScenarioExecutor()
      executor.execute(executorScenario)
      assertTrue {
        executor.isGoalArchived()
      }
    }
  }

  @Test
  fun loadProjectTest() {
    val project = ArbigentProjectSerializer().load(
      File(this::class.java.getResource("/projects/nowinandroidsample.yaml")!!.toURI())
    )
    assertEquals(2, project.scenarioContents.size)
    val firstTask = project.scenarioContents.createArbigentScenario(
      scenario = project.scenarioContents[0],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() }
    ).agentTasks
    assertEquals(1, firstTask.size)

    val secondTask = project.scenarioContents.createArbigentScenario(
      scenario = project.scenarioContents[1],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() }
    ).agentTasks
    assertEquals(2, secondTask.size)
  }
}
