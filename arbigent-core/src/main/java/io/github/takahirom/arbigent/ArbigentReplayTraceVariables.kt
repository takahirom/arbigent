package io.github.takahirom.arbigent

/**
 * Keeps project variable values out of stored replay traces.
 *
 * A goal is resolved before the AI sees it, so what the AI types, clicks and writes in its notes
 * carries the values themselves, and a step records far more than its action: the request the AI
 * was sent, the UI tree it looked at, the element it targeted. A value that reached the scenario
 * through a variable — a test account, a password, an application id — would otherwise sit in
 * plain text in every one of those. Before a trace is written, every occurrence of every variable
 * value becomes its `{{name}}` placeholder, and the names used are kept on the trace. When the
 * trace is read back, the same names are resolved against the run's variables. A name the run no
 * longer defines cannot be filled in, so the trace is rejected rather than replayed with a
 * placeholder typed where a value belongs.
 *
 * Only values that entered through a variable are protected. Text the AI typed from the goal's own
 * words is recorded as it was typed.
 */
internal object ArbigentReplayTraceVariables {
  fun toPlaceholders(trace: ArbigentReplayTrace, variables: Map<String, String>): ArbigentReplayTrace {
    // A blank value matches everywhere and nowhere in particular; there is nothing to protect.
    val protectable = variables.filterValues { value -> value.isNotBlank() }
    if (protectable.isEmpty()) return trace
    // One pass, longest value first: a value that contains another is replaced whole, and nothing
    // ever matches inside a placeholder this pass just inserted.
    val byValue = protectable.entries
      .sortedByDescending { entry -> entry.value.length }
      .distinctBy { entry -> entry.value }
    val nameByValue = byValue.associate { entry -> entry.value to entry.key }
    val pattern = byValue.joinToString("|") { entry -> Regex.escape(entry.value) }.toRegex()
    val used = linkedSetOf<String>()
    val protected = trace.mapStrings { text ->
      pattern.replace(text) { match ->
        val name = nameByValue.getValue(match.value)
        used += name
        "{{$name}}"
      }
    }
    return protected.copy(variableNames = used.toList())
  }

  sealed interface Resolution {
    data class Resolved(val trace: ArbigentReplayTrace) : Resolution
    data class Unresolved(val missingNames: List<String>) : Resolution
  }

  fun fromPlaceholders(trace: ArbigentReplayTrace, variables: Map<String, String>): Resolution {
    if (trace.variableNames.isEmpty()) return Resolution.Resolved(trace)
    val missing = trace.variableNames.filter { name -> variables[name].isNullOrBlank() }
    if (missing.isNotEmpty()) return Resolution.Unresolved(missing)
    // Only the names this trace recorded are placeholders here. Any other `{{...}}` in its text
    // was there when it was recorded and is left exactly as it was.
    val pattern = trace.variableNames
      .joinToString("|", prefix = "\\{\\{(", postfix = ")\\}\\}") { name -> Regex.escape(name) }
      .toRegex()
    val resolved = trace.mapStrings { text ->
      pattern.replace(text) { match -> variables.getValue(match.groupValues[1]) }
    }
    return Resolution.Resolved(resolved)
  }

  private fun ArbigentReplayTrace.mapStrings(transform: (String) -> String): ArbigentReplayTrace =
    copy(
      steps = steps.map { step ->
        ArbigentReplayTraceStep(
          decisionOutput = ArbigentAi.DecisionOutput(
            agentActions = step.decisionOutput.agentActions.map { action -> action.mapStrings(transform) },
            step = step.decisionOutput.step.mapStrings(transform),
          ),
        )
      },
    )

  private fun ArbigentContextHolder.Step.mapStrings(transform: (String) -> String): ArbigentContextHolder.Step =
    copy(
      agentAction = agentAction?.mapStrings(transform),
      action = action?.let(transform),
      feedback = feedback?.let(transform),
      memo = memo?.let(transform),
      imageDescription = imageDescription?.let(transform),
      aiRequest = aiRequest?.let(transform),
      aiResponse = aiResponse?.let(transform),
      uiTreeStrings = uiTreeStrings?.let { strings ->
        strings.copy(
          allTreeString = transform(strings.allTreeString),
          optimizedTreeString = transform(strings.optimizedTreeString),
          aiHints = strings.aiHints.map(transform),
        )
      },
      targetElement = targetElement?.let { identity ->
        identity.copy(
          text = identity.text?.let(transform),
          resourceId = identity.resourceId?.let(transform),
          accessibilityId = identity.accessibilityId?.let(transform),
        )
      },
      focusedElement = focusedElement?.let { focus ->
        focus.copy(resourceId = focus.resourceId?.let(transform))
      },
    )

  /**
   * The text an action carries to the device. Key names, indexes and counts carry no text; MCP
   * tool arguments are structured and left alone.
   */
  private fun ArbigentAgentAction.mapStrings(transform: (String) -> String): ArbigentAgentAction =
    when (this) {
      is InputTextAgentAction -> copy(text = transform(text))
      is ClickWithTextAgentAction -> copy(textRegex = transform(textRegex))
      is ClickWithIdAgentAction -> copy(textRegex = transform(textRegex))
      is DpadAutoFocusWithTextAgentAction -> copy(text = transform(text))
      is DpadAutoFocusWithIdAgentAction -> copy(id = transform(id))
      else -> this
    }
}
