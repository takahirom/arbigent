package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import maestro.orchestra.MaestroCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `settings.variables` in the project YAML and `{{name}}` in initialization methods
 * (LaunchApp / CleanupData `packageName`, OpenLink `link`).
 */
class ProjectVariablesTest {
  private val serializer = ArbigentProjectSerializer()
  private fun load(yaml: String): ArbigentProjectFileContent = serializer.load(yaml)

  private class TestAppSettings(override val variables: Map<String, String>?) : ArbigentAppSettings {
    override val workingDirectory: String? = null
    override val path: String? = null
    override val mcpEnvironmentVariables: Map<String, String>? = null
  }

  private class RecordingDevice : ArbigentDevice by FakeDevice() {
    val executedCommands = mutableListOf<MaestroCommand>()
    val settledAppIds = mutableListOf<String?>()
    override fun executeActions(actions: List<MaestroCommand>) {
      executedCommands += actions
    }
    override fun waitForAppToSettle(appId: String?) {
      settledAppIds += appId
    }
  }

  private fun ArbigentProjectFileContent.scenarioOf(
    scenarioId: String,
    device: ArbigentDevice,
    appSettings: ArbigentAppSettings = DefaultArbigentAppSettings,
  ) = scenarioContents.createArbigentScenario(
    projectSettings = settings,
    scenario = scenarioContents.first { it.id == scenarioId },
    aiFactory = { FakeAi() },
    deviceFactory = { device },
    aiDecisionCache = ArbigentAiDecisionCache.Disabled,
    appSettings = appSettings,
    fixedScenarios = fixedScenarios,
    reusableScenarios = reusableScenarios
  )

  /** Runs only the initializer interceptors of the first task, without AI or the executor. */
  private fun ArbigentScenario.runInitializers(device: ArbigentDevice) {
    val interceptors = agentTasks.first().agentConfig.interceptors.filterIsInstance<ArbigentInitializerInterceptor>()
    fun proceed(index: Int) {
      if (index < interceptors.size) interceptors[index].intercept(device) { proceed(index + 1) }
    }
    proceed(0)
  }

  private val launchProject = """
    settings:
      variables:
        appId: "com.example.app"
        user: "alice"
    scenarios:
    - id: "launch-app"
      goal: "Log in as {{user}}"
      initializationMethods:
      - type: "CleanupData"
        packageName: "{{appId}}"
      - type: "LaunchApp"
        packageName: "{{appId}}"
      - type: "OpenLink"
        link: "example://open?from={{appId}}"
  """.trimIndent()

  // ----- settings.variables -----

  @Test
  fun settingsVariablesLoadAndRoundTrip() {
    val project = load(launchProject)
    assertEquals(mapOf("appId" to "com.example.app", "user" to "alice"), project.settings.variables!!)

    val encoded = serializer.encodeToString(project)
    assertTrue(encoded.contains("variables:"), encoded)
    assertEquals(project.settings.variables, load(encoded).settings.variables)
  }

  @Test
  fun settingsWithoutVariablesEncodeWithoutTheKey() {
    val project = load(
      """
      scenarios:
      - id: "launch-app"
        goal: "Open the app"
      """.trimIndent()
    )
    assertNull(project.settings.variables)
    assertFalse(serializer.encodeToString(project).contains("variables"))
  }

  @Test
  fun projectVariablesResolveInitializationMethodsAtRunTime() {
    val device = RecordingDevice()
    load(launchProject).scenarioOf("launch-app", device).runInitializers(device)

    assertEquals("com.example.app", device.executedCommands.mapNotNull { it.clearStateCommand }.single().appId)
    assertEquals("com.example.app", device.executedCommands.mapNotNull { it.launchAppCommand }.single().appId)
    assertEquals(listOf<String?>("com.example.app"), device.settledAppIds)
    assertEquals("example://open?from=com.example.app", device.executedCommands.mapNotNull { it.openLinkCommand }.single().link)
  }

  @Test
  fun appVariablesOverrideProjectVariablesPerKey() {
    val device = RecordingDevice()
    val scenario = load(launchProject).scenarioOf(
      "launch-app", device, appSettings = TestAppSettings(mapOf("appId" to "com.example.app.debug"))
    )
    scenario.runInitializers(device)

    assertEquals("com.example.app.debug", device.executedCommands.mapNotNull { it.launchAppCommand }.single().appId)
    assertEquals("com.example.app.debug", device.executedCommands.mapNotNull { it.clearStateCommand }.single().appId)
    // The merged map is what the agent resolves goals with: overridden key wins, the rest stay.
    val effective = scenario.agentTasks.first().agentConfig.appSettings?.variables
    assertEquals(mapOf("appId" to "com.example.app.debug", "user" to "alice"), effective)
    assertEquals("Log in as alice", scenario.agentTasks.first().agentConfig.resolveGoal("Log in as {{user}}"))
  }

  @Test
  fun appSettingsWithoutProjectVariablesAreUsedAsIs() {
    val appSettings = TestAppSettings(mapOf("user" to "bob"))
    val scenario = load(
      """
      scenarios:
      - id: "launch-app"
        goal: "Log in as {{user}}"
      """.trimIndent()
    ).scenarioOf("launch-app", FakeDevice(), appSettings = appSettings)
    assertTrue(scenario.agentTasks.first().agentConfig.appSettings === appSettings)
  }

  @Test
  fun unresolvedVariableInInitializationMethodFailsWithActionableMessage() {
    val device = RecordingDevice()
    val scenario = load(
      """
      scenarios:
      - id: "launch-app"
        goal: "Open the app"
        initializationMethods:
        - type: "LaunchApp"
          packageName: "{{appId}}"
      """.trimIndent()
    ).scenarioOf("launch-app", device)

    val error = assertFailsWith<ArbigentUnresolvedVariableException> { scenario.runInitializers(device) }
    val message = error.message!!
    assertTrue(message.contains("{{appId}}"), message)
    assertTrue(message.contains("LaunchApp packageName"), message)
    assertTrue(message.contains("settings.variables"), message)
    assertTrue(message.contains("--variables"), message)
    assertTrue(device.executedCommands.isEmpty(), "nothing must run with a placeholder app id")
  }

  @Test
  fun escapedPlaceholderInLinkIsKeptLiterally() {
    val device = RecordingDevice()
    load(
      """
      scenarios:
      - id: "open-link"
        goal: "Open the link"
        initializationMethods:
        - type: "OpenLink"
          link: "example://open?template=\\{{id}}"
      """.trimIndent()
    ).scenarioOf("open-link", device).runInitializers(device)
    assertEquals("example://open?template={{id}}", device.executedCommands.mapNotNull { it.openLinkCommand }.single().link)
  }

  // ----- {{inputs.*}} in initialization methods -----

  @Test
  fun inputsInInitializationMethodsAreBoundAtTheCallSite() {
    val device = RecordingDevice()
    load(
      """
      settings:
        variables:
          appId: "com.example.app"
      scenarios:
      - id: "open-search"
        uses: "open-deeplink"
        with:
          path: "search"
          pkg: "com.example.other"
      reusableScenarios:
      - id: "open-deeplink"
        inputs:
          path:
            required: true
          pkg:
            required: true
        initializationMethods:
        - type: "CleanupData"
          packageName: "{{inputs.pkg}}"
        - type: "LaunchApp"
          packageName: "{{inputs.pkg}}"
          launchArguments:
            screen:
              type: "String"
              value: "{{inputs.path}}"
        - type: "OpenLink"
          link: "example://{{inputs.path}}?app={{appId}}"
        goal: "Confirm the {{inputs.path}} screen is shown"
      """.trimIndent()
    ).scenarioOf("open-search", device).runInitializers(device)

    assertEquals("com.example.other", device.executedCommands.mapNotNull { it.clearStateCommand }.single().appId)
    val launch = device.executedCommands.mapNotNull { it.launchAppCommand }.single()
    assertEquals("com.example.other", launch.appId)
    assertEquals(mapOf("screen" to "search"), launch.launchArguments)
    // Call-site inputs and project variables resolve in the same field.
    assertEquals("example://search?app=com.example.app", device.executedCommands.mapNotNull { it.openLinkCommand }.single().link)
  }

  @Test
  fun undeclaredInputInLaunchAppPackageNameFailsAtLoad() {
    val error = assertFailsWith<ArbigentProjectValidationException> {
      load(
        """
        scenarios:
        - id: "caller"
          uses: "part"
        reusableScenarios:
        - id: "part"
          initializationMethods:
          - type: "LaunchApp"
            packageName: "{{inputs.typo}}"
          goal: "Open the app"
        """.trimIndent()
      )
    }
    assertTrue(error.message!!.contains("'{{inputs.typo}}' is not declared in inputs"), error.message)
  }

  @Test
  fun undeclaredInputInLegacySingularInitializeMethodsFailsAtLoad() {
    val error = assertFailsWith<ArbigentProjectValidationException> {
      load(
        """
        scenarios:
        - id: "caller"
          uses: "part"
        reusableScenarios:
        - id: "part"
          initializeMethods:
            type: "LaunchApp"
            packageName: "{{inputs.typo}}"
          goal: "Open the app"
        """.trimIndent()
      )
    }
    assertTrue(error.message!!.contains("'{{inputs.typo}}' is not declared in inputs"), error.message)
  }

  @Test
  fun escapedPlaceholderInLaunchArgumentIsPassedLiterally() {
    val device = RecordingDevice()
    load(
      """
      scenarios:
      - id: "caller"
        uses: "open-screen"
        with:
          screen: "search"
      reusableScenarios:
      - id: "open-screen"
        inputs:
          screen:
            required: true
        initializationMethods:
        - type: "LaunchApp"
          packageName: "com.example.app"
          launchArguments:
            screen:
              type: "String"
              value: "{{inputs.screen}}"
            template:
              type: "String"
              value: "\\{{inputs.screen}}"
        goal: "Open the screen"
      """.trimIndent()
    ).scenarioOf("caller", device).runInitializers(device)

    assertEquals(
      mapOf("screen" to "search", "template" to "{{inputs.screen}}"),
      device.executedCommands.mapNotNull { it.launchAppCommand }.single().launchArguments
    )
  }

  @Test
  fun bracesThatAreNotVariableNamesAreLeftLiteral() {
    val device = RecordingDevice()
    load(
      """
      scenarios:
      - id: "open-link"
        goal: "Open the link"
        initializationMethods:
        - type: "OpenLink"
          link: "example://open?template={{user:id}}"
      """.trimIndent()
    ).scenarioOf("open-link", device).runInitializers(device)

    // `user:id` is outside the variable-name grammar, so it is text, not an unresolved variable.
    assertEquals(
      "example://open?template={{user:id}}",
      device.executedCommands.mapNotNull { it.openLinkCommand }.single().link
    )
  }

  @Test
  fun variableValuesAreNotResolvedAgain() {
    val device = RecordingDevice()
    load(
      """
      settings:
        variables:
          link: "example://open?next={{missing}}"
      scenarios:
      - id: "open-link"
        goal: "Open the link"
        initializationMethods:
        - type: "OpenLink"
          link: "{{link}}"
      """.trimIndent()
    ).scenarioOf("open-link", device).runInitializers(device)

    // Substitution is single-pass: a placeholder inside a value is data, not a second reference.
    assertEquals(
      "example://open?next={{missing}}",
      device.executedCommands.mapNotNull { it.openLinkCommand }.single().link
    )
  }

  @Test
  fun inputsInOpenLinkOutsideReusableScenariosFailAtLoad() {
    val error = assertFailsWith<ArbigentProjectValidationException> {
      load(
        """
        scenarios:
        - id: "open-link"
          initializationMethods:
          - type: "OpenLink"
            link: "example://{{inputs.path}}"
          goal: "Open the link"
        """.trimIndent()
      )
    }
    assertTrue(error.message!!.contains("'{{inputs.*}}' can only be used inside reusable scenario definitions"), error.message)
  }

  @Test
  fun undeclaredInputInInlineMaestroYamlFailsAtLoad() {
    val error = assertFailsWith<ArbigentProjectValidationException> {
      load(
        """
        scenarios:
        - id: "caller"
          uses: "part"
        reusableScenarios:
        - id: "part"
          initializationMethods:
          - type: "MaestroYaml"
            scenarioId: "flow"
            yamlContent: |-
              appId: "com.example.app"
              ---
              - openLink: "example://login?user={{inputs.typo}}"
          goal: "Open the app"
        fixedScenarios:
        - id: "flow"
          title: "flow"
          description: "flow"
          yamlText: |-
            appId: "com.example.app"
            ---
            - openLink: "example://login"
        """.trimIndent()
      )
    }
    assertTrue(error.message!!.contains("'{{inputs.typo}}' is not declared in inputs"), error.message)
  }

  @Test
  fun inlineMaestroYamlTakesPrecedenceWhenInputsAreResolved() {
    val project = load(
      """
      scenarios:
      - id: "caller"
        uses: "part"
        with:
          user: "premium"
      reusableScenarios:
      - id: "part"
        inputs:
          user:
            required: true
        initializationMethods:
        - type: "MaestroYaml"
          scenarioId: "flow"
          yamlContent: |-
            appId: "com.example.app"
            ---
            - openLink: "example://inline?user={{inputs.user}}"
        goal: "Open the app"
      fixedScenarios:
      - id: "flow"
        title: "flow"
        description: "flow"
        yamlText: |-
          appId: "com.example.app"
          ---
          - openLink: "example://fixed"
      """.trimIndent()
    )
    val device = RecordingDevice()
    project.scenarioOf("caller", device).runInitializers(device)
    assertEquals(
      "example://inline?user=premium",
      device.executedCommands.mapNotNull { it.openLinkCommand }.firstOrNull()?.link
    )
  }
}
