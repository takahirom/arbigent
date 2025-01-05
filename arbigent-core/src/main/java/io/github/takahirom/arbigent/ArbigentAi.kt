package io.github.takahirom.arbigent

public interface ArbigentAi {
  public data class DecisionInput(
    val arbigentContextHolder: ArbigentContextHolder,
    val dumpHierarchy: String,
    // Only true if it is TV form factor
    val focusedTreeString: String?,
    val agentCommandTypes: List<AgentCommandType>,
    val screenshotFileName: String,
  )
  public data class DecisionOutput(
    val agentCommands: List<AgentCommand>,
    val step: ArbigentContextHolder.Step
  )
  public fun decideAgentCommands(
    decisionInput: DecisionInput
  ): DecisionOutput
}
