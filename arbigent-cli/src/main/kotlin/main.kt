package io.github.takahirom.arbigent.cli

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.defaultByName
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.sources.PropertiesValueSource
import com.github.ajalt.clikt.sources.ValueSource
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.NonInteractivePolicy
import com.jakewharton.mosaic.runMosaicBlocking
import com.jakewharton.mosaic.ui.Color.Companion
import com.jakewharton.mosaic.ui.Color.Companion.Black
import com.jakewharton.mosaic.ui.Color.Companion.Green
import com.jakewharton.mosaic.ui.Color.Companion.Red
import com.jakewharton.mosaic.ui.Color.Companion.White
import com.jakewharton.mosaic.ui.Color.Companion.Yellow
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.takahirom.arbigent.*
import io.ktor.client.request.*
import io.ktor.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Custom implementation of [ArbigentAppSettings] for CLI.
 */
data class CliAppSettings(
  override val workingDirectory: String?,
  override val path: String?
) : ArbigentAppSettings

sealed class AiConfig(name: String) : OptionGroup(name)

// Common options shared between commands - with fallback prompt for property file compatibility
fun CliktCommand.projectFileOption() = option("--project-file", help = "Path to the project YAML file")
  .prompt("Project file path")

fun CliktCommand.workingDirectoryOption() = option("--working-directory", help = "Working directory for the project")

fun CliktCommand.logLevelOption() = option("--log-level", help = "Log level")
  .choice("debug", "info", "warn", "error")
  .default("info")

fun CliktCommand.logFileOption() = option("--log-file", help = "Log file path")
  .default("$defaultResultPath/arbigent.log")

class OpenAIAiConfig : AiConfig("Options for OpenAI API AI") {
  private val defaultEndpoint = "https://api.openai.com/v1/"
  val openAiEndpoint by option("--openai-endpoint", help = "Endpoint URL (default: $defaultEndpoint)")
    .default(defaultEndpoint, defaultForHelp = defaultEndpoint)
  val openAiModelName by option("--openai-model-name", help = "Model name (default: gpt-4o-mini)")
    .default("gpt-4o-mini", "gpt-4o-mini")
  val openAiApiKey by option("--openai-api-key", "--openai-key", envvar = "OPENAI_API_KEY", help = "API key")
    .prompt("API key")
}

class GeminiAiConfig : AiConfig("Options for Gemini API AI") {
  private val defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta/openai/"
  val geminiEndpoint by option(help = "Endpoint URL (default: $defaultEndpoint)")
    .default(defaultEndpoint, defaultForHelp = defaultEndpoint)
  val geminiModelName by option(help = "Model name (default: gemini-1.5-flash)")
    .default("gemini-1.5-flash", "gemini-1.5-flash")
  val geminiApiKey by option(envvar = "GEMINI_API_KEY", help = "API key")
    .prompt("API key")
}

class AzureOpenAiConfig : AiConfig("Options for Azure OpenAI") {
  val azureOpenAIEndpoint by option("--azure-openai-endpoint", help = "Endpoint URL")
    .prompt("Endpoint URL")
  val azureOpenAIApiVersion by option("--azure-openai-api-version", help = "API version")
    .default("2024-10-21")
  val azureOpenAIModelName by option("--azure-openai-model-name", help = "Model name (default: gpt-4o-mini)")
    .default("gpt-4o-mini")
  val azureOpenAIKey by option("--azure-openai-api-key", "--azure-openai-key", envvar = "AZURE_OPENAI_API_KEY", help = "API key")
    .prompt("API key")
}

private const val defaultResultPath = "arbigent-result"
private const val defaultCachePath = "arbigent-cache"

class ArbigentCli : CliktCommand(name = "arbigent") {
  init {
    context {
      val propertiesFile = File("arbigent.properties")
      if (propertiesFile.exists()) {
        valueSource = PropertiesValueSource.from(
          propertiesFile.absolutePath,
          getKey = ValueSource.getKey(joinSubcommands = null)
        )
      }
    }
  }
  override fun run() = Unit
}

class ArbigentRunCommand : CliktCommand(name = "run") {
  
  private val aiType by option(help = "Type of AI to use")
    .groupChoice(
      "openai" to OpenAIAiConfig(),
      "gemini" to GeminiAiConfig(),
      "azureopenai" to AzureOpenAiConfig()
    )
    .defaultByName("openai")

  private val aiApiLoggingEnabled by option(
    "--ai-api-logging",
    help = "Enable AI API debug logging"
  ).flag(default = false)

  private val os by option(help = "Target operating system")
    .choice("android", "ios", "web")
    .default("android")

  // Common options using extension functions
  private val projectFile by projectFileOption()
  private val logLevel by logLevelOption()
  private val logFile by logFileOption()
  private val workingDirectory by workingDirectoryOption()

  private val path by option(help = "Path to a file")

  private val scenarioIds by option(
    "--scenario-ids",
    help = "Scenario IDs to execute (comma-separated or multiple flags)"
  )
    .split(",")
    .multiple()

  private val tags by option(
    "--tags",
    help = "Tags to filter scenarios. Use comma-separated values which supports OR operation"
  )
    .split(",")
    .multiple()

  private val dryRun by option("--dry-run", help = "Dry run mode")
    .flag()

  private val shard by option("--shard", help = "Shard specification (e.g., 1/5)")
    .convert { input ->
      val regex = """(\d+)/(\d+)""".toRegex()
      val match = regex.matchEntire(input)
        ?: throw CliktError("Invalid shard format. Use `<current>/<total>` (e.g., 1/5)")

      val (currentStr, totalStr) = match.destructured
      val current = currentStr.toIntOrNull()
        ?: throw CliktError("Shard number must be an integer")
      val total = totalStr.toIntOrNull()
        ?: throw CliktError("Total shards must be an integer")

      when {
        total < 1 -> throw CliktError("Total shards must be at least 1")
        current < 1 -> throw CliktError("Shard number must be at least 1")
        current > total -> throw CliktError("Shard number ($current) exceeds total ($total)")
      }

      ArbigentShard(current, total)
    }
    .default(ArbigentShard(1, 1))

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

  @OptIn(ArbigentInternalApi::class)
  override fun run() {
    // Set log level early to avoid unwanted debug logs
    arbigentLogLevel =
      ArbigentLogLevel.entries.find { it.name.toLowerCasePreservingASCIIRules() == logLevel.toLowerCasePreservingASCIIRules() }
        ?: throw IllegalArgumentException(
          "Invalid log level. The log level should be one of ${
            ArbigentLogLevel.values()
              .joinToString(", ") { it.name.toLowerCasePreservingASCIIRules() }
          }")
    
    printLogger = {
      // Disable direct console output to display logs in mosaic instead
    }
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
          apiKey = aiType.openAiApiKey,
          baseUrl = aiType.openAiEndpoint,
          modelName = aiType.openAiModelName,
          loggingEnabled = aiApiLoggingEnabled,
        )

        is GeminiAiConfig -> OpenAIAi(
          apiKey = aiType.geminiApiKey,
          baseUrl = aiType.geminiEndpoint,
          modelName = aiType.geminiModelName,
          loggingEnabled = aiApiLoggingEnabled,
          jsonSchemaType = ArbigentAi.JsonSchemaType.GeminiOpenAICompatible
        )

        is AzureOpenAiConfig -> OpenAIAi(
          apiKey = aiType.azureOpenAIKey,
          baseUrl = aiType.azureOpenAIEndpoint,
          modelName = aiType.azureOpenAIModelName,
          loggingEnabled = aiApiLoggingEnabled,
          requestBuilderModifier = {
            parameter("api-version", aiType.azureOpenAIApiVersion)
            header("api-key", aiType.azureOpenAIKey)
          }
        )
      }
    }

    var device: ArbigentDevice? = null
    val appSettings = CliAppSettings(
      workingDirectory = workingDirectory,
      path = path,
    )
    val arbigentProject = ArbigentProject(
      file = File(projectFile),
      aiFactory = { ai },
      deviceFactory = { device!! },
      appSettings = appSettings
    )
    if (scenarioIds.isNotEmpty() && tags.isNotEmpty()) {
      throw IllegalArgumentException("Cannot specify both scenario IDs and tags. Please create an issue if you need this feature.")
    }
    val nonShardedScenarios = if (scenarioIds.isNotEmpty()) {
      val scenarioIdsSet = scenarioIds.flatten().toSet()
      arbigentProject.scenarios.filter { it.id in scenarioIdsSet }
    } else if (tags.isNotEmpty()) {
      val tagSet = tags.flatten().toSet()
      val candidates = arbigentProject.scenarios
        .filter {
          it.tags.any { scenarioTag -> tagSet.contains(scenarioTag.name) }
        }
      val excludedIds = candidates.flatMap { scenario ->
        scenario.agentTasks.dropLast(1).map { it.scenarioId }
      }.toSet()
      candidates.filterNot { it.id in excludedIds }
    } else {
      val leafScenarios = arbigentProject.leafScenarioAssignments()
      leafScenarios.map { it.scenario }
    }
    val scenarios = nonShardedScenarios.shard(shard)
    arbigentDebugLog("[Scenario Selection] Unsharded candidates: ${nonShardedScenarios.map { it.id }}")
    arbigentDebugLog("[Sharding Configuration] Active shard: $shard")
    arbigentInfoLog("[Execution Plan] Selected scenarios for execution: ${scenarios.map { it.id }}")
    val scenarioIdSet = scenarios.map { it.id }.toSet()

    val os =
      ArbigentDeviceOs.entries.find { it.name.toLowerCasePreservingASCIIRules() == os.toLowerCasePreservingASCIIRules() }
        ?: throw IllegalArgumentException(
          "Invalid OS. The OS should be one of ${
            ArbigentDeviceOs.values()
              .joinToString(", ") { it.name.toLowerCasePreservingASCIIRules() }
          }")
    device = fetchAvailableDevicesByOs(os).firstOrNull()?.connectToDevice()
      ?: throw IllegalArgumentException("No available device found")
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

    runNoRawMosaicBlocking {
      LaunchedEffect(Unit) {
        // Log result locations early for coding agents
        logResultsLocation(resultFile, resultDir)
        
        if (dryRun) {
          arbigentInfoLog("🧪 Dry run mode is enabled. Exiting without executing scenarios.")
          delay(500) // Give time for logs to be displayed
          exitProcess(0)
        }
        
        arbigentProject.executeScenarios(scenarios)
        // Show the result
        delay(100)
        if (arbigentProject.isScenariosSuccessful(scenarios)) {
          val scenarioNames = scenarios.map { it.id }
          arbigentInfoLog("✅ All scenarios completed successfully: $scenarioNames")
          logResultsAvailable(resultFile, resultDir)
          // Give time for logs to be displayed before exit
          delay(100)
          exitProcess(0)
        } else {
          val scenarioNames = scenarios.map { it.id }
          arbigentInfoLog("❌ Some scenarios failed: $scenarioNames")
          logResultsAvailable(resultFile, resultDir)
          // Give time for logs to be displayed before exit
          delay(100)
          exitProcess(1)
        }
      }
      Column {
        // Display logs in mosaic UI (top section - scrollable)
        LogComponent()
        
        // Separator
        Text("─".repeat(80), color = White)
        
        // Status section (bottom section - fixed)
        val assignments by arbigentProject.scenarioAssignmentsFlow.collectAsState(arbigentProject.scenarioAssignments())
        assignments
          .filter { it.scenario.id in scenarioIdSet }
          .shard(shard)
          .forEach { (scenario, scenarioExecutor) ->
            // Show only leaf scenarios
            ScenarioRow(scenario, scenarioExecutor)
          }
      }
    }
  }
}

private fun logResultsLocation(resultFile: File, resultDir: File) {
  arbigentInfoLog("")
  arbigentInfoLog("📁 Results will be saved to:")
  arbigentInfoLog("  • YAML Results: ${resultFile.absolutePath}")
  arbigentInfoLog("  • Screenshots: ${ArbigentFiles.screenshotsDir.absolutePath}/")
  arbigentInfoLog("  • API Logs: ${ArbigentFiles.jsonlsDir.absolutePath}/")
  arbigentInfoLog("  • HTML Report: ${File(resultDir, "report.html").absolutePath}")
}

private fun logResultsAvailable(resultFile: File, resultDir: File) {
  arbigentInfoLog("")
  arbigentInfoLog("📊 Results available:")
  arbigentInfoLog("  • YAML Results: ${resultFile.absolutePath}")
  arbigentInfoLog("  • Screenshots: ${ArbigentFiles.screenshotsDir.absolutePath}/")
  arbigentInfoLog("  • API Logs: ${ArbigentFiles.jsonlsDir.absolutePath}/")
  arbigentInfoLog("  • HTML Report: ${File(resultDir, "report.html").absolutePath}")
}

class ArbigentScenariosCommand : CliktCommand(name = "scenarios") {
  // Same common options as run command
  private val projectFile by projectFileOption()
  private val workingDirectory by workingDirectoryOption()
  
  override fun run() {
    val arbigentProject = ArbigentProject(
      file = File(projectFile),
      aiFactory = { throw UnsupportedOperationException("AI not needed for listing") },
      deviceFactory = { throw UnsupportedOperationException("Device not needed for listing") },
      appSettings = CliAppSettings(
        workingDirectory = workingDirectory,
        path = null,
      )
    )
    
    println("Scenarios in $projectFile:")
    arbigentProject.scenarios.forEach { scenario ->
      println("- ${scenario.id}: ${scenario.agentTasks.lastOrNull()?.goal?.take(80)}...")
    }
  }
}

fun runNoRawMosaicBlocking(block: @Composable () -> Unit) = runBlocking {
  runMosaicBlocking(onNonInteractive = NonInteractivePolicy.AssumeAndIgnore) {
    block()
  }
}

@Composable
fun ScenarioRow(scenario: ArbigentScenario, scenarioExecutor: ArbigentScenarioExecutor) {
  val runningInfo by scenarioExecutor.runningInfoFlow.collectAsState(scenarioExecutor.runningInfo())
  val scenarioState by scenarioExecutor.scenarioStateFlow.collectAsState(scenarioExecutor.scenarioState())
  Row {
    val bg = when (scenarioState) {
      ArbigentScenarioExecutorState.Running -> Yellow
      ArbigentScenarioExecutorState.Success -> Green
      ArbigentScenarioExecutorState.Failed -> Red
      ArbigentScenarioExecutorState.Idle -> White
    }
    Text(
      scenarioState.name(),
      modifier = Modifier
        .background(bg)
        .padding(horizontal = 1),
      color = Black,
    )
    if (runningInfo != null) {
      Text(
        runningInfo.toString().lines()
          .joinToString(" "),
        modifier = Modifier.padding(horizontal = 1).background(Companion.Magenta),
        color = White,
      )
    }
    Text(
      "Goal:" + scenario.agentTasks.lastOrNull()?.goal?.take(80) + "...",
      modifier = Modifier.padding(horizontal = 1),
    )
  }
}

@Composable
fun LogComponent() {
  val logs by ArbigentGlobalStatus.console.collectAsState(emptyList())
  
  // Show all logs - infinite scrolling
  logs.forEach { (instant, message) ->
    val timeText = instant.toString().substring(11, 19) // Extract HH:mm:ss from timestamp
    Text(
      "$timeText $message",
      color = White,
      modifier = Modifier.padding(horizontal = 1)
    )
  }
}

fun main(args: Array<String>) {
  LoggingUtils.suppressSlf4jWarnings()
  
  ArbigentCli()
    .subcommands(ArbigentRunCommand(), ArbigentScenariosCommand())
    .main(args)
}
