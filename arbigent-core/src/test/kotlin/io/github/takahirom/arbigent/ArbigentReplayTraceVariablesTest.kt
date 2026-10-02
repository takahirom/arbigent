package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArbigentReplayTraceVariablesTest {
  private val json = Json { useArrayPolymorphism = true }
  private val account = "alice@example.com"
  private val variables = mapOf("user" to account, "appId" to "com.example.app")

  @Test
  fun `a stored trace holds no variable value anywhere, and reads back as recorded`() {
    val recorded = traceOf(
      step(
        action = InputTextAgentAction(account),
        memo = "Typed $account into the e-mail field",
        aiRequest = "Log in with $account on com.example.app",
        uiTree = "EditText text=$account id=com.example.app:id/email",
        target = ArbigentElementIdentity(text = account, resourceId = "com.example.app:id/email"),
        focusResourceId = "com.example.app:id/email",
      ),
      step(action = ClickWithTextAgentAction("Continue as $account")),
      step(action = DpadAutoFocusWithIdAgentAction("com.example.app:id/next")),
    )

    val stored = ArbigentReplayTraceVariables.toPlaceholders(recorded, variables)
    val text = json.encodeToString(ArbigentReplayTrace.serializer(), stored)

    assertFalse(text.contains(account), "the account leaked into the stored trace: $text")
    assertFalse(text.contains("com.example.app"), "the application id leaked into the stored trace: $text")
    assertEquals(listOf("user", "appId"), stored.variableNames)
    assertEquals(
      InputTextAgentAction("{{user}}"),
      stored.steps[0].decisionOutput.agentActions.single(),
    )

    val readBack = json.decodeFromString(ArbigentReplayTrace.serializer(), text)
    val resolution = ArbigentReplayTraceVariables.fromPlaceholders(readBack, variables)
    assertEquals(recorded, assertIs<ArbigentReplayTraceVariables.Resolution.Resolved>(resolution).trace.copy(variableNames = emptyList()))
  }

  @Test
  fun `a trace recorded under a variable the run no longer defines is not resolved`() {
    val stored = ArbigentReplayTraceVariables.toPlaceholders(
      traceOf(step(action = InputTextAgentAction(account))),
      variables,
    )

    val resolution = ArbigentReplayTraceVariables.fromPlaceholders(stored, mapOf("appId" to "com.example.app"))

    assertEquals(listOf("user"), assertIs<ArbigentReplayTraceVariables.Resolution.Unresolved>(resolution).missingNames)
  }

  @Test
  fun `a value that contains another variable's value is replaced whole`() {
    val nested = mapOf("name" to "alice", "user" to account)
    val recorded = traceOf(step(action = InputTextAgentAction(account), memo = "alice signs in as $account"))

    val stored = ArbigentReplayTraceVariables.toPlaceholders(recorded, nested)

    assertEquals(InputTextAgentAction("{{user}}"), stored.steps[0].decisionOutput.agentActions.single())
    assertEquals("{{name}} signs in as {{user}}", stored.steps[0].decisionOutput.step.memo)
    val resolved = ArbigentReplayTraceVariables.fromPlaceholders(stored, nested)
    assertEquals(recorded, assertIs<ArbigentReplayTraceVariables.Resolution.Resolved>(resolved).trace.copy(variableNames = emptyList()))
  }

  @Test
  fun `a blank variable protects nothing and is not recorded`() {
    val recorded = traceOf(step(action = InputTextAgentAction("hello")))

    val stored = ArbigentReplayTraceVariables.toPlaceholders(recorded, mapOf("empty" to "", "blank" to "  "))

    assertEquals(recorded, stored)
    assertTrue(stored.variableNames.isEmpty())
  }

  @Test
  fun `a trace recorded with no variables is read back untouched`() {
    val legacy = traceOf(step(action = InputTextAgentAction("{{user}} literally"), memo = "{{appId}}"))

    val resolution = ArbigentReplayTraceVariables.fromPlaceholders(legacy, variables)

    assertEquals(legacy, assertIs<ArbigentReplayTraceVariables.Resolution.Resolved>(resolution).trace)
  }

  @Test
  fun `only the recorded names are placeholders when reading back`() {
    // The AI's own text happened to contain braces that look like a variable nothing defines.
    val recorded = traceOf(step(action = InputTextAgentAction(account), memo = "see {{nothing}}"))
    val stored = ArbigentReplayTraceVariables.toPlaceholders(recorded, variables)

    val resolution = ArbigentReplayTraceVariables.fromPlaceholders(stored, variables)

    assertEquals("see {{nothing}}", assertIs<ArbigentReplayTraceVariables.Resolution.Resolved>(resolution).trace.steps[0].decisionOutput.step.memo)
  }

  private fun traceOf(vararg steps: ArbigentReplayTraceStep): ArbigentReplayTrace = ArbigentReplayTrace(
    version = "1.2.3",
    scenarioId = "login",
    taskIndex = 0,
    taskIdentity = "login",
    goalHash = "hash",
    steps = steps.toList(),
  )

  private fun step(
    action: ArbigentAgentAction,
    memo: String? = null,
    aiRequest: String? = null,
    uiTree: String? = null,
    target: ArbigentElementIdentity? = null,
    focusResourceId: String? = null,
  ): ArbigentReplayTraceStep = ArbigentReplayTraceStep(
    decisionOutput = ArbigentAi.DecisionOutput(
      agentActions = listOf(action),
      step = ArbigentContextHolder.Step(
        stepId = "step",
        agentAction = action,
        action = action.stepLogText(),
        memo = memo,
        aiRequest = aiRequest,
        uiTreeStrings = uiTree?.let { ArbigentUiTreeStrings(allTreeString = it, optimizedTreeString = it) },
        cacheKey = "cache-key",
        timestamp = 1_000L,
        screenshotFilePath = "screenshot.png",
        targetElement = target,
        focusedElement = focusResourceId?.let {
          ArbigentFocusedElement(className = "android.view.View", x = 0, y = 0, width = 10, height = 10, resourceId = it)
        },
      ),
    ),
  )
}
