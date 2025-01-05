package io.github.takahirom.arbiter

public interface ArbiterAi {
  public data class DecisionInput(
    val arbiterContextHolder: ArbiterContextHolder,
    val dumpHierarchy: String,
    // Only true if it is TV form factor
    val focusedTreeString: String?,
    val agentCommandTypes: List<AgentCommandType>,
    val screenshotFileName: String,
  )
  public data class DecisionOutput(
    val agentCommands: List<AgentCommand>,
    val step: ArbiterContextHolder.Step
  )
  public fun decideAgentCommands(
    decisionInput: DecisionInput
  ): DecisionOutput
}
