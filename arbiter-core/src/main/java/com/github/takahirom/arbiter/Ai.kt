package com.github.takahirom.arbiter

interface Ai {
  data class DecisionInput(
    val arbiterContextHolder: ArbiterContextHolder,
    val dumpHierarchy: String,
    val agentCommandTypes: List<AgentCommandType>,
    val screenshotFileName: String,
  )
  data class DecisionOutput(
    val agentCommands: List<AgentCommand>,
    val turn: ArbiterContextHolder.Turn
  )
  fun decideWhatToDo(
    decisionInput: DecisionInput
  ): DecisionOutput
}
