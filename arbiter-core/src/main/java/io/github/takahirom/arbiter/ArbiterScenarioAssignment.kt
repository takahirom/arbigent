package io.github.takahirom.arbiter

public data class ArbiterScenarioAssignment(
  public val scenario: ArbiterScenario,
  public val scenarioExecutor: ArbiterScenarioExecutor
)