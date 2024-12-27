package com.github.takahirom.arbiter

import kotlinx.coroutines.flow.MutableStateFlow

class ArbiterContextHolder(
  val goal: String,
) {
  class Turn(
    val agentCommand: AgentCommand? = null,
    val action: String? = null,
    val memo: String,
    val whatYouSaw: String? = null,
    val message: String? = null,
    val screenshotFileName: String
  ) {
    fun text(): String {
      return """
        action: $agentCommand
        memo: $memo
        whatYouSaw: $whatYouSaw
      """.trimIndent()
    }
  }

  val turns = MutableStateFlow<List<Turn>>(listOf())
  fun addTurn(turn: Turn) {
    println("addTurn: $turn")
    turns.value = turns.value.toMutableList() + turn
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