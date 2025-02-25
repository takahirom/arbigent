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
        deviceFactory = { FakeDevice() },
        aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
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
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    ).agentTasks
    assertEquals(1, firstTask.size)

    val secondTask = basicProject.scenarioContents.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(),
      scenario = basicProject.scenarioContents[1],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
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
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    ).agentTasks
    assertEquals(1, firstTask.size)

    val interceptors = firstTask[0].agentConfig
      .interceptors
    assertEquals(2, interceptors.size)
    assertTrue(interceptors.any {
        it is ArbigentInitializerInterceptor
      })
  }

  private val projectWithCustomTemplate = ArbigentProjectSerializer().load(
    """scenarios:
- id: "A-ID"
  goal: "A-GOAL"
  promptTemplate: |
    Task: {{USER_INPUT_GOAL}}

    Current progress: Step {{CURRENT_STEP}} of {{MAX_STEP}}

    Previous steps:
    {{STEPS}}
"""
  )

  @Test
  fun testPromptTemplate() {
    // Test custom template
    val scenarioWithCustomTemplate = projectWithCustomTemplate.scenarioContents[0]
    assertEquals(
      """Task: {{USER_INPUT_GOAL}}

Current progress: Step {{CURRENT_STEP}} of {{MAX_STEP}}

Previous steps:
{{STEPS}}
""".trimEnd(),
      scenarioWithCustomTemplate.promptTemplate.trimEnd()
    )

    // Test default template
    val scenarioWithDefaultTemplate = basicProject.scenarioContents[0]
    assertEquals(PromptTemplate.DEFAULT_TEMPLATE, scenarioWithDefaultTemplate.promptTemplate)

    // Test template in prompt
    val contextHolder = ArbigentContextHolder(
      goal = "test goal",
      maxStep = 10,
      promptTemplate = PromptTemplate(scenarioWithCustomTemplate.promptTemplate)
    )
    val prompt = contextHolder.prompt()
    assertTrue(prompt.contains("Task: test goal"))
    assertTrue(prompt.contains("Current progress: Step 1 of 10"))
  }
}
