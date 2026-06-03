@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.defaultByName
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.jakewharton.mosaic.ui.Color.Companion.White
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.takahirom.arbigent.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

@ArbigentInternalApi
class ArbigentRunTaskCommand : CliktCommand(name = "task") {

  private val goal by argument(help = "The goal for the task to execute")

  private val maxStep by defaultOption("--max-step", help = "Maximum number of steps")
    .int()
    .default(10)

  private val maxRetry by defaultOption("--max-retry", help = "Maximum number of retries")
    .int()
    .default(3)

  private val aiType by defaultOption("--ai-type", help = "Type of AI to use")
    .groupChoice(
      "openai" to OpenAIAiConfig(),
      "gemini" to GeminiAiConfig(),
      "azureopenai" to AzureOpenAiConfig()
    )
    .defaultByName("openai")

  private val aiApiLoggingEnabled by defaultOption(
    "--ai-api-logging",
    help = "Enable AI API debug logging"
  ).flag(default = false)

  private val os by defaultOption("--os", help = "Target operating system")
    .choice("android", "ios", "web")
    .default("android")

  private val logLevel by logLevelOption()
  private val logFile by logFileOption()
  private val workingDirectory by workingDirectoryOption()

  override fun run() {
    validateAiConfig(aiType)
    applyLogLevel(logLevel)

    val (resultDir, resultFile) = setupArbigentFiles(workingDirectory, logFile)
    val ai = createAi(aiType, aiApiLoggingEnabled)
    val device = kotlinx.coroutines.runBlocking { connectDevice(os) }

    try {
      val scenarioContent = ArbigentScenarioContent(
        id = "task",
        goal = goal,
        maxStep = maxStep,
        maxRetry = maxRetry,
        initializationMethods = emptyList()
      )
      val projectFileContent = ArbigentProjectFileContent(
        scenarioContents = listOf(scenarioContent),
        settings = ArbigentProjectSettings(
          cacheStrategy = CacheStrategy(AiDecisionCacheStrategy.Disk())
        )
      )
      val appSettings = CliAppSettings(workingDirectory = workingDirectory, path = null)
      val arbigentProject = ArbigentProject(projectFileContent, aiFactory = { ai }, deviceFactory = { device }, appSettings = appSettings)
      val scenarios = arbigentProject.scenarios

      Runtime.getRuntime().addShutdownHook(object : Thread() {
        override fun run() {
          arbigentProject.cancel()
          ArbigentProjectSerializer().save(arbigentProject.getResult(scenarios), resultFile)
          ArbigentHtmlReport().saveReportHtml(
            resultDir.absolutePath,
            arbigentProject.getResult(scenarios),
            needCopy = false
          )
          if (!device.isClosed()) device.close()
        }
      })

      val isTerminal = System.console() != null
      if (isTerminal) {
        runInteractiveMode(arbigentProject, scenarios, resultFile, resultDir)
      } else {
        runNonInteractiveMode(arbigentProject, scenarios, resultFile, resultDir)
      }
    } catch (e: Exception) {
      if (!device.isClosed()) device.close()
      throw e
    }
  }

  private fun runInteractiveMode(
    arbigentProject: ArbigentProject,
    scenarios: List<ArbigentScenario>,
    resultFile: File,
    resultDir: File
  ) {
    printLogger = { log -> ArbigentGlobalStatus.log(log) }

    runNoRawMosaicBlocking {
      LaunchedEffect(Unit) {
        logResultsLocation(resultFile, resultDir)

        arbigentProject.executeScenarios(scenarios)
        delay(100)

        if (arbigentProject.isScenariosSuccessful(scenarios)) {
          arbigentInfoLog("All scenarios completed successfully")
          logResultsAvailable(resultFile, resultDir)
          delay(100)
          exitProcess(0)
        } else {
          arbigentInfoLog("Some scenarios failed")
          logResultsAvailable(resultFile, resultDir)
          delay(100)
          exitProcess(1)
        }
      }

      Column {
        LogComponent()
        Text("─".repeat(80), color = White)

        val assignments by arbigentProject.scenarioAssignmentsFlow.collectAsState(arbigentProject.scenarioAssignments())

        scenarios.forEach { scenario ->
          val assignment = assignments.find { it.scenario.id == scenario.id }
          if (assignment != null) {
            ScenarioRow(scenario, assignment.scenarioExecutor)
          }
        }
      }
    }
  }

  private fun runNonInteractiveMode(
    arbigentProject: ArbigentProject,
    scenarios: List<ArbigentScenario>,
    resultFile: File,
    resultDir: File
  ) {
    runBlocking {
      logResultsLocation(resultFile, resultDir)

      arbigentProject.executeScenarios(scenarios)

      if (arbigentProject.isScenariosSuccessful(scenarios)) {
        arbigentInfoLog("All scenarios completed successfully")
        logResultsAvailable(resultFile, resultDir)
        exitProcess(0)
      } else {
        arbigentInfoLog("Some scenarios failed")
        logResultsAvailable(resultFile, resultDir)
        exitProcess(1)
      }
    }
  }
}
