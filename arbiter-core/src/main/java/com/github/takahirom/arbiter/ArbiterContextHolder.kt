package com.github.takahirom.arbiter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ArbiterContextHolder(
  private val goal: String,
) {
  class Turn(
    val agentCommand: AgentCommand? = null,
    val action: String? = null,
    val memo: String,
    val whatYouSaw: String? = null,
    val aiRequest: String? = null,
    val aiResponse: String? = null,
    val screenshotFileName: String
  ) {
    fun text(): String {
      return """
        action: ${agentCommand?.turnLogText()}
        memo: $memo
        whatYouSaw: $whatYouSaw
      """.trimIndent()
    }
  }

  private val _turns = MutableStateFlow<List<Turn>>(listOf())
  val turns = _turns.asStateFlow()
  fun addTurn(turn: Turn) {
    println("addTurn: $turn")
    _turns.value = turns.value.toMutableList() + turn
  }

  fun prompt(): String {
    return """
Goal: "$goal"
Turns so far:
${
      turns.value.mapIndexed { index, turn ->
        "${index + 1}. \n" +
          turn.text()
      }.joinToString("\n")
    }
    """.trimIndent()
  }
}