package io.github.takahirom.arbigent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

public class ArbigentProject(initialArbigentScenarios: List<ArbigentScenario>) {
  private val _scenarioAssignmentsFlow =
    MutableStateFlow<List<ArbigentScenarioAssignment>>(listOf())
  public val scenarioAssignmentsFlow: Flow<List<ArbigentScenarioAssignment>> =
    _scenarioAssignmentsFlow.asSharedFlow()

  public fun scenarioAssignments(): List<ArbigentScenarioAssignment> = _scenarioAssignmentsFlow.value
  public val scenarios: List<ArbigentScenario> get() = scenarioAssignments().map { it.scenario }

  init {
    _scenarioAssignmentsFlow.value = initialArbigentScenarios.map { scenario ->
      ArbigentScenarioAssignment(scenario, ArbigentScenarioExecutor())
    }
  }

  public class FailedToArchiveException(message: String) : RuntimeException(message)

  public suspend fun execute() {
    scenarioAssignments().forEach { (scenario, scenarioExecutor) ->
      arbigentInfoLog("Start scenario: $scenario")
      scenarioExecutor.execute(scenario)

      arbigentDebugLog(scenarioExecutor.statusText())
      if (!scenarioExecutor.isGoalArchived()) {
        throw FailedToArchiveException(
          "Failed to archive scenario:" + scenarioExecutor.statusText()
        )
      }
    }
  }

  public suspend fun execute(scenario: ArbigentScenario) {
    arbigentInfoLog("Start scenario: ${scenario}")
    val scenarioExecutor =
      scenarioAssignments().first { it.scenario.id == scenario.id }.scenarioExecutor
    scenarioExecutor.execute(scenario)
    arbigentDebugLog(scenarioExecutor.statusText())
    if (!scenarioExecutor.isGoalArchived()) {
      throw FailedToArchiveException(
        "Failed to archive scenario:" + scenarioExecutor.statusText()
      )
    }
  }

  public fun cancel() {
    scenarioAssignments().forEach { (_, scenarioExecutor) ->
      scenarioExecutor.cancel()
    }
  }
}

public fun ArbigentProject(
  arbigentProjectFileContent: ArbigentProjectFileContent,
  aiFactory: () -> ArbigentAi,
  deviceFactory: () -> ArbigentDevice
): ArbigentProject {
  return ArbigentProject(
    arbigentProjectFileContent.scenarioContents.map {
      arbigentProjectFileContent.scenarioContents.createArbigentScenario(
        scenario = it,
        aiFactory = aiFactory,
        deviceFactory = deviceFactory
      )
    }
  )
}

public fun ArbigentProject(
  file: File,
  aiFactory: () -> ArbigentAi,
  deviceFactory: () -> ArbigentDevice
): ArbigentProject {
  val arbigentProjectFileContent = ArbigentProjectSerializer().load(file)
  return ArbigentProject(arbigentProjectFileContent, aiFactory, deviceFactory)
}

public data class ArbigentScenario(
  val id: String,
  val agentTasks: List<ArbigentAgentTask>,
  val maxRetry: Int = 0,
  val maxStepCount: Int = 10,
  val deviceFormFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
)