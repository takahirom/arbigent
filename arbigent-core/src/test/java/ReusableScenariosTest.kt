package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import maestro.orchestra.MaestroCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReusableScenariosTest {
  private fun load(yaml: String): ArbigentProjectFileContent = ArbigentProjectSerializer().load(yaml)

  private fun ArbigentProjectFileContent.tasksOf(scenarioId: String) =
    scenarioContents.createArbigentScenario(
      projectSettings = settings,
      scenario = scenarioContents.first { it.id == scenarioId },
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = ArbigentAiDecisionCache.Disabled,
      fixedScenarios = fixedScenarios,
      reusableScenarios = reusableScenarios
    ).agentTasks

  // ----- Expansion -----

  @Test
  fun singleUsesExpandsToOneTaskWithSubstitutedGoal() {
    val project = load(
      """
      scenarios:
      - id: "call-login"
        uses: "login"
        with:
          user: "paid"
      reusableScenarios:
      - id: "login"
        inputs:
          user:
            required: true
        goal: "Log in as {{inputs.user}}"
      """
    )
    val tasks = project.tasksOf("call-login")
    assertEquals(1, tasks.size)
    assertEquals("Log in as paid", tasks[0].goal)
    assertEquals("call-login", tasks[0].scenarioId)
    assertEquals("call-login › login (user=paid)", tasks[0].callBreadcrumb)
  }

  @Test
  fun stepsExpandInOrderAndCompositeNests() {
    val project = load(
      """
      scenarios:
      - id: "combo"
        steps:
        - uses: "change-language"
          with:
            lang: "English"
        - uses: "verify-home"
      reusableScenarios:
      - id: "change-language"
        inputs:
          lang:
            required: true
        steps:
        - uses: "open-settings"
        - uses: "set-language"
          with:
            lang: "{{inputs.lang}}"
      - id: "open-settings"
        goal: "Open the settings screen"
      - id: "set-language"
        inputs:
          lang:
            required: true
        goal: "Set language to {{inputs.lang}}"
      - id: "verify-home"
        goal: "Verify the home screen is shown"
      """
    )
    val tasks = project.tasksOf("combo")
    assertEquals(3, tasks.size)
    assertEquals("Open the settings screen", tasks[0].goal)
    assertEquals("Set language to English", tasks[1].goal)
    assertEquals("Verify the home screen is shown", tasks[2].goal)
    assertEquals("combo › change-language (lang=English) › open-settings", tasks[0].callBreadcrumb)
    assertEquals("combo › change-language (lang=English) › set-language (lang=English)", tasks[1].callBreadcrumb)
    assertEquals("combo › verify-home", tasks[2].callBreadcrumb)
  }

  @Test
  fun ordinaryAndReusableNodesMixAlongDependencyChain() {
    val project = load(
      """
      scenarios:
      - id: "launch-app"
        goal: "Launch the app"
      - id: "become-paid"
        dependency: "launch-app"
        uses: "upgrade"
        with:
          plan: "premium"
      - id: "content-a"
        dependency: "become-paid"
        goal: "Open content A"
      reusableScenarios:
      - id: "upgrade"
        inputs:
          plan:
            required: true
        goal: "Purchase the {{inputs.plan}} plan"
      """
    )
    val tasks = project.tasksOf("content-a")
    assertEquals(listOf("Launch the app", "Purchase the premium plan", "Open content A"), tasks.map { it.goal })
    assertNull(tasks[0].callBreadcrumb)
    assertEquals("become-paid › upgrade (plan=premium)", tasks[1].callBreadcrumb)
    assertNull(tasks[2].callBreadcrumb)
  }

  @Test
  fun defaultInputIsUsedWhenNotProvided() {
    val project = load(
      """
      scenarios:
      - id: "call-login"
        uses: "login"
      reusableScenarios:
      - id: "login"
        inputs:
          method:
            default: "email"
        goal: "Log in via {{inputs.method}}"
      """
    )
    assertEquals("Log in via email", project.tasksOf("call-login")[0].goal)
  }

  @Test
  fun bareVariablesAreLeftForRuntimeResolution() {
    val project = load(
      """
      scenarios:
      - id: "call-login"
        uses: "login"
        with:
          user: "paid"
      reusableScenarios:
      - id: "login"
        inputs:
          user:
            required: true
        goal: "Log in as {{inputs.user}} on {{app_name}}"
      """
    )
    assertEquals("Log in as paid on {{app_name}}", project.tasksOf("call-login")[0].goal)
  }

  @Test
  fun reusableLeafKeepsItsOwnExecutionOptions() {
    val project = load(
      """
      scenarios:
      - id: "caller"
        uses: "part"
      reusableScenarios:
      - id: "part"
        goal: "Do something"
        maxStep: 25
      """
    )
    assertEquals(25, project.tasksOf("caller")[0].maxStep)
  }

  // ----- Maestro yamlText substitution -----

  @Test
  fun maestroYamlInputsAreSubstitutedAndExecuted() = runTest {
    val previousDispatcher = ArbigentCoroutinesDispatcher.dispatcher
    ArbigentCoroutinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    try {
      maestroYamlInputsAreSubstitutedAndExecutedBody()
    } finally {
      ArbigentCoroutinesDispatcher.dispatcher = previousDispatcher
    }
  }

  private suspend fun maestroYamlInputsAreSubstitutedAndExecutedBody() {
    val executedCommands = mutableListOf<MaestroCommand>()
    val fakeDevice = FakeDevice()
    val recordingDevice = object : ArbigentDevice by fakeDevice {
      override fun executeActions(actions: List<MaestroCommand>) {
        executedCommands.addAll(actions)
        fakeDevice.executeActions(actions)
      }
    }
    val project = load(
      """
      scenarios:
      - id: "caller"
        uses: "deeplink-login"
        with:
          user: "premium-user"
      reusableScenarios:
      - id: "deeplink-login"
        type:
          type: "Execution"
        inputs:
          user:
            required: true
        initializationMethods:
        - type: "MaestroYaml"
          scenarioId: "login-flow"
        goal: "Log in via deeplink"
      fixedScenarios:
      - id: "login-flow"
        title: "login"
        description: "login flow"
        yamlText: |-
          appId: "com.example.app"
          ---
          - openLink: "example://login?user={{inputs.user}}"
      """
    )
    val scenario = project.scenarioContents.createArbigentScenario(
      projectSettings = project.settings,
      scenario = project.scenarioContents.first { it.id == "caller" },
      aiFactory = { FakeAi() },
      deviceFactory = { recordingDevice },
      aiDecisionCache = ArbigentAiDecisionCache.Disabled,
      fixedScenarios = project.fixedScenarios,
      reusableScenarios = project.reusableScenarios
    )
    val executor = ArbigentScenarioExecutor()
    executor.execute(scenario, MCPClient())
    assertTrue(executor.isGoalAchieved())
    val openLink = executedCommands.mapNotNull { it.openLinkCommand }.firstOrNull()
    assertEquals("example://login?user=premium-user", openLink?.link)
  }

  // ----- Round-trip -----

  @Test
  fun roundTripKeepsReusableScenarios() {
    val yaml =
      """
      scenarios:
      - id: "caller"
        steps:
        - uses: "part"
          with:
            key: "value"
      reusableScenarios:
      - id: "part"
        inputs:
          key:
            required: true
        goal: "Use {{inputs.key}}"
      """
    val serializer = ArbigentProjectSerializer()
    val loaded = serializer.load(yaml)
    val saved = serializer.encodeToString(loaded)
    val reloaded = serializer.load(saved)
    assertEquals(1, reloaded.reusableScenarios.size)
    assertEquals("part", reloaded.reusableScenarios[0].id)
    assertEquals(true, reloaded.reusableScenarios[0].inputs["key"]?.required)
    assertEquals("part", reloaded.scenarioContents[0].callSteps().single().uses)
    assertEquals(mapOf("key" to "value"), reloaded.scenarioContents[0].callSteps().single().withValues)
  }

  @Test
  fun oldProjectFilesStillLoad() {
    val project = load(
      """
      scenarios:
      - id: "A-ID"
        goal: "A-GOAL"
      """
    )
    assertEquals(1, project.scenarioContents.size)
    assertEquals(0, project.reusableScenarios.size)
  }

  // ----- Load-time validation -----

  private fun assertValidationError(expectedMessagePart: String, yaml: String) {
    val exception = assertFailsWith<ArbigentProjectValidationException> { load(yaml) }
    assertTrue(
      exception.message!!.contains(expectedMessagePart),
      "Expected message to contain '$expectedMessagePart' but was: ${exception.message}"
    )
  }

  @Test
  fun unknownUsesReferenceFailsAtLoad() {
    assertValidationError(
      "'missing' is not defined",
      """
      scenarios:
      - id: "caller"
        uses: "missing"
      """
    )
  }

  @Test
  fun goalAndUsesAreExclusive() {
    assertValidationError(
      "must not have a goal",
      """
      scenarios:
      - id: "caller"
        goal: "some goal"
        uses: "part"
      reusableScenarios:
      - id: "part"
        goal: "part goal"
      """
    )
  }

  @Test
  fun usesAndStepsAreExclusive() {
    assertValidationError(
      "not both",
      """
      scenarios:
      - id: "caller"
        uses: "part"
        steps:
        - uses: "part"
      reusableScenarios:
      - id: "part"
        goal: "part goal"
      """
    )
  }

  @Test
  fun callFormMustNotHaveInitializationMethods() {
    assertValidationError(
      "initializationMethods are not allowed",
      """
      scenarios:
      - id: "caller"
        uses: "part"
        initializationMethods:
        - type: "Back"
      reusableScenarios:
      - id: "part"
        goal: "part goal"
      """
    )
  }

  @Test
  fun cyclicCompositeReferencesFailAtLoad() {
    assertValidationError(
      "cyclic",
      """
      scenarios:
      - id: "caller"
        uses: "a"
      reusableScenarios:
      - id: "a"
        steps:
        - uses: "b"
      - id: "b"
        steps:
        - uses: "a"
      """
    )
  }

  @Test
  fun undeclaredWithKeyFailsAtLoad() {
    assertValidationError(
      "with key 'usr' is not declared",
      """
      scenarios:
      - id: "caller"
        uses: "login"
        with:
          usr: "paid"
      reusableScenarios:
      - id: "login"
        inputs:
          user:
            required: true
        goal: "Log in as {{inputs.user}}"
      """
    )
  }

  @Test
  fun missingRequiredInputFailsAtLoad() {
    assertValidationError(
      "required input 'user'",
      """
      scenarios:
      - id: "caller"
        uses: "login"
      reusableScenarios:
      - id: "login"
        inputs:
          user:
            required: true
        goal: "Log in as {{inputs.user}}"
      """
    )
  }

  @Test
  fun undeclaredInputsPlaceholderInReusableGoalFailsAtLoad() {
    assertValidationError(
      "'{{inputs.user}}' is not declared",
      """
      scenarios:
      - id: "caller"
        uses: "login"
      reusableScenarios:
      - id: "login"
        goal: "Log in as {{inputs.user}}"
      """
    )
  }

  @Test
  fun inputsPlaceholderOutsideReusableFailsAtLoad() {
    assertValidationError(
      "can only be used inside reusable",
      """
      scenarios:
      - id: "caller"
        goal: "Log in as {{inputs.user}}"
      """
    )
  }

  @Test
  fun dependencyOnReusableDefinitionFailsAtLoad() {
    assertValidationError(
      "'dependency' is not allowed on a reusable scenario",
      """
      scenarios:
      - id: "caller"
        uses: "part"
      reusableScenarios:
      - id: "part"
        dependency: "caller"
        goal: "part goal"
      """
    )
  }

  @Test
  fun tagsOnReusableDefinitionFailsAtLoad() {
    assertValidationError(
      "'tags' are not allowed on a reusable scenario",
      """
      scenarios:
      - id: "caller"
        uses: "part"
      reusableScenarios:
      - id: "part"
        goal: "part goal"
        tags:
        - name: "tag"
      """
    )
  }

  @Test
  fun inputsOnOrdinaryScenarioFailsAtLoad() {
    assertValidationError(
      "'inputs' can only be declared on reusableScenarios",
      """
      scenarios:
      - id: "caller"
        goal: "some goal"
        inputs:
          user:
            required: true
      """
    )
  }

  @Test
  fun reservedCharactersInUsesFailAtLoad() {
    assertValidationError(
      "reserved for future cross-file references",
      """
      scenarios:
      - id: "caller"
        uses: "./common.yaml#login"
      """
    )
  }

  @Test
  fun duplicateReusableIdsFailAtLoad() {
    assertValidationError(
      "duplicate id 'part'",
      """
      scenarios:
      - id: "caller"
        uses: "part"
      reusableScenarios:
      - id: "part"
        goal: "part goal"
      - id: "part"
        goal: "another goal"
      """
    )
  }

  @Test
  fun maestroYamlInputsMustBeDeclared() {
    assertValidationError(
      "'{{inputs.user}}' is not declared",
      """
      scenarios:
      - id: "caller"
        uses: "deeplink-login"
      reusableScenarios:
      - id: "deeplink-login"
        initializationMethods:
        - type: "MaestroYaml"
          scenarioId: "login-flow"
        goal: "Log in via deeplink"
      fixedScenarios:
      - id: "login-flow"
        title: "login"
        description: "login flow"
        yamlText: |-
          appId: "com.example.app"
          ---
          - openLink: "example://login?user={{inputs.user}}"
      """
    )
  }
}
