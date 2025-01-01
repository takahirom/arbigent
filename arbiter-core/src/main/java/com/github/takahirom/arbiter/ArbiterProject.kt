package com.github.takahirom.arbiter

import com.github.takahirom.arbiter.ArbiterScenarioExecutor.ArbiterAgentTask
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class ArbiterProject(initialArbiterScenarios: List<ArbiterScenario>) {
  val scenarioAndExecutorsStateFlow =
    MutableStateFlow<List<Pair<ArbiterScenario, ArbiterScenarioExecutor>>>(listOf())
  val scenarios get() = scenarioAndExecutorsStateFlow.value.map { it.first }

  init {
    scenarioAndExecutorsStateFlow.value = initialArbiterScenarios.map { scenario ->
      scenario to ArbiterScenarioExecutor()
    }
  }

  suspend fun execute() {
    scenarioAndExecutorsStateFlow.value.forEach { (scenario, scenarioExecutor) ->
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

  suspend fun execute(scenario: ArbiterScenario) {
    arbiterInfoLog("Start scenario: ${scenario}")
    val scenarioExecutor =
      scenarioAndExecutorsStateFlow.value.first { it.first.id == scenario.id }.second
    scenarioExecutor.execute(scenario)
    arbiterDebugLog(scenarioExecutor.statusText())
    if (!scenarioExecutor.isGoalArchived()) {
      error(
        "Failed to archive " + scenarioExecutor.statusText()
      )
    }
  }

  fun cancel() {
    scenarioAndExecutorsStateFlow.value.forEach { (_, scenarioExecutor) ->
      scenarioExecutor.cancel()
    }
  }
}

fun ArbiterProject(
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

fun ArbiterProject(
  file: File,
  aiFactory: () -> ArbiterAi,
  deviceFactory: () -> ArbiterDevice
): ArbiterProject {
  val arbiterProjectFileContent = ArbiterProjectSerializer().load(file)
  return ArbiterProject(arbiterProjectFileContent, aiFactory, deviceFactory)
}

data class ArbiterScenario(
  val id: String,
  val agentTasks: List<ArbiterAgentTask>,
  val maxRetry: Int = 0,
  val maxStepCount: Int = 10,
  val deviceFormFactor: ArbiterScenarioDeviceFormFactor = ArbiterScenarioDeviceFormFactor.Mobile,
)