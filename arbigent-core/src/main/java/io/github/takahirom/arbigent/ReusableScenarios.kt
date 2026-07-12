package io.github.takahirom.arbigent

/**
 * Thrown when a project file violates the reusable-scenario rules.
 * All violations are detected at load time so that a part is never silently skipped at runtime.
 */
public class ArbigentProjectValidationException(message: String) : RuntimeException(message)

/**
 * Resolves `{{inputs.name}}` placeholders with values bound at the call site (`with:` + declared defaults).
 * Bare `{{name}}` placeholders are project variables and stay untouched here — they are resolved
 * at runtime by [GoalVariableResolver].
 */
public object ReusableInputsResolver {
  // (?<!\\) keeps escaped placeholders (\{{inputs.x}}) for GoalVariableResolver's escape handling.
  private val INPUT_PATTERN = """(?<!\\)\{\{\s*inputs\.([^}]+)\}\}""".toRegex()

  public fun resolve(text: String, bindings: Map<String, String>): String {
    return INPUT_PATTERN.replace(text) { matchResult ->
      val name = matchResult.groupValues[1].trim()
      bindings[name] ?: matchResult.value
    }
  }

  public fun containsInputPlaceholder(text: String): Boolean = INPUT_PATTERN.containsMatchIn(text)

  /** Input names referenced as {{inputs.name}} in the given text. */
  public fun referencedInputNames(text: String): Set<String> =
    INPUT_PATTERN.findAll(text).map { it.groupValues[1].trim() }.toSet()

  /** Breadcrumb label like `login (user=paid)` used in reports and the UI tree. */
  public fun breadcrumbLabel(reusableId: String, withValues: Map<String, String>): String =
    if (withValues.isEmpty()) reusableId
    else "$reusableId (${withValues.entries.joinToString(", ") { "${it.key}=${it.value}" }})"
}

/**
 * Load-time validation of reusable scenarios. Throws [ArbigentProjectValidationException]
 * with all violations joined, so the user can fix everything in one pass.
 */
public fun ArbigentProjectFileContent.validateReusableScenarios() {
  val errors = mutableListOf<String>()
  val reusableById = reusableScenarios.associateBy { it.id }

  fun validateNode(content: ArbigentScenarioContent, isReusable: Boolean) {
    val where = (if (isReusable) "reusableScenarios" else "scenarios") + " '" + content.id + "'"
    val isCall = content.isCallForm()

    if (isCall) {
      if (content.goal.isNotEmpty()) {
        errors += "$where: a call-form scenario (uses/steps) must not have a goal"
      }
      if (content.uses != null && content.steps.isNotEmpty()) {
        errors += "$where: use either 'uses' or 'steps', not both"
      }
      if (content.initializationMethods.isNotEmpty()) {
        errors += "$where: initializationMethods are not allowed on a call-form scenario; move them into the reusable leaf"
      }
      if (content.imageAssertions.isNotEmpty()) {
        errors += "$where: imageAssertions are not allowed on a call-form scenario; move them into the reusable leaf"
      }
      if (content.type.isExecution()) {
        errors += "$where: type Execution is not allowed on a call-form scenario; set it on the reusable leaf"
      }
      if (content.aiOptions != null || content.mcpOptions != null || content.cacheOptions != null || content.additionalActions != null) {
        errors += "$where: execution options (aiOptions/mcpOptions/cacheOptions/additionalActions) are not allowed on a call-form scenario; set them on the reusable leaf"
      }
      // Fields with non-null defaults can only be checked against their default values;
      // an explicitly-written default is indistinguishable after decoding and is accepted.
      if (content.maxStep != 10) {
        errors += "$where: maxStep is not allowed on a call-form scenario; set it on the reusable leaf"
      }
      if (content.imageAssertionHistoryCount != 1) {
        errors += "$where: imageAssertionHistoryCount is not allowed on a call-form scenario; set it on the reusable leaf"
      }
      if (content.userPromptTemplate != UserPromptTemplate.DEFAULT_TEMPLATE) {
        errors += "$where: userPromptTemplate is not allowed on a call-form scenario; set it on the reusable leaf"
      }
      @Suppress("DEPRECATION")
      if (content.initializeMethods != ArbigentScenarioContent.InitializationMethod.Noop) {
        errors += "$where: initializeMethods are not allowed on a call-form scenario; move them into the reusable leaf"
      }
    } else {
      // Empty-goal leaves are only rejected in reusableScenarios (a new namespace with no
      // legacy files); ordinary scenarios could always be saved with an empty goal (WIP).
      if (content.goal.isEmpty() && isReusable) {
        errors += "$where: a reusable scenario must have a goal, uses, or steps"
      }
      if (content.withValues.isNotEmpty()) {
        errors += "$where: 'with' requires 'uses'"
      }
    }

    if (isReusable) {
      if (content.dependencyId != null) {
        errors += "$where: 'dependency' is not allowed on a reusable scenario; express prerequisites at the call site"
      }
      if (content.tags.isNotEmpty()) {
        errors += "$where: 'tags' are not allowed on a reusable scenario"
      }
      if (RESERVED_ID_CHARS.any { content.id.contains(it) }) {
        errors += "$where: reusable scenario ids must not contain '/', '#', or '@' (reserved for future cross-file references)"
      }
    } else {
      if (content.inputs.isNotEmpty()) {
        errors += "$where: 'inputs' can only be declared on reusableScenarios entries"
      }
    }

    // {{inputs.*}} may only appear in reusable definitions and only for declared inputs.
    val referencedInputs = ReusableInputsResolver.referencedInputNames(content.goal) +
      content.initializationMethods.filterIsInstance<ArbigentScenarioContent.InitializationMethod.MaestroYaml>()
        .mapNotNull { method -> fixedScenarios.firstOrNull { it.id == method.scenarioId }?.yamlText }
        .flatMap { ReusableInputsResolver.referencedInputNames(it) }
    if (isReusable) {
      (referencedInputs - content.inputs.keys).forEach {
        errors += "$where: '{{inputs.$it}}' is not declared in inputs"
      }
    } else if (referencedInputs.isNotEmpty()) {
      errors += "$where: '{{inputs.*}}' can only be used inside reusable scenario definitions"
    }

    // Call sites: references must resolve, with-keys must be declared, required inputs must be bound.
    content.callSteps().forEach { step ->
      if (RESERVED_ID_CHARS.any { step.uses.contains(it) }) {
        errors += "$where: uses '${step.uses}' must not contain '/', '#', or '@' (reserved for future cross-file references)"
        return@forEach
      }
      val target = reusableById[step.uses]
      if (target == null) {
        errors += "$where: uses '${step.uses}' is not defined in reusableScenarios"
        return@forEach
      }
      (step.withValues.keys - target.inputs.keys).forEach {
        errors += "$where: with key '$it' is not declared in inputs of '${step.uses}'"
      }
      target.inputs.forEach { (name, input) ->
        if (input.required && input.default == null && !step.withValues.containsKey(name)) {
          errors += "$where: required input '$name' of '${step.uses}' is not provided via 'with'"
        }
      }
      // {{inputs.*}} inside with-values is explicit propagation; only valid when the caller declares them.
      step.withValues.values.flatMap { ReusableInputsResolver.referencedInputNames(it) }.toSet()
        .minus(content.inputs.keys)
        .forEach {
          errors += "$where: with value references '{{inputs.$it}}' which is not declared in this scenario's inputs"
        }
    }
  }

  val duplicateReusableIds = reusableScenarios.groupBy { it.id }.filterValues { it.size > 1 }.keys
  duplicateReusableIds.forEach { errors += "reusableScenarios: duplicate id '$it'" }

  scenarioContents.forEach { validateNode(it, isReusable = false) }
  reusableScenarios.forEach { validateNode(it, isReusable = true) }

  // Cycle detection over composite references.
  val visitState = mutableMapOf<String, Int>() // 0 = visiting, 1 = done
  fun visit(id: String, path: List<String>) {
    when (visitState[id]) {
      0 -> {
        errors += "reusableScenarios: cyclic reference detected: ${(path + id).joinToString(" -> ")}"
        return
      }
      1 -> return
    }
    visitState[id] = 0
    reusableById[id]?.callSteps()?.forEach { step ->
      if (reusableById.containsKey(step.uses)) {
        visit(step.uses, path + id)
      }
    }
    visitState[id] = 1
  }
  reusableScenarios.forEach { visit(it.id, emptyList()) }

  if (errors.isNotEmpty()) {
    throw ArbigentProjectValidationException(
      "Invalid reusable scenario configuration:\n" + errors.joinToString("\n") { "- $it" }
    )
  }
}

private val RESERVED_ID_CHARS = listOf("/", "#", "@")
