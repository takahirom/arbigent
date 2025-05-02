package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


class ArbigentProjectFileContentTest {
  private val defaultAiOptions = ArbigentAiOptions(
    temperature = null,
    imageDetail = null,
    imageFormat = null,
    historicalStepLimit = null
  )

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
      executor.execute(executorScenario, MCPClient())
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
      aiDecisionCache = ArbigentAiDecisionCache.Disabled
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
  userPromptTemplate: |
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
      scenarioWithCustomTemplate.userPromptTemplate.trimEnd()
    )

    // Test default template
    val scenarioWithDefaultTemplate = basicProject.scenarioContents[0]
    assertEquals(UserPromptTemplate.DEFAULT_TEMPLATE, scenarioWithDefaultTemplate.userPromptTemplate)

    // Test template in prompt
    val contextHolder = ArbigentContextHolder(
      goal = "test goal",
      maxStep = 10,
      userPromptTemplate = UserPromptTemplate(scenarioWithCustomTemplate.userPromptTemplate)
    )
    val prompt = contextHolder.context(defaultAiOptions)
    assertTrue(prompt.contains("Task: test goal"))
    assertTrue(prompt.contains("Current progress: Step 1 of 10"))
  }

  private val projectWithAiOptions = ArbigentProjectSerializer().load(
    """
    settings:
      aiOptions:
        temperature: 0.8
    scenarios:
    - id: "A-ID"
      goal: "A-GOAL"
    - id: "B-ID"
      goal: "B-GOAL"
      aiOptions:
        temperature: 0.5
    """
  )

  private val projectWithCacheOptions = ArbigentProjectSerializer().load(
    """
    scenarios:
    - id: "cache-enabled"
      goal: "Test cache enabled"
      cacheOptions:
        forceCacheDisabled: true
    - id: "cache-disabled"
      goal: "Test cache disabled"
      cacheOptions:
        forceCacheDisabled: false
    - id: "cache-default"
      goal: "Test default cache"
    """
  )

  @Test
  fun testCacheOptions() {
    // Test scenario with cache enabled
    // Test scenario with cache override enabled
    val scenarioWithCacheEnabled = projectWithCacheOptions.scenarioContents.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(),
      scenario = projectWithCacheOptions.scenarioContents[0],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    assertEquals(true, scenarioWithCacheEnabled.cacheOptions?.forceCacheDisabled, "Cache override should be disabled")

    // Test scenario with cache override disabled
    val scenarioWithCacheDisabled = projectWithCacheOptions.scenarioContents.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(),
      scenario = projectWithCacheOptions.scenarioContents[1],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    assertEquals(false, scenarioWithCacheDisabled.cacheOptions?.forceCacheDisabled, "Cache override should be enabled")

    // Test scenario with default cache settings
    val scenarioWithDefaultCache = projectWithCacheOptions.scenarioContents.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(),
      scenario = projectWithCacheOptions.scenarioContents[2],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    assertEquals(null, scenarioWithDefaultCache.cacheOptions, "Cache override should be null by default")
  }

  @Test
  fun testAiOptions() {
    // Test project-level aiOptions
    val projectSettings = projectWithAiOptions.settings
    val projectAiOptions = projectSettings.aiOptions
    assertNotNull(projectAiOptions, "Project aiOptions should not be null")
    assertEquals(0.8, projectAiOptions.temperature!!, "Project temperature should be 0.8")

    // Test scenario without custom aiOptions (should use project settings)
    val scenarioA = projectWithAiOptions.scenarioContents.createArbigentScenario(
      projectSettings = projectSettings,
      scenario = projectWithAiOptions.scenarioContents[0],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    val scenarioAAiOptions = scenarioA.agentTasks[0].agentConfig.aiOptions
    assertNotNull(scenarioAAiOptions, "Scenario A aiOptions should not be null")
    assertEquals(0.8, scenarioAAiOptions.temperature!!, "Scenario A temperature should be 0.8")

    // Test scenario with custom aiOptions (should override project settings)
    val scenarioB = projectWithAiOptions.scenarioContents.createArbigentScenario(
      projectSettings = projectSettings,
      scenario = projectWithAiOptions.scenarioContents[1],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    val scenarioBAiOptions = scenarioB.agentTasks[0].agentConfig.aiOptions
    assertNotNull(scenarioBAiOptions, "Scenario B aiOptions should not be null")
    assertEquals(0.5, scenarioBAiOptions.temperature!!, "Scenario B temperature should be 0.5")
  }

  private val projectWithDeviceFormFactor = ArbigentProjectSerializer().load(
    """
    settings:
      defaultDeviceFormFactor:
        type: "Tv"
    scenarios:
    - id: "default-form-factor"
      goal: "Test default form factor"
      deviceFormFactor:
        type: "Unspecified"
    - id: "custom-form-factor"
      goal: "Test custom form factor"
      deviceFormFactor:
        type: "Mobile"
    - id: "default-not-using-project"
      goal: "Test not using project default"
    """
  )

  @Test
  fun testDefaultDeviceFormFactor() {
    // Test project-level defaultDeviceFormFactor
    val projectSettings = projectWithDeviceFormFactor.settings
    assertEquals(
      io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor.Tv, 
      projectSettings.defaultDeviceFormFactor, 
      "Project defaultDeviceFormFactor should be Tv"
    )

    // Test scenario using project default
    val scenarioUsingDefault = projectWithDeviceFormFactor.scenarioContents.createArbigentScenario(
      projectSettings = projectSettings,
      scenario = projectWithDeviceFormFactor.scenarioContents[0],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    assertEquals(
      io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor.Tv,
      scenarioUsingDefault.deviceFormFactor,
      "Scenario using project default should have Tv form factor"
    )

    // Test scenario with custom form factor (should override project default)
    val scenarioWithCustom = projectWithDeviceFormFactor.scenarioContents.createArbigentScenario(
      projectSettings = projectSettings,
      scenario = projectWithDeviceFormFactor.scenarioContents[1],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    assertEquals(
      io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor.Mobile,
      scenarioWithCustom.deviceFormFactor,
      "Scenario with custom form factor should have Mobile form factor"
    )

    // Test scenario not using project default (should use Mobile as default)
    val scenarioNotUsingDefault = projectWithDeviceFormFactor.scenarioContents.createArbigentScenario(
      projectSettings = projectSettings,
      scenario = projectWithDeviceFormFactor.scenarioContents[2],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    assertEquals(
      io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor.Mobile,
      scenarioNotUsingDefault.deviceFormFactor,
      "Scenario not using project default should have Mobile form factor"
    )
  }
}
