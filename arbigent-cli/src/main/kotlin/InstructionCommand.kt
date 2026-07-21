@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import io.github.takahirom.arbigent.*
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import java.io.File

/**
 * Prints AI-oriented reproduction instructions for one or more scenarios: the ordered chain of
 * scenarios from the root dependency to the target, so an AI agent can manually reproduce the
 * flow on a device. Read-only; needs no AI key and no device.
 */
class ArbigentInstructionCommand : CliktCommand(name = "instruction") {
  private val projectFile by projectFileOption()
  private val logLevel by logLevelOption()
  private val scenarioIds by option(
    "--scenario-ids",
    help = "Comma-separated scenario ids to print reproduction instructions for"
  ).split(",").required()
  private val includeAppUiStructure by option(
    "--include-app-ui-structure",
    help = "Include the project's appUiStructure in the output when it is non-empty"
  ).flag(default = false)

  /** One resolved leaf in a scenario chain, ready to render as a numbered step. */
  private data class ResolvedStep(
    val id: String,
    val goal: String,
    val initializationMethods: List<ArbigentScenarioContent.InitializationMethod>,
    val note: String,
    val imageAssertions: List<ArbigentImageAssertion>,
  )

  override fun run() {
    applyLogLevel(logLevel)
    val projectFilePath = requireProjectFile(projectFile)
    val projectFileContent = if (isJourneyProjectSource(projectFilePath)) {
      ArbigentJourneyXmlImporter.loadProjectContent(File(projectFilePath))
    } else {
      ArbigentProjectSerializer().load(File(projectFilePath))
    }

    val scenariosById = projectFileContent.scenarioContents.associateBy { it.id }
    val reusableById = projectFileContent.reusableScenarios.associateBy { it.id }

    scenarioIds.forEachIndexed { index, targetId ->
      val target = scenariosById[targetId] ?: throw CliktError(
        "Unknown scenario id '$targetId'. Available scenario ids: " +
          projectFileContent.scenarioContents.joinToString(", ") { it.id }
      )
      if (index > 0) echo("")
      echo(renderInstructions(projectFileContent, scenariosById, reusableById, target))
    }
  }

  private fun renderInstructions(
    projectFileContent: ArbigentProjectFileContent,
    scenariosById: Map<String, ArbigentScenarioContent>,
    reusableById: Map<String, ArbigentScenarioContent>,
    target: ArbigentScenarioContent,
  ): String {
    val steps = resolveChain(scenariosById, reusableById, target)

    val out = StringBuilder()
    out.appendLine("# Instructions to reach scenario: ${target.id}")
    out.appendLine()
    out.appendLine("Execute the following scenarios in order. Each goal describes what to")
    out.appendLine("accomplish on the device; explore the UI as needed to achieve it.")

    if (includeAppUiStructure) {
      val appUiStructure = projectFileContent.settings.prompt.appUiStructure
      if (appUiStructure.isNotBlank()) {
        out.appendLine()
        out.appendLine("## App UI structure")
        out.appendLine()
        out.appendLine(appUiStructure.trim())
      }
    }

    val unresolvedVariables = steps
      .flatMap { unresolvedPlaceholders(it.goal) }
      .distinct()
    if (unresolvedVariables.isNotEmpty()) {
      out.appendLine()
      out.appendLine("Variables (unresolved; provide with `run --variables`):")
      unresolvedVariables.forEach { out.appendLine("- $it") }
    }

    steps.forEachIndexed { index, step ->
      out.appendLine()
      out.appendLine("## Step ${index + 1}: ${step.id}")
      val initLines = step.initializationMethods.mapNotNull { renderInitializationMethod(it) }
      if (initLines.isNotEmpty()) {
        out.appendLine("- Initialization:")
        initLines.forEach { out.appendLine("  - $it") }
      }
      out.appendLine("- Goal: ${step.goal}")
      if (step.note.isNotBlank()) {
        out.appendLine("- Note: ${step.note.trim()}")
      }
      step.imageAssertions.forEach { assertion ->
        out.appendLine("- Verification: ${assertion.assertionPrompt}")
      }
    }

    out.appendLine()
    out.append("Device form factor: ${effectiveDeviceFormFactorName(projectFileContent, target)}")
    return out.toString()
  }

  /**
   * Walks [target]'s dependency chain (dependency-first) and expands reusable `uses`/`steps` calls
   * into the concrete leaves that actually execute, resolving `{{inputs.*}}` bindings along the way.
   * Mirrors the runtime expansion in `createArbigentScenario`, but keeps each leaf's own
   * initialization methods and image assertions so they can be rendered.
   */
  private fun resolveChain(
    scenariosById: Map<String, ArbigentScenarioContent>,
    reusableById: Map<String, ArbigentScenarioContent>,
    target: ArbigentScenarioContent,
  ): List<ResolvedStep> {
    val steps = mutableListOf<ResolvedStep>()
    val visited = mutableSetOf<String>()

    fun leaf(content: ArbigentScenarioContent, bindings: Map<String, String>) {
      @Suppress("DEPRECATION")
      val rawInitializationMethods =
        content.initializationMethods.ifEmpty { listOf(content.initializeMethods) }
      steps.add(
        ResolvedStep(
          id = content.id,
          goal = ReusableInputsResolver.resolve(content.goal, bindings),
          initializationMethods = rawInitializationMethods.map { resolveInitializationMethod(it, bindings) },
          note = ReusableInputsResolver.resolve(content.noteForHumans, bindings),
          imageAssertions = content.imageAssertions.map {
            it.copy(assertionPrompt = ReusableInputsResolver.resolve(it.assertionPrompt, bindings))
          },
        )
      )
    }

    fun expandStep(
      step: ArbigentScenarioContent.ReusableStep,
      parentBindings: Map<String, String>,
      expansionStack: List<String>,
    ) {
      if (expansionStack.contains(step.uses)) {
        throw CliktError(
          "Cyclic reusable scenario reference detected: ${(expansionStack + step.uses).joinToString(" -> ")}"
        )
      }
      val reusable = reusableById[step.uses] ?: throw CliktError(
        "Reusable scenario '${step.uses}' is not defined in reusableScenarios"
      )
      val resolvedWith = step.withValues.mapValues { (_, value) ->
        ReusableInputsResolver.resolve(value, parentBindings)
      }
      val defaults = reusable.inputs.mapNotNull { (name, input) -> input.default?.let { name to it } }.toMap()
      val bindings = defaults + resolvedWith
      if (reusable.isCallForm()) {
        reusable.callSteps().forEach { expandStep(it, bindings, expansionStack + step.uses) }
      } else {
        leaf(reusable, bindings)
      }
    }

    fun dfs(scenario: ArbigentScenarioContent) {
      if (!visited.add(scenario.id)) return
      scenario.dependencyId?.let { dependencyId ->
        val dependency = scenariosById[dependencyId] ?: throw CliktError(
          "Scenario '${scenario.id}' depends on unknown scenario '$dependencyId'."
        )
        dfs(dependency)
      }
      if (scenario.isCallForm()) {
        scenario.callSteps().forEach { expandStep(it, emptyMap(), emptyList()) }
      } else {
        leaf(scenario, emptyMap())
      }
    }

    dfs(target)
    return steps
  }

  private fun resolveInitializationMethod(
    method: ArbigentScenarioContent.InitializationMethod,
    bindings: Map<String, String>,
  ): ArbigentScenarioContent.InitializationMethod = when (method) {
    is ArbigentScenarioContent.InitializationMethod.LaunchApp ->
      method.copy(packageName = ReusableInputsResolver.resolve(method.packageName, bindings))
    is ArbigentScenarioContent.InitializationMethod.CleanupData ->
      method.copy(packageName = ReusableInputsResolver.resolve(method.packageName, bindings))
    is ArbigentScenarioContent.InitializationMethod.OpenLink ->
      method.copy(link = ReusableInputsResolver.resolve(method.link, bindings))
    else -> method
  }

  /** Human-readable one-line description of an initialization method, or null for no-ops. */
  private fun renderInitializationMethod(
    method: ArbigentScenarioContent.InitializationMethod,
  ): String? = when (method) {
    is ArbigentScenarioContent.InitializationMethod.LaunchApp -> {
      val args = method.launchArguments
        .takeIf { it.isNotEmpty() }
        ?.let { args -> ", arguments=" + args.entries.joinToString(", ") { "${it.key}=${it.value.value}" } }
        .orEmpty()
      "Launch app: package=${method.packageName}$args"
    }

    is ArbigentScenarioContent.InitializationMethod.CleanupData ->
      "Cleanup app data: package=${method.packageName}"

    is ArbigentScenarioContent.InitializationMethod.Back ->
      "Press back: times=${method.times}"

    is ArbigentScenarioContent.InitializationMethod.Wait ->
      "Wait: ${method.durationMs}ms"

    is ArbigentScenarioContent.InitializationMethod.OpenLink ->
      "Open link: ${method.link}"

    is ArbigentScenarioContent.InitializationMethod.MaestroYaml ->
      "Run Maestro flow: scenarioId=${method.scenarioId}"

    ArbigentScenarioContent.InitializationMethod.Noop -> null
  }

  private fun effectiveDeviceFormFactorName(
    projectFileContent: ArbigentProjectFileContent,
    target: ArbigentScenarioContent,
  ): String {
    val effective = when {
      !target.deviceFormFactor.isUnspecified() -> target.deviceFormFactor
      !projectFileContent.settings.deviceFormFactor.isUnspecified() -> projectFileContent.settings.deviceFormFactor
      else -> ArbigentScenarioDeviceFormFactor.Mobile
    }
    return if (effective.isTv()) "Tv" else "Mobile"
  }

  private fun unresolvedPlaceholders(text: String): List<String> =
    PLACEHOLDER_PATTERN.findAll(text).map { it.value }.toList()

  private companion object {
    private val PLACEHOLDER_PATTERN = """(?<!\\)\{\{\s*[^}]+\}\}""".toRegex()
  }
}
