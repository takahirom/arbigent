package com.github.takahirom.arbiter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ArbiterContextHolder(
  private val goal: String,
) {
  class Step(
    val agentCommand: AgentCommand? = null,
    val action: String? = null,
    val memo: String,
    val whatYouSaw: String? = null,
    val aiRequest: String? = null,
    val aiResponse: String? = null,
    val screenshotFileName: String
  ) {
    val screenshotFilePath = "screenshots/$screenshotFileName.png"
    fun isFailed(): Boolean {
      return memo.contains("Failed")
    }
    fun text(): String {
      return """
        memo: $memo
        whatYouSaw: $whatYouSaw
        action done: ${agentCommand?.stepLogText()}
      """.trimIndent()
    }
  }

  private val _steps = MutableStateFlow<List<Step>>(listOf())
  val steps = _steps.asStateFlow()
  fun addStep(step: Step) {
    _steps.value = steps.value.toMutableList() + step
  }

  fun prompt(): String {
    return """
Goal: "$goal"
What you did so far:
${
      steps.value.mapIndexed { index, turn ->
        "${index + 1}. \n" +
          turn.text()
      }.joinToString("\n")
    }
    """.trimIndent()
  }
}