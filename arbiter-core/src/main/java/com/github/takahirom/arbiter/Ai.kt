package com.github.takahirom.arbiter

interface Ai {
  data class DecisionInput(
    val arbiterContextHolder: ArbiterContextHolder,
    val dumpHierarchy: String,
    val agentCommandTypes: List<AgentCommandType>,
    val screenshotFileName: String,
  )
  data class DecisionOutput(
    val agentCommands: List<AgentCommand>
  )
  fun decideWhatToDo(
    decisionInput: DecisionInput
  ): DecisionOutput
}
