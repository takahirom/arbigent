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
  /**
   * Every `{{name}}` reference in this task's goal and initialization methods that the run's
   * variables do not define. Building a scenario collects these instead of throwing, so one task
   * with a typo neither stops the project from loading nor blocks the tasks that are fine; the
   * check belongs to the task so running a subset of the chain only checks that subset.
   */
  val unresolvedVariables: List<ArbigentUnresolvedVariable> = emptyList(),
)
