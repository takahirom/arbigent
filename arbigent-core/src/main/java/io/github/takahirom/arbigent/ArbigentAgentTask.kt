package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor

public data class ArbigentAgentTask(
  val scenarioId: String,
  val goal: String,
  val agentConfig: AgentConfig,
  val maxStep: Int = 10,
  val deviceFormFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
  val additionalActions: List<String> = emptyList(),
  val mcpOptions: ArbigentMcpOptions? = null,
  // Call chain like "scenario-id › reusable-id (user=paid)" when this task was expanded
  // from a reusable scenario; null for ordinary goal-based tasks.
  val callBreadcrumb: String? = null,
)
