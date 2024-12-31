package com.github.takahirom.arbiter

interface ArbiterAi {
  data class DecisionInput(
    val arbiterContextHolder: ArbiterContextHolder,
    val dumpHierarchy: String,
    // Only true if it is TV form factor
    val focusedTreeString: String?,
    val agentCommandTypes: List<AgentCommandType>,
    val screenshotFileName: String,
  )
  data class DecisionOutput(
    val agentCommands: List<AgentCommand>,
    val step: ArbiterContextHolder.Step
  )
  fun decideWhatToDo(
    decisionInput: DecisionInput
  ): DecisionOutput
}
