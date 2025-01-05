package io.github.takahirom.arbigent

public data class ArbigentAgentTask(
  val scenarioId: String,
  val goal: String,
  val agentConfig: AgentConfig,
  val maxStep: Int = 10,
  val deviceFormFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
)
