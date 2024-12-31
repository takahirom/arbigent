package com.github.takahirom.arbiter

import com.github.takahirom.arbiter.ArbiterScenarioExecutor.ArbiterAgentTask
import java.io.File

class ArbiterProject(private val arbiterScenarios: List<ArbiterScenario>) {
  suspend fun execute() {
    arbiterScenarios.forEach { scenario ->
      arbiterInfoLog("Start scenario: ${scenario.arbiterAgentTasks.last().goal}")
      val executor = ArbiterScenarioExecutor()
      executor.execute(scenario)

      arbiterDebugLog(executor.statusText())
      if (!executor.isGoalArchived()) {
        error(
          "Failed to archive " + executor.statusText()
        )
      }
    }
  }
}

fun ArbiterProject(
  arbiterProjectFileContent: ArbiterProjectFileContent,
  aiFactory: () -> ArbiterAi,
  deviceFactory: () -> ArbiterDevice
): ArbiterProject {
  return ArbiterProject(
    arbiterProjectFileContent.scenarios.map {
      arbiterProjectFileContent.scenarios.createArbiterScenario(
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
  val arbiterAgentTasks: List<ArbiterAgentTask>,
  val maxRetry: Int = 0,
  val maxStepCount: Int = 10,
  val deviceFormFactor: ArbiterScenarioDeviceFormFactor = ArbiterScenarioDeviceFormFactor.Mobile,
)
