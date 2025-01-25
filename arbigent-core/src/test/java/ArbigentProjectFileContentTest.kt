package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class ArbigentProjectFileContentTest {

  private val basicProject: ArbigentProjectFileContent = ArbigentProjectSerializer().load(
    """scenarios:
- id: "A-ID"
  goal: "A-GOAL"
  initializationMethods:
  - type: "LaunchApp"
    packageName: "com.example"
  cleanupData:
    type: "Cleanup"
    packageName: "com.example"
- id: "B-ID"
  goal: "B-GOAL"
  dependency: "A-ID"
  imageAssertions:
  - assertionPrompt: "B-ASSERTION"
"""
  )
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbigentCoroutinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!

    val projectFileContent: ArbigentProjectFileContent = basicProject
    projectFileContent.scenarioContents.forEach { scenarioContent ->
      val executorScenario = projectFileContent.scenarioContents.createArbigentScenario(
        projectSettings = ArbigentProjectSettings(),
        scenario = scenarioContent,
        aiFactory = { FakeAi() },
        deviceFactory = { FakeDevice() }
      )
      val executor = ArbigentScenarioExecutor()
      executor.execute(executorScenario)
      assertTrue {
        executor.isGoalAchieved()
      }
    }
  }

  @Test
  fun loadProjectTest() {
    assertEquals(2, basicProject.scenarioContents.size)
    val firstTask = basicProject.scenarioContents.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(),
      scenario = basicProject.scenarioContents[0],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() }
    ).agentTasks
    assertEquals(1, firstTask.size)

    val secondTask = basicProject.scenarioContents.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(),
      scenario = basicProject.scenarioContents[1],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() }
    ).agentTasks
    assertEquals(2, secondTask.size)

    val initializationMethods = basicProject.scenarioContents[0].initializationMethods
    assertEquals(
      (initializationMethods[0] as ArbigentScenarioContent.InitializationMethod.LaunchApp).packageName,
      "com.example"
    )
  }

  private val oldInitializeProject = ArbigentProjectSerializer().load(
    """scenarios:
- id: "A-ID"
  goal: "A-GOAL"
  initializeMethods:
    type: "LaunchApp"
    packageName: "com.example"
"""
  )
  @Test
  fun loadOldInitializeProject() {
    val initializeMethods = oldInitializeProject.scenarioContents[0].initializeMethods
    assertEquals(
      (initializeMethods as ArbigentScenarioContent.InitializationMethod.LaunchApp).packageName,
      "com.example"
    )

    val firstTask = oldInitializeProject.scenarioContents.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(),
      scenario = oldInitializeProject.scenarioContents[0],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() }
    ).agentTasks
    assertEquals(1, firstTask.size)

    val interceptors = firstTask[0].agentConfig
      .interceptors
    assertEquals(1, interceptors.size)
    assertTrue(interceptors.any {
        it is ArbigentInitializerInterceptor
      })
  }
}
