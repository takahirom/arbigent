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

  /**
   * replayWithFallback is a scenario-run level knob like maxRetry, not a per-task execution
   * option, so a call-form scenario must be able to carry it. Most real scenarios are call-form,
   * and the executor reads this flag from the scenario it was asked to run.
   */
  @Test
  fun replayWithFallbackIsAllowedOnCallFormScenario() {
    val project = load(
      """
      scenarios:
      - id: "caller"
        uses: "part"
        replayWithFallback: true
        imageAssertions:
        - assertionPrompt: "The home screen is shown"
      reusableScenarios:
      - id: "part"
        goal: "Do something"
      """
    )
    assertEquals(true, project.scenarioContents.single { it.id == "caller" }.replayWithFallback)
  }

  /**
   * Replay is verified by the image assertions and by nothing else, so enabling it on a scenario
   * that has none would produce a run that cannot fail.
   */
  @Test
  fun replayWithFallbackWithoutImageAssertionsFailsAtLoad() {
    assertValidationError(
      "replayWithFallback requires imageAssertions",
      """
      scenarios:
      - id: "unverified"
        goal: "Do something"
        replayWithFallback: true
      """
    )
  }

  /**
   * The counterpart of the test above: this is why replayWithFallback cannot live inside
   * cacheOptions. Per-task execution options are rejected on a call-form scenario, which would
   * leave the flag with nowhere to go for most real scenarios.
   */
  @Test
  fun cacheOptionsOnCallFormFailAtLoad() {
    assertValidationError(
      "execution options (aiOptions/mcpOptions/cacheOptions/additionalActions) are not allowed on a call-form scenario",
      """
      scenarios:
      - id: "caller"
        uses: "part"
        cacheOptions:
          forceCacheDisabled: true
      reusableScenarios:
      - id: "part"
        goal: "Do something"
      """
    )
  }

  // ----- Call-site assertions -----

  /**
   * A call-form scenario may declare its own imageAssertions: the call shares the *how*, the
   * assertions are this call site's own *what*. They run once the whole call has finished, so
   * the resolver carries them on the last leaf of the expansion.
   */
  @Test
  fun callSiteAssertionsAreCarriedByTheLastLeafOfTheExpansion() {
    val project = load(
      """
      scenarios:
      - id: "open-search"
        dependency: "launch"
        uses: "open-screen"
        with:
          screen: "Search"
        imageAssertions:
        - assertionPrompt: "The search input field is shown"
      - id: "launch"
        goal: "Launch the app"
      reusableScenarios:
      - id: "open-screen"
        inputs:
          screen:
            required: true
        steps:
        - uses: "focus-nav"
        - uses: "select-item"
          with:
            screen: "{{inputs.screen}}"
      - id: "focus-nav"
        goal: "Focus the left navigation"
      - id: "select-item"
        inputs:
          screen:
            required: true
        goal: "Select {{inputs.screen}}"
      """
    )
    val resolution = ArbigentScenarioResolver.resolveChain(
      target = project.scenarioContents.first { it.id == "open-search" },
      scenarioLookup = { id -> project.scenarioContents.firstOrNull { it.id == id } },
      reusableLookup = { id -> project.reusableScenarios.firstOrNull { it.id == id } },
    )
    assertEquals(emptyList(), resolution.diagnostics)
    // launch, focus-nav, select-item — only the expansion's last leaf carries the assertions.
    assertEquals(
      listOf(emptyList(), emptyList(), listOf("The search input field is shown")),
      resolution.leaves.map { leaf -> leaf.callSiteAssertions.map { it.assertionPrompt } }
    )
  }

  @Test
  fun leafAssertionsResolveInputsAndRunBeforeCallSiteAssertions() = runTest {
    leafAssertionsResolveInputsAndRunBeforeCallSiteAssertionsBody(coroutineContext[CoroutineDispatcher]!!)
  }

  private suspend fun leafAssertionsResolveInputsAndRunBeforeCallSiteAssertionsBody(
    dispatcher: CoroutineDispatcher
  ) {
    val assertedPrompts = mutableListOf<String>()
    val fakeAi = FakeAi()
    val recordingAi = object : ArbigentAi by fakeAi {
      override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
        assertedPrompts += imageAssertionInput.assertions.assertions.map { it.assertionPrompt }
        return fakeAi.assertImage(imageAssertionInput)
      }
    }
    val project = load(
      """
      scenarios:
      - id: "caller"
        uses: "part"
        with:
          user: "paid"
        imageAssertions:
        - assertionPrompt: "Call-site verification"
      reusableScenarios:
      - id: "part"
        goal: "Log in as {{inputs.user}}"
        inputs:
          user:
            required: true
        imageAssertions:
        - assertionPrompt: "Logged in as {{inputs.user}}"
      """
    )
    val scenario = project.scenarioContents.createArbigentScenario(
      projectSettings = project.settings,
      scenario = project.scenarioContents.first { it.id == "caller" },
      aiFactory = { recordingAi },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = ArbigentAiDecisionCache.Disabled,
      fixedScenarios = project.fixedScenarios,
      reusableScenarios = project.reusableScenarios
    )
    val executor = ArbigentScenarioExecutor(dispatcher)
    executor.execute(scenario, MCPClient())
    assertTrue(executor.isGoalAchieved())
    // The leaf's prompt is {{inputs.*}}-resolved (matching what `instruction` renders) and runs
    // before the call site's own verification.
    assertEquals(listOf("Logged in as paid", "Call-site verification"), assertedPrompts)
  }

  // ----- Maestro yamlText substitution -----

  @Test
  fun maestroYamlInputsAreSubstitutedAndExecuted() = runTest {
    maestroYamlInputsAreSubstitutedAndExecutedBody(coroutineContext[CoroutineDispatcher]!!)
  }

  private suspend fun maestroYamlInputsAreSubstitutedAndExecutedBody(dispatcher: CoroutineDispatcher) {
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
    val executor = ArbigentScenarioExecutor(dispatcher)
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

  @Test
  fun emptyGoalScenariosFromLegacyFilesStillLoad() {
    // Work-in-progress scenarios could always be saved with an empty goal.
    val project = load(
      """
      scenarios:
      - id: "wip"
        goal: ""
      """
    )
    assertEquals(1, project.scenarioContents.size)
  }

  @Test
  fun defaultedInputsAppearInBreadcrumb() {
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
    assertEquals(
      "call-login › login (method=email)",
      project.tasksOf("call-login")[0].callBreadcrumb
    )
  }

  @Test
  fun callFormMustNotHaveMaxStepOrPromptTemplate() {
    assertValidationError(
      "maxStep is not allowed",
      """
      scenarios:
      - id: "caller"
        uses: "part"
        maxStep: 25
      reusableScenarios:
      - id: "part"
        goal: "part goal"
      """
    )
    assertValidationError(
      "userPromptTemplate is not allowed",
      """
      scenarios:
      - id: "caller"
        uses: "part"
        userPromptTemplate: "custom {{USER_INPUT_GOAL}}"
      reusableScenarios:
      - id: "part"
        goal: "part goal"
      """
    )
    assertValidationError(
      "initializeMethods are not allowed",
      """
      scenarios:
      - id: "caller"
        uses: "part"
        initializeMethods:
          type: "Back"
      reusableScenarios:
      - id: "part"
        goal: "part goal"
      """
    )
  }

  /** Assertion prompts follow the same `{{inputs.*}}` rules as goals. */
  @Test
  fun inputsPlaceholderInCallSiteAssertionFailsAtLoad() {
    assertValidationError(
      "scenarios 'caller': '{{inputs.*}}' can only be used inside reusable scenario definitions",
      """
      scenarios:
      - id: "caller"
        uses: "part"
        with:
          user: "paid"
        imageAssertions:
        - assertionPrompt: "Logged in as {{inputs.user}}"
      reusableScenarios:
      - id: "part"
        inputs:
          user:
            required: true
        goal: "Log in as {{inputs.user}}"
      """
    )
  }

  @Test
  fun undeclaredInputsPlaceholderInReusableAssertionFailsAtLoad() {
    assertValidationError(
      "reusableScenarios 'part': '{{inputs.typo}}' is not declared in inputs",
      """
      scenarios:
      - id: "caller"
        uses: "part"
        with:
          user: "paid"
      reusableScenarios:
      - id: "part"
        inputs:
          user:
            required: true
        goal: "Log in as {{inputs.user}}"
        imageAssertions:
        - assertionPrompt: "Logged in as {{inputs.typo}}"
      """
    )
  }

  /**
   * Reusable composites stay pure delegation: verification belongs either to the call site or
   * to the leaf, not to the wiring in between.
   */
  @Test
  fun imageAssertionsOnReusableCallFormFailAtLoad() {
    assertValidationError(
      "reusableScenarios 'composite': imageAssertions are not allowed on a reusable call-form scenario",
      """
      scenarios:
      - id: "caller"
        uses: "composite"
      reusableScenarios:
      - id: "composite"
        steps:
        - uses: "part"
        imageAssertions:
        - assertionPrompt: "Not allowed here"
      - id: "part"
        goal: "part goal"
      """
    )
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
      "reusableScenarios 'part': duplicate id (declared 2 times)",
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
