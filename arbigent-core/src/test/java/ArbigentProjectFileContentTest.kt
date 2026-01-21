package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
      deviceFormFactor:
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
      projectSettings.deviceFormFactor,
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

  private val projectWithAdditionalActions = ArbigentProjectSerializer().load(
    """
    settings:
      additionalActions:
        - ClickWithText
    scenarios:
    - id: "project-actions"
      goal: "Test project-level actions"
    - id: "scenario-actions"
      goal: "Test scenario-level actions"
      additionalActions:
        - ClickWithId
    - id: "scenario-only-actions"
      goal: "Test scenario-only actions"
    """
  )

  @Test
  fun testAdditionalActions() {
    // Test project-level additionalActions
    val projectSettings = projectWithAdditionalActions.settings
    val projectAdditionalActions = projectSettings.additionalActions
    assertNotNull(projectAdditionalActions, "Project additionalActions should not be null")
    assertEquals(1, projectAdditionalActions.size, "Project should have 1 additional action")
    assertEquals("ClickWithText", projectAdditionalActions[0], "Project action should be ClickWithText")

    // Test scenario using project actions
    val scenarioWithProjectActions = projectWithAdditionalActions.scenarioContents.createArbigentScenario(
      projectSettings = projectSettings,
      scenario = projectWithAdditionalActions.scenarioContents[0],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    val projectActionsTask = scenarioWithProjectActions.agentTasks[0]
    assertEquals(1, projectActionsTask.additionalActions.size, "Scenario should have 1 additional action from project")
    assertEquals("ClickWithText", projectActionsTask.additionalActions[0])

    // Test scenario with both project and scenario actions (merged)
    val scenarioWithBothActions = projectWithAdditionalActions.scenarioContents.createArbigentScenario(
      projectSettings = projectSettings,
      scenario = projectWithAdditionalActions.scenarioContents[1],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    val bothActionsTask = scenarioWithBothActions.agentTasks[0]
    assertEquals(2, bothActionsTask.additionalActions.size, "Scenario should have 2 additional actions (merged)")
    assertTrue(bothActionsTask.additionalActions.contains("ClickWithText"), "Should contain project action")
    assertTrue(bothActionsTask.additionalActions.contains("ClickWithId"), "Should contain scenario action")

    // Test scenario with no additional actions (empty project settings)
    val scenarioWithNoActions = projectWithAdditionalActions.scenarioContents.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(additionalActions = null),
      scenario = projectWithAdditionalActions.scenarioContents[2],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    val noActionsTask = scenarioWithNoActions.agentTasks[0]
    assertEquals(0, noActionsTask.additionalActions.size, "Scenario should have 0 additional actions")
  }

  private val projectWithDuplicateActions = ArbigentProjectSerializer().load(
    """
    settings:
      additionalActions:
        - ClickWithText
    scenarios:
    - id: "duplicate-test"
      goal: "Test deduplication"
      additionalActions:
        - ClickWithText
        - ClickWithId
    """
  )

  @Test
  fun testAdditionalActionsDeduplication() {
    // Test that duplicate actions are removed
    val projectSettings = projectWithDuplicateActions.settings
    val scenarioWithDuplicates = projectWithDuplicateActions.scenarioContents.createArbigentScenario(
      projectSettings = projectSettings,
      scenario = projectWithDuplicateActions.scenarioContents[0],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    val task = scenarioWithDuplicates.agentTasks[0]
    assertEquals(2, task.additionalActions.size, "Should have 2 actions after deduplication")
    assertEquals(listOf("ClickWithText", "ClickWithId"), task.additionalActions, "Should deduplicate ClickWithText")
  }

  private val projectWithScenarioOnlyActions = ArbigentProjectSerializer().load(
    """
    scenarios:
    - id: "scenario-only"
      goal: "Test scenario-only actions"
      additionalActions:
        - ClickWithText
    """
  )

  @Test
  fun testScenarioOnlyAdditionalActions() {
    // Test scenario-level additionalActions without project-level settings
    val scenarioOnly = projectWithScenarioOnlyActions.scenarioContents.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(),
      scenario = projectWithScenarioOnlyActions.scenarioContents[0],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    val task = scenarioOnly.agentTasks[0]
    assertEquals(1, task.additionalActions.size, "Scenario should have 1 additional action")
    assertEquals("ClickWithText", task.additionalActions[0])
  }

  private val projectWithExtraRequestParams = ArbigentProjectSerializer().load(
    """
    settings:
      aiOptions:
        extraBody:
          reasoning:
            effort: "high"
    scenarios:
    - id: "use-project-params"
      goal: "Test using project params"
    - id: "override-params"
      goal: "Test overriding params"
      aiOptions:
        extraBody:
          reasoning:
            effort: "low"
    - id: "merge-params"
      goal: "Test merging params"
      aiOptions:
        extraBody:
          max_tokens: 1000
    """
  )

  @Test
  fun testExtraRequestParams() {
    val projectSettings = projectWithExtraRequestParams.settings
    val projectAiOptions = projectSettings.aiOptions
    assertNotNull(projectAiOptions, "Project aiOptions should not be null")
    assertNotNull(projectAiOptions.extraBody, "Project extraBody should not be null")

    // Test scenario using project params
    val scenarioUsingProject = projectWithExtraRequestParams.scenarioContents.createArbigentScenario(
      projectSettings = projectSettings,
      scenario = projectWithExtraRequestParams.scenarioContents[0],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    val projectParamsTask = scenarioUsingProject.agentTasks[0].agentConfig.aiOptions
    assertNotNull(projectParamsTask?.extraBody, "Scenario should have extraBody from project")

    // Test scenario overriding params
    val scenarioOverriding = projectWithExtraRequestParams.scenarioContents.createArbigentScenario(
      projectSettings = projectSettings,
      scenario = projectWithExtraRequestParams.scenarioContents[1],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    val overridingTask = scenarioOverriding.agentTasks[0].agentConfig.aiOptions
    assertNotNull(overridingTask?.extraBody, "Scenario should have extraBody")
    val reasoningObj = overridingTask?.extraBody?.get("reasoning") as? JsonObject
    assertNotNull(reasoningObj, "Should have reasoning object")
    assertEquals(JsonPrimitive("low"), reasoningObj["effort"], "Should override to 'low'")

    // Test scenario merging params
    val scenarioMerging = projectWithExtraRequestParams.scenarioContents.createArbigentScenario(
      projectSettings = projectSettings,
      scenario = projectWithExtraRequestParams.scenarioContents[2],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    val mergingTask = scenarioMerging.agentTasks[0].agentConfig.aiOptions
    assertNotNull(mergingTask?.extraBody, "Scenario should have merged extraBody")
    // Should have both reasoning and max_tokens
    assertNotNull(mergingTask?.extraBody?.get("reasoning"), "Should have reasoning from project")
    assertNotNull(mergingTask?.extraBody?.get("max_tokens"), "Should have max_tokens from scenario")
  }

  @Test
  fun testAiOptionsMerge() {
    val base = ArbigentAiOptions(
      temperature = 0.5,
      extraBody = buildJsonObject {
        put("reasoning", buildJsonObject {
          put("effort", JsonPrimitive("high"))
        })
      }
    )
    val overlay = ArbigentAiOptions(
      extraBody = buildJsonObject {
        put("max_tokens", JsonPrimitive(1000))
      }
    )
    val merged = base.mergeWith(overlay)

    assertEquals(0.5, merged.temperature, "Temperature should be preserved from base")
    assertNotNull(merged.extraBody, "Merged should have extraBody")
    assertNotNull(merged.extraBody?.get("reasoning"), "Should have reasoning from base")
    assertNotNull(merged.extraBody?.get("max_tokens"), "Should have max_tokens from overlay")
  }

  private val projectWithMcpOptions = ArbigentProjectSerializer().load(
    """
    scenarios:
    - id: "mcp-all"
      goal: "Test MCP all servers enabled"
    - id: "mcp-specific"
      goal: "Test MCP specific servers"
      mcpOptions:
        enabledMcpServers:
          - name: "filesystem"
          - name: "github"
    - id: "mcp-disabled"
      goal: "Test MCP disabled"
      mcpOptions:
        enabledMcpServers: []
    """
  )

  @Test
  fun testMcpOptions() {
    // Test scenario with all MCP servers enabled (default)
    val scenarioWithAllMcp = projectWithMcpOptions.scenarioContents.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(),
      scenario = projectWithMcpOptions.scenarioContents[0],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    assertNull(scenarioWithAllMcp.mcpOptions, "MCP options should be null (all servers enabled)")

    // Test scenario with specific MCP servers enabled
    val scenarioWithSpecificMcp = projectWithMcpOptions.scenarioContents.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(),
      scenario = projectWithMcpOptions.scenarioContents[1],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    assertNotNull(scenarioWithSpecificMcp.mcpOptions, "MCP options should not be null")
    assertEquals(2, scenarioWithSpecificMcp.mcpOptions?.enabledMcpServers?.size, "Should have 2 enabled servers")
    assertTrue(scenarioWithSpecificMcp.mcpOptions?.isServerEnabled("filesystem") == true, "filesystem should be enabled")
    assertTrue(scenarioWithSpecificMcp.mcpOptions?.isServerEnabled("github") == true, "github should be enabled")
    assertTrue(scenarioWithSpecificMcp.mcpOptions?.isServerEnabled("database") == false, "database should be disabled")

    // Test scenario with MCP disabled
    val scenarioWithMcpDisabled = projectWithMcpOptions.scenarioContents.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(),
      scenario = projectWithMcpOptions.scenarioContents[2],
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.InMemory().toCache()
    )
    assertNotNull(scenarioWithMcpDisabled.mcpOptions, "MCP options should not be null")
    assertEquals(0, scenarioWithMcpDisabled.mcpOptions?.enabledMcpServers?.size, "Should have 0 enabled servers")
    assertTrue(scenarioWithMcpDisabled.mcpOptions?.isServerEnabled("filesystem") == false, "filesystem should be disabled")
  }

  @Test
  fun testMcpOptionsIsServerEnabled() {
    // null = all enabled
    val optionsNull = ArbigentMcpOptions(enabledMcpServers = null)
    assertTrue(optionsNull.isServerEnabled("any-server"), "null should enable all servers")

    // empty = all disabled
    val optionsEmpty = ArbigentMcpOptions(enabledMcpServers = emptyList())
    assertFalse(optionsEmpty.isServerEnabled("any-server"), "empty list should disable all servers")

    // specific list
    val optionsSpecific = ArbigentMcpOptions(enabledMcpServers = listOf(EnabledMcpServer("server1"), EnabledMcpServer("server2")))
    assertTrue(optionsSpecific.isServerEnabled("server1"), "server1 should be enabled")
    assertTrue(optionsSpecific.isServerEnabled("server2"), "server2 should be enabled")
    assertFalse(optionsSpecific.isServerEnabled("server3"), "server3 should be disabled")
  }

}
