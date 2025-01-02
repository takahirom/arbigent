package com.github.takahirom.arbiter

data class ArbiterAgentTask(
  val scenarioId: String,
  val goal: String,
  val agentConfig: AgentConfig,
  val maxStep: Int = 10,
  val deviceFormFactor: ArbiterScenarioDeviceFormFactor = ArbiterScenarioDeviceFormFactor.Mobile,
)
