package io.github.takahirom.arbigent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

public class FailedToArchiveException(message: String) : RuntimeException(message)

public class ArbigentProject(
  initialScenarios: List<ArbigentScenario>,
) {
  private val _scenarioAssignmentsFlow =
    MutableStateFlow<List<ArbigentScenarioAssignment>>(listOf())
  public val scenarioAssignmentsFlow: Flow<List<ArbigentScenarioAssignment>> =
    _scenarioAssignmentsFlow.asSharedFlow()

  public fun scenarioAssignments(): List<ArbigentScenarioAssignment> =
    _scenarioAssignmentsFlow.value

  public val scenarios: List<ArbigentScenario> get() = scenarioAssignments().map { it.scenario }

  init {
    _scenarioAssignmentsFlow.value = initialScenarios.map { scenario ->
      ArbigentScenarioAssignment(scenario, ArbigentScenarioExecutor())
    }
  }


  public suspend fun execute() {
    scenarioAssignments()
      .filter { it.scenario.isLeaf }
      .forEach { (scenario, scenarioExecutor) ->
        arbigentInfoLog("Start scenario: $scenario")
        try {
          scenarioExecutor.execute(scenario)
        } catch (e: FailedToArchiveException) {
          arbigentErrorLog("Failed to archive: $scenario" + e.stackTraceToString())
        }
        arbigentDebugLog(scenarioExecutor.statusText())
      }
  }

  public suspend fun execute(scenario: ArbigentScenario) {
    arbigentInfoLog("Start scenario: ${scenario}")
    val scenarioExecutor =
      scenarioAssignments().first { it.scenario.id == scenario.id }.scenarioExecutor
    scenarioExecutor.execute(scenario)
    arbigentDebugLog(scenarioExecutor.statusText())
  }

  public fun cancel() {
    scenarioAssignments().forEach { (_, scenarioExecutor) ->
      scenarioExecutor.cancel()
    }
  }

  public fun isSuccess(): Boolean {
    return scenarioAssignments().all { it.scenarioExecutor.isSuccess() }
  }
}

public fun ArbigentProject(
  projectFileContent: ArbigentProjectFileContent,
  aiFactory: () -> ArbigentAi,
  deviceFactory: () -> ArbigentDevice
): ArbigentProject {
  return ArbigentProject(
    projectFileContent.scenarioContents.map {
      projectFileContent.scenarioContents.createArbigentScenario(
        scenario = it,
        aiFactory = aiFactory,
        deviceFactory = deviceFactory
      )
    },
  )
}

public fun ArbigentProject(
  file: File,
  aiFactory: () -> ArbigentAi,
  deviceFactory: () -> ArbigentDevice
): ArbigentProject {
  val projectContentFileContent = ArbigentProjectContentSerializer().load(file)
  return ArbigentProject(projectContentFileContent, aiFactory, deviceFactory)
}

public data class ArbigentScenario(
  val id: String,
  val agentTasks: List<ArbigentAgentTask>,
  val maxRetry: Int = 0,
  val maxStepCount: Int = 10,
  val deviceFormFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
  val isLeaf: Boolean,
)
