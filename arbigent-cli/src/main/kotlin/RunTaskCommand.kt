@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.defaultByName
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color.Companion.White
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.takahirom.arbigent.*
import io.ktor.client.request.*
import io.ktor.util.*
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

  private fun file(workingDirectory: String?, fileName: String): File {
    return if (workingDirectory.isNullOrBlank()) {
      File(fileName)
    } else {
      File(workingDirectory, fileName)
    }
  }

  private fun file(workingDirectory: String?, fileDir: String, fileName: String): File {
    return if (workingDirectory.isNullOrBlank()) {
      File(fileDir, fileName)
    } else {
      File(workingDirectory, fileDir + File.separator + fileName)
    }
  }

  override fun run() {
    // Validate AI configuration based on selected AI type
    val currentAiType = aiType
    when (currentAiType) {
      is OpenAIAiConfig -> {
        if (currentAiType.openAiApiKey.isNullOrBlank()) {
          throw CliktError("Missing OpenAI API key. Please provide via --openai-api-key, OPENAI_API_KEY environment variable, or in .arbigent/settings.local.yml")
        }
      }
      is GeminiAiConfig -> {
        if (currentAiType.geminiApiKey.isNullOrBlank()) {
          throw CliktError("Missing Gemini API key. Please provide via --gemini-api-key, GEMINI_API_KEY environment variable, or in .arbigent/settings.local.yml")
        }
      }
      is AzureOpenAiConfig -> {
        if (currentAiType.azureOpenAIEndpoint.isNullOrBlank()) {
          throw CliktError("Missing Azure OpenAI endpoint. Please provide via --azure-openai-endpoint or in .arbigent/settings.local.yml")
        }
        if (currentAiType.azureOpenAIKey.isNullOrBlank()) {
          throw CliktError("Missing Azure OpenAI API key. Please provide via --azure-openai-api-key, AZURE_OPENAI_API_KEY environment variable, or in .arbigent/settings.local.yml")
        }
      }
    }

    // Set log level early to avoid unwanted debug logs
    arbigentLogLevel =
      ArbigentLogLevel.entries.find { it.name.toLowerCasePreservingASCIIRules() == logLevel.toLowerCasePreservingASCIIRules() }
        ?: throw IllegalArgumentException(
          "Invalid log level. The log level should be one of ${
            ArbigentLogLevel.entries
              .joinToString(", ") { it.name.toLowerCasePreservingASCIIRules() }
          }")

    val resultDir = file(workingDirectory, defaultResultPath)
    resultDir.mkdirs()
    ArbigentFiles.parentDir = resultDir.absolutePath
    ArbigentFiles.screenshotsDir = File(resultDir, "screenshots")
    ArbigentFiles.jsonlsDir = File(resultDir, "jsonls")
    ArbigentFiles.logFile = file(workingDirectory, logFile)
    ArbigentFiles.cacheDir = file(workingDirectory, defaultCachePath + File.separator + BuildConfig.VERSION_NAME)
    ArbigentFiles.cacheDir.mkdirs()
    val resultFile = File(resultDir, "result.yml")
    val ai: ArbigentAi = aiType.let { aiType ->
      when (aiType) {
        is OpenAIAiConfig -> OpenAIAi(
          apiKey = aiType.openAiApiKey!!,
          baseUrl = aiType.openAiEndpoint,
          modelName = aiType.openAiModelName,
          loggingEnabled = aiApiLoggingEnabled,
        )

        is GeminiAiConfig -> OpenAIAi(
          apiKey = aiType.geminiApiKey!!,
          baseUrl = aiType.geminiEndpoint,
          modelName = aiType.geminiModelName,
          loggingEnabled = aiApiLoggingEnabled,
          jsonSchemaType = ArbigentAi.JsonSchemaType.GeminiOpenAICompatible
        )

        is AzureOpenAiConfig -> OpenAIAi(
          apiKey = aiType.azureOpenAIKey!!,
          baseUrl = aiType.azureOpenAIEndpoint!!,
          modelName = aiType.azureOpenAIModelName,
          loggingEnabled = aiApiLoggingEnabled,
          requestBuilderModifier = {
            parameter("api-version", aiType.azureOpenAIApiVersion)
            header("api-key", aiType.azureOpenAIKey!!)
          }
        )
      }
    }

    val os =
      ArbigentDeviceOs.entries.find { it.name.toLowerCasePreservingASCIIRules() == os.toLowerCasePreservingASCIIRules() }
        ?: throw IllegalArgumentException(
          "Invalid OS. The OS should be one of ${
            ArbigentDeviceOs.entries
              .joinToString(", ") { it.name.toLowerCasePreservingASCIIRules() }
          }")
    val device: ArbigentDevice = fetchAvailableDevicesByOs(os).firstOrNull()?.connectToDevice()
      ?: throw IllegalArgumentException("No available device found")

    try {
      val scenarioContent = ArbigentScenarioContent(
        id = "task",
        goal = goal,
        maxStep = maxStep,
        maxRetry = maxRetry,
        initializationMethods = listOf(
          ArbigentScenarioContent.InitializationMethod.Back(times = 5)
        )
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
          device.close()
        }
      })

      val isTerminal = System.console() != null
      if (isTerminal) {
        runInteractiveMode(arbigentProject, scenarios, resultFile, resultDir)
      } else {
        runNonInteractiveMode(arbigentProject, scenarios, resultFile, resultDir)
      }
    } catch (e: Exception) {
      device.close()
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
