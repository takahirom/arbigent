package io.github.takahirom.arbigent.cli

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.defaultByName
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaic
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

sealed class AiConfig(name: String) : OptionGroup(name)

class OpenAIAiConfig : AiConfig("Options for OpenAI API AI") {
  private val defaultEndpoint = "https://api.openai.com/v1/"
  val openAiEndpoint by option(help = "Endpoint URL (default: $defaultEndpoint)")
    .default(defaultEndpoint, defaultForHelp = defaultEndpoint)
  val openAiModelName by option(help = "Model name (default: gpt-4o-mini)")
    .default("gpt-4o-mini", "gpt-4o-mini")
  val openAiApiKey by option(envvar = "OPENAI_API_KEY", help = "API key")
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
  val azureOpenAIEndpoint by option(help = "Endpoint URL")
    .prompt("Endpoint URL")
  val azureOpenAIApiVersion by option(help = "API version")
    .default("2024-10-21")
  val azureOpenAIModelName by option(help = "Model name (default: gpt-4o-mini)")
    .default("gpt-4o-mini")
  val azureOpenAIKey by option(envvar = "AZURE_OPENAI_API_KEY", help = "API key")
    .prompt("API key")
}

private const val defaultResultPath = "arbigent-result"

class ArbigentCli : CliktCommand(name = "arbigent") {
  private val aiType by option(help = "Type of AI to use")
    .groupChoice(
      "openai" to OpenAIAiConfig(),
      "gemini" to GeminiAiConfig(),
      "azureopenai" to AzureOpenAiConfig()
    )
    .defaultByName("openai")

  private val os by option(help = "Target operating system")
    .choice("android", "ios", "web")
    .default("android")

  private val projectFile by option(help = "Path to the project YAML file")
    .prompt("Project file path")

  private val logLevel by option(help = "Log level")
    .choice("debug", "info", "warn", "error")
    .default("info")

  private val logFile by option(help = "Log file path")
    .default("$defaultResultPath/arbigent.log")

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

  @OptIn(ArbigentInternalApi::class)
  override fun run() {
    printLogger = {
      echo(it)
    }
    val resultDir = File(defaultResultPath)
    resultDir.mkdirs()
    ArbigentFiles.screenshotsDir = File(resultDir, "screenshots")
    ArbigentFiles.jsonlsDir = File(resultDir, "jsonls")
    ArbigentFiles.logFile = File(logFile)
    val resultFile = File(resultDir, "result.yml")
    val ai: ArbigentAi = aiType.let { aiType ->
      when (aiType) {
        is OpenAIAiConfig -> OpenAIAi(
          apiKey = aiType.openAiApiKey,
          baseUrl = aiType.openAiEndpoint,
          modelName = aiType.openAiModelName,
        )

        is GeminiAiConfig -> OpenAIAi(
          apiKey = aiType.geminiApiKey,
          baseUrl = aiType.geminiEndpoint,
          modelName = aiType.geminiModelName,
        )

        is AzureOpenAiConfig -> OpenAIAi(
          apiKey = aiType.azureOpenAIKey,
          baseUrl = aiType.azureOpenAIEndpoint,
          modelName = aiType.azureOpenAIModelName,
          requestBuilderModifier = {
            parameter("api-version", aiType.azureOpenAIApiVersion)
            header("api-key", System.getenv("AZURE_OPENAI_API_KEY").orEmpty())
          }
        )
      }
    }

    var device: ArbigentDevice? = null
    val arbigentProject = ArbigentProject(
      file = File(projectFile),
      aiFactory = { ai },
      deviceFactory = { device!! },
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
    if (dryRun) {
      arbigentInfoLog("Dry run mode is enabled. Exiting without executing scenarios.")
      return
    }

    val os =
      ArbigentDeviceOs.entries.find { it.name.toLowerCasePreservingASCIIRules() == os.toLowerCasePreservingASCIIRules() }
        ?: throw IllegalArgumentException(
          "Invalid OS. The OS should be one of ${
            ArbigentDeviceOs.values()
              .joinToString(", ") { it.name.toLowerCasePreservingASCIIRules() }
          }")
    device = fetchAvailableDevicesByOs(os).firstOrNull()?.connectToDevice()
      ?: throw IllegalArgumentException("No available device found")
    arbigentLogLevel =
      ArbigentLogLevel.entries.find { it.name.toLowerCasePreservingASCIIRules() == logLevel.toLowerCasePreservingASCIIRules() }
        ?: throw IllegalArgumentException(
          "Invalid log level. The log level should be one of ${
            ArbigentLogLevel.values()
              .joinToString(", ") { it.name.toLowerCasePreservingASCIIRules() }
          }")
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
        arbigentProject.executeScenarios(scenarios)
        // Show the result
        delay(100)
        if (arbigentProject.isScenariosSuccessful(scenarios)) {
          arbigentInfoLog("All scenarios are succeeded. Executed scenarios:${scenarios.map { it.id }}")
          exitProcess(0)
        } else {
          arbigentInfoLog("Some scenarios are failed. Executed scenarios:${scenarios.map { it.id }}")
          exitProcess(1)
        }
      }
      Column {
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

fun runNoRawMosaicBlocking(block: @Composable () -> Unit) = runBlocking {
  runMosaic(enterRawMode = false) {
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

fun main(args: Array<String>) = ArbigentCli().main(args)
