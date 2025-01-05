package io.github.takahirom.arbiter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

public class ArbiterProject(initialArbiterScenarios: List<ArbiterScenario>) {
  private val _scenarioAssignmentsFlow =
    MutableStateFlow<List<ArbiterScenarioAssignment>>(listOf())
  public val scenarioAssignmentsFlow: Flow<List<ArbiterScenarioAssignment>> =
    _scenarioAssignmentsFlow.asSharedFlow()

  public fun scenarioAssignments(): List<ArbiterScenarioAssignment> = _scenarioAssignmentsFlow.value
  public val scenarios: List<ArbiterScenario> get() = scenarioAssignments().map { it.scenario }

  init {
    _scenarioAssignmentsFlow.value = initialArbiterScenarios.map { scenario ->
      ArbiterScenarioAssignment(scenario, ArbiterScenarioExecutor())
    }
  }

  public suspend fun execute() {
    scenarioAssignments().forEach { (scenario, scenarioExecutor) ->
      arbiterInfoLog("Start scenario: $scenario")
      scenarioExecutor.execute(scenario)

      arbiterDebugLog(scenarioExecutor.statusText())
      if (!scenarioExecutor.isGoalArchived()) {
        error(
          "Failed to archive " + scenarioExecutor.statusText()
        )
      }
    }
  }

  public suspend fun execute(scenario: ArbiterScenario) {
    arbiterInfoLog("Start scenario: ${scenario}")
    val scenarioExecutor =
      scenarioAssignments().first { it.scenario.id == scenario.id }.scenarioExecutor
    scenarioExecutor.execute(scenario)
    arbiterDebugLog(scenarioExecutor.statusText())
    if (!scenarioExecutor.isGoalArchived()) {
      error(
        "Failed to archive " + scenarioExecutor.statusText()
      )
    }
  }

  public fun cancel() {
    scenarioAssignments().forEach { (_, scenarioExecutor) ->
      scenarioExecutor.cancel()
    }
  }
}

public fun ArbiterProject(
  arbiterProjectFileContent: ArbiterProjectFileContent,
  aiFactory: () -> ArbiterAi,
  deviceFactory: () -> ArbiterDevice
): ArbiterProject {
  return ArbiterProject(
    arbiterProjectFileContent.scenarioContents.map {
      arbiterProjectFileContent.scenarioContents.createArbiterScenario(
        scenario = it,
        aiFactory = aiFactory,
        deviceFactory = deviceFactory
      )
    }
  )
}

public fun ArbiterProject(
  file: File,
  aiFactory: () -> ArbiterAi,
  deviceFactory: () -> ArbiterDevice
): ArbiterProject {
  val arbiterProjectFileContent = ArbiterProjectSerializer().load(file)
  return ArbiterProject(arbiterProjectFileContent, aiFactory, deviceFactory)
}

public data class ArbiterScenario(
  val id: String,
  val agentTasks: List<ArbiterAgentTask>,
  val maxRetry: Int = 0,
  val maxStepCount: Int = 10,
  val deviceFormFactor: ArbiterScenarioDeviceFormFactor = ArbiterScenarioDeviceFormFactor.Mobile,
)