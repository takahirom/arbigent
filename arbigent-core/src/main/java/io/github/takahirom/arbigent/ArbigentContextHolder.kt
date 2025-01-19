package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentAgentTaskStepResult
import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow

public class ArbigentContextHolder(
  public val goal: String,
  public val maxStep: Int,
  public val startTimestamp: Long = System.currentTimeMillis(),
) {
  public class Step(
    public val agentCommand: ArbigentAgentCommand? = null,
    public val action: String? = null,
    public val feedback: String? = null,
    public val memo: String? = null,
    public val imageDescription: String? = null,
    public val uiTreeStrings: ArbigentUiTreeStrings? = null,
    public val aiRequest: String? = null,
    public val aiResponse: String? = null,
    public val timestamp: Long = System.currentTimeMillis(),
    public val screenshotFilePath: String
  ) {
    public fun isFailed(): Boolean {
      return feedback?.contains("Failed") == true
    }

    public fun text(): String {
      return buildString {
        imageDescription?.let { append("image description: $it\n") }
        memo?.let { append("memo: $it\n") }
        feedback?.let { append("feedback: $it\n") }
        agentCommand?.let { append("action done: ${it.stepLogText()}\n") }
      }
    }

    public fun getResult(): ArbigentAgentTaskStepResult {
      return ArbigentAgentTaskStepResult(
        summary = text(),
        timestamp = timestamp,
        screenshotFilePath = screenshotFilePath,
        agentCommand = agentCommand?.stepLogText(),
        uiTreeStrings = uiTreeStrings,
        aiRequest = aiRequest,
        aiResponse = aiResponse,
      )
    }
  }

  private val _steps = MutableStateFlow<List<Step>>(listOf())
  public val stepsFlow: Flow<List<Step>> = _steps.asSharedFlow()
  public fun steps(): List<Step> = _steps.value
  public fun addStep(step: Step) {
    _steps.value = steps().toMutableList() + step
  }

  public fun prompt(): String {
    return """
Goal:"$goal"

Your step:${steps().size + 1}
Max step:$maxStep

What you did so far:
${
      steps().mapIndexed { index, turn ->
        "Step:${index + 1}. \n" +
          turn.text()
      }.joinToString("\n")
    }
    """.trimIndent()
  }
}