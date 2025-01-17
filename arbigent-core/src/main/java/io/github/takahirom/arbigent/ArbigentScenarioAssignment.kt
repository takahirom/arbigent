package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentAgentResults
import io.github.takahirom.arbigent.result.ArbigentScenarioResult

public data class ArbigentScenarioAssignment(
  public val scenario: ArbigentScenario,
  public val scenarioExecutor: ArbigentScenarioExecutor,
) {
  public fun getResult(): ArbigentScenarioResult {
    val taskAssignmentsHistory: List<List<ArbigentTaskAssignment>> = scenarioExecutor.taskAssignmentsHistory()
    return ArbigentScenarioResult(
      id = scenario.id,
      goal = scenario.goal(),
      executionStatus = scenarioExecutor.runningInfo()?.toString(),
      isSuccess = taskAssignmentsHistory.lastOrNull()?.all { it.agent.isGoalArchived() } ?: false,
      histories = taskAssignmentsHistory.mapIndexed { index, taskAssignments ->
        ArbigentAgentResults(
          status = "History ${index + 1} / " + taskAssignmentsHistory.size,
          agentResults = taskAssignments.map { (task, agent) ->
            agent.getResult()
          }
        )
      }
    )
  }
}