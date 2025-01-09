package io.github.takahirom.arbigent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow

public class ArbigentContextHolder(
  public val goal: String,
) {
  public class Step(
    public val agentCommand: ArbigentAgentCommand? = null,
    public val action: String? = null,
    public val memo: String,
    public val imageDescription: String? = null,
    public val uiTreeStrings: ArbigentUiTreeStrings? = null,
    public val aiRequest: String? = null,
    public val aiResponse: String? = null,
    public val screenshotFilePath: String
  ) {
    public fun isFailed(): Boolean {
      return memo.contains("Failed")
    }

    public fun text(): String {
      return buildString {
        imageDescription?.let { append("image description: $it\n") }
        append("memo: $memo\n")
        agentCommand?.let { append("action done: ${it.stepLogText()}\n") }
      }
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
Goal: "$goal"
What you did so far:
${
      steps().mapIndexed { index, turn ->
        "${index + 1}. \n" +
          turn.text()
      }.joinToString("\n")
    }
    """.trimIndent()
  }
}