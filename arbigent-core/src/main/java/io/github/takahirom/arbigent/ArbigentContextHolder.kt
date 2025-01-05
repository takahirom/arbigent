package io.github.takahirom.arbigent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

public class ArbigentContextHolder(
  private val goal: String,
) {
  public class Step(
    public val agentCommand: AgentCommand? = null,
    public val action: String? = null,
    public val memo: String,
    public val whatYouSaw: String? = null,
    public val aiRequest: String? = null,
    public val aiResponse: String? = null,
    public val screenshotFileName: String
  ) {
    public val screenshotFilePath: String =  ArbigentTempDir.screenshotsDir.absolutePath + File.separator + "$screenshotFileName.png"
    public fun isFailed(): Boolean {
      return memo.contains("Failed")
    }
    public fun text(): String {
      return """
        memo: $memo
        whatYouSaw: $whatYouSaw
        action done: ${agentCommand?.stepLogText()}
      """.trimIndent()
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