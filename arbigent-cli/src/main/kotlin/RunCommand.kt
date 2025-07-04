@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.groups.defaultByName
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.layout.wrapContentWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.NonInteractivePolicy
import com.jakewharton.mosaic.runMosaicBlocking
import com.jakewharton.mosaic.LocalTerminalState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

@ArbigentInternalApi
class ArbigentRunCommand : CliktCommand(name = "run") {
  
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

  // Common options using extension functions with automatic property file detection
  private val projectFile by projectFileOption()
  private val logLevel by logLevelOption()
  private val logFile by logFileOption()
  private val workingDirectory by workingDirectoryOption()

  private val path by defaultOption("--path",help = "Path to a file")

  private val scenarioIds by defaultOption(
    "--scenario-ids",
    help = "Scenario IDs to execute (comma-separated or multiple flags)"
  )
    .split(",")
    .multiple()

  private val tags by defaultOption(
    "--tags",
    help = "Tags to filter scenarios. Use comma-separated values which supports OR operation"
  )
    .split(",")
    .multiple()

  private val dryRun by defaultOption("--dry-run", help = "Dry run mode")
    .flag()

  private val shard by defaultOption("--shard", help = "Shard specification (e.g., 1/5)")
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

  override fun run() {
    // Check that project-file is provided either via CLI args or settings file
    if (projectFile == null) {
      throw CliktError("Missing option '--project-file'. Please provide a project file path via command line argument or in .arbigent/settings.local.yml")
    }
    
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
    
    // Display loaded configuration values for debugging/testing
    arbigentDebugLog("=== Configuration Priority Demonstration ===")
    arbigentDebugLog("Command: run")
    arbigentDebugLog("Loaded configuration values:")
    arbigentDebugLog("  ai-type: ${when(aiType) {
      is OpenAIAiConfig -> "openai"
      is GeminiAiConfig -> "gemini"  
      is AzureOpenAiConfig -> "azureopenai"
      else -> "unknown"
    }} (Expected: run-specific-azure from run.ai-type)")
    arbigentDebugLog("  log-level: $logLevel (Expected: debug from run.log-level)")
    arbigentDebugLog("==========================================")
    
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
          apiKey = aiType.openAiApiKey!!, // Already validated above
          baseUrl = aiType.openAiEndpoint,
          modelName = aiType.openAiModelName,
          loggingEnabled = aiApiLoggingEnabled,
        )

        is GeminiAiConfig -> OpenAIAi(
          apiKey = aiType.geminiApiKey!!, // Already validated above
          baseUrl = aiType.geminiEndpoint,
          modelName = aiType.geminiModelName,
          loggingEnabled = aiApiLoggingEnabled,
          jsonSchemaType = ArbigentAi.JsonSchemaType.GeminiOpenAICompatible
        )

        is AzureOpenAiConfig -> OpenAIAi(
          apiKey = aiType.azureOpenAIKey!!, // Already validated above
          baseUrl = aiType.azureOpenAIEndpoint!!, // Already validated above
          modelName = aiType.azureOpenAIModelName,
          loggingEnabled = aiApiLoggingEnabled,
          requestBuilderModifier = {
            parameter("api-version", aiType.azureOpenAIApiVersion)
            header("api-key", aiType.azureOpenAIKey!!)
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
      deviceFactory = { device ?: throw UnsupportedOperationException("Device not available in dry-run mode") },
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

    // Skip device connection in dry-run mode
    if (!dryRun) {
      val os =
        ArbigentDeviceOs.entries.find { it.name.toLowerCasePreservingASCIIRules() == os.toLowerCasePreservingASCIIRules() }
          ?: throw IllegalArgumentException(
            "Invalid OS. The OS should be one of ${
              ArbigentDeviceOs.entries
                .joinToString(", ") { it.name.toLowerCasePreservingASCIIRules() }
            }")
      device = fetchAvailableDevicesByOs(os).firstOrNull()?.connectToDevice()
        ?: throw IllegalArgumentException("No available device found")
    }
    Runtime.getRuntime().addShutdownHook(object : Thread() {
      override fun run() {
        arbigentProject.cancel()
        ArbigentProjectSerializer().save(arbigentProject.getResult(scenarios), resultFile)
        ArbigentHtmlReport().saveReportHtml(
          resultDir.absolutePath,
          arbigentProject.getResult(scenarios),
          needCopy = false
        )
        device?.close()
      }
    })

    val isTerminal = System.console() != null
    if (isTerminal) {
      runInteractiveMode(arbigentProject, scenarios, scenarioIdSet, shard, resultFile, resultDir, dryRun)
    } else {
      runNonInteractiveMode(arbigentProject, scenarios, resultFile, resultDir, dryRun)
    }
  }

  private fun runInteractiveMode(
    arbigentProject: ArbigentProject,
    scenarios: List<ArbigentScenario>,
    scenarioIdSet: Set<String>,
    shard: ArbigentShard,
    resultFile: File,
    resultDir: File,
    dryRun: Boolean
  ) {
    // Disable console output for Mosaic UI - logs will be displayed in mosaic instead
    printLogger = {}

    runNoRawMosaicBlocking {
      LaunchedEffect(Unit) {
        logResultsLocation(resultFile, resultDir)
        
        if (dryRun) {
          arbigentInfoLog("🧪 Dry run mode is enabled. Exiting without executing scenarios.")
          delay(500)
          exitProcess(0)
        }
        
        arbigentProject.executeScenarios(scenarios)
        delay(100)
        
        if (arbigentProject.isScenariosSuccessful(scenarios)) {
          val scenarioNames = scenarios.map { it.id }
          arbigentInfoLog("🟢 All scenarios completed successfully: $scenarioNames")
          logResultsAvailable(resultFile, resultDir)
          delay(100)
          exitProcess(0)
        } else {
          val scenarioNames = scenarios.map { it.id }
          arbigentInfoLog("🔴 Some scenarios failed: $scenarioNames")
          logResultsAvailable(resultFile, resultDir)
          delay(100)
          exitProcess(1)
        }
      }
      
      Column {
        LogComponent()
        Text("─".repeat(80), color = White)
        
        val assignments by arbigentProject.scenarioAssignmentsFlow.collectAsState(arbigentProject.scenarioAssignments())
        
        // Find currently running scenario index
        val runningScenarioIndex = scenarios.indexOfFirst { scenario ->
          val assignment = assignments.find { it.scenario.id == scenario.id }
          assignment?.scenarioExecutor?.scenarioState() == ArbigentScenarioExecutorState.Running
        }
        
        // Display scenarios around the running one (±2)
        val displayScenarios = if (runningScenarioIndex >= 0) {
          val start = maxOf(0, runningScenarioIndex - 2)
          val end = minOf(scenarios.size, runningScenarioIndex + 3) // +3 because end is exclusive
          scenarios.subList(start, end)
        } else {
          // If no scenario is running, show first 5 scenarios
          scenarios.take(5)
        }
        
        displayScenarios.forEach { scenario ->
          ScenarioWithDependenciesRow(arbigentProject, scenario, assignments)
        }
      }
    }
  }

  private fun runNonInteractiveMode(
    arbigentProject: ArbigentProject,
    scenarios: List<ArbigentScenario>,
    resultFile: File,
    resultDir: File,
    dryRun: Boolean
  ) {
    // Keep default printLogger for console output
    runBlocking {
      logResultsLocation(resultFile, resultDir)
      
      if (dryRun) {
        arbigentInfoLog("🧪 Dry run mode is enabled. Exiting without executing scenarios.")
        delay(500)
        exitProcess(0)
      }
      
      // Start reactive progress monitoring
      val progressJob = launch {
        // Monitor assignment changes
        arbigentProject.scenarioAssignmentsFlow.collect { assignments ->
          logScenarioProgress(arbigentProject, scenarios)
          
          // Monitor state changes for each assignment
          assignments.forEach { assignment ->
            launch {
              assignment.scenarioExecutor.scenarioStateFlow.collect {
                logScenarioProgress(arbigentProject, scenarios)
              }
            }
            launch {
              assignment.scenarioExecutor.runningInfoFlow.collect {
                logScenarioProgress(arbigentProject, scenarios)
              }
            }
          }
        }
      }
      
      arbigentProject.executeScenarios(scenarios)
      progressJob.cancel()
      
      // Final status log
      logFinalScenarioStatus(arbigentProject, scenarios)
      
      if (arbigentProject.isScenariosSuccessful(scenarios)) {
        val scenarioNames = scenarios.map { it.id }
        arbigentInfoLog("🟢 All scenarios completed successfully: $scenarioNames")
        logResultsAvailable(resultFile, resultDir)
        exitProcess(0)
      } else {
        val scenarioNames = scenarios.map { it.id }
        arbigentInfoLog("🔴 Some scenarios failed: $scenarioNames")
        logResultsAvailable(resultFile, resultDir)
        exitProcess(1)
      }
    }
  }
  
  private fun logScenarioWithDependencies(arbigentProject: ArbigentProject, scenario: ArbigentScenario, indent: String = ""): Boolean {
    val assignment = arbigentProject.scenarioAssignments().find { it.scenario.id == scenario.id }
    var hasActiveScenarios = false
    
    if (assignment != null) {
      val state = assignment.scenarioExecutor.scenarioState()
      val runningInfo = assignment.scenarioExecutor.runningInfo()
      
      when (state) {
        ArbigentScenarioExecutorState.Running -> {
          hasActiveScenarios = true
          if (runningInfo != null) {
            arbigentInfoLog("$indent🔄 Running: ${scenario.id} - ${runningInfo.toString().lines().joinToString(" ")}")
          } else {
            arbigentInfoLog("$indent🔄 Running: ${scenario.id}")
          }
        }
        ArbigentScenarioExecutorState.Success -> {
          // Show completed status for context
          arbigentInfoLog("$indent🟢 ${scenario.id}: Completed")
        }
        ArbigentScenarioExecutorState.Failed -> {
          // Show failed status for context
          arbigentInfoLog("$indent🔴 ${scenario.id}: Failed")
        }
        else -> {
          // Show pending status for context
          arbigentInfoLog("$indent⏸️ ${scenario.id}: Pending")
        }
      }
    }
    
    // Show dependencies if this scenario has multiple agent tasks
    if (scenario.agentTasks.size > 1) {
      scenario.agentTasks.dropLast(1).forEach { task ->
        val depScenario = arbigentProject.scenarios.find { it.id == task.scenarioId }
        if (depScenario != null) {
          if (logScenarioWithDependencies(arbigentProject, depScenario, "$indent └ ")) {
            hasActiveScenarios = true
          }
        }
      }
    }
    
    return hasActiveScenarios
  }

  private var lastLoggedStates = mutableMapOf<String, String>()
  
  private fun logScenarioProgress(arbigentProject: ArbigentProject, scenarios: List<ArbigentScenario>) {
    // Use assignments to find running scenarios (not limited to scenarios list)
    val assignments = arbigentProject.scenarioAssignments()
    
    
    // Find running assignment using isRunning() method (more reliable than StateFlow with WhileSubscribed)
    val runningAssignment = assignments.find { assignment ->
      assignment.scenarioExecutor.isRunning()
    }
    
    if (runningAssignment != null) {
      // Show only the running scenario and its dependencies
      logScenarioWithDependenciesWithStateChange(arbigentProject, runningAssignment.scenario)
    } else {
      // Check if execution scenarios are completed
      val targetScenarios = scenarios
      val allCompleted = targetScenarios.all { scenario ->
        val assignment = assignments.find { it.scenario.id == scenario.id }
        assignment?.scenarioExecutor?.scenarioState() in listOf(
          ArbigentScenarioExecutorState.Success,
          ArbigentScenarioExecutorState.Failed
        )
      }
      
      
      if (allCompleted) {
        val stateKey = "all_completed"
        if (lastLoggedStates[stateKey] != "completed") {
          arbigentInfoLog("📊 All scenarios completed. Finalizing results...")
          lastLoggedStates[stateKey] = "completed"
        }
      }
    }
  }
  
  private fun logScenarioWithDependenciesWithStateChange(arbigentProject: ArbigentProject, scenario: ArbigentScenario) {
    val assignment = arbigentProject.scenarioAssignments().find { it.scenario.id == scenario.id }
    
    if (assignment != null) {
      val state = assignment.scenarioExecutor.scenarioState()
      val runningInfo = assignment.scenarioExecutor.runningInfo()
      val stateString = "${state.name()}-${runningInfo?.toString() ?: "null"}"
      
      // Only log if state has changed
      if (lastLoggedStates[scenario.id] != stateString) {
        when (state) {
          ArbigentScenarioExecutorState.Running -> {
            if (runningInfo != null) {
              arbigentInfoLog("🔄 Running: ${scenario.id} - ${runningInfo.toString().lines().firstOrNull() ?: ""}")
            } else {
              arbigentInfoLog("🔄 Running: ${scenario.id}")
            }
          }
          ArbigentScenarioExecutorState.Success -> {
            arbigentInfoLog("🟢 ${scenario.id}: Completed")
          }
          ArbigentScenarioExecutorState.Failed -> {
            arbigentInfoLog("🔴 ${scenario.id}: Failed")
          }
          else -> {
            arbigentInfoLog("⏸️ ${scenario.id}: Pending")
          }
        }
        lastLoggedStates[scenario.id] = stateString
      }
    }
    
    // Show dependencies only if parent scenario is running and has multiple agent tasks
    if (scenario.agentTasks.size > 1 && assignment != null) {
      val parentState = assignment.scenarioExecutor.scenarioState()
      if (parentState == ArbigentScenarioExecutorState.Running) {
        val runningInfo = assignment.scenarioExecutor.runningInfo()
        
        scenario.agentTasks.dropLast(1).forEachIndexed { index, task ->
          val depScenario = arbigentProject.scenarios.find { it.id == task.scenarioId }
          if (depScenario != null) {
            // Determine task status based on running info (same logic as UI)
            val currentRunningInfo = runningInfo
            val (icon, status) = if (currentRunningInfo != null) {
              val currentTaskIndex = currentRunningInfo.runningTasks - 1 // Convert to 0-based
              when {
                index < currentTaskIndex -> "🟢" to "Completed"
                index == currentTaskIndex -> "🔄" to "Running"
                else -> "⏸️" to "Pending"
              }
            } else {
              "⏸️" to "Pending"
            }
            
            val depStateString = "$status-${currentRunningInfo?.toString() ?: "null"}"
            val depKey = "${scenario.id}-dep-${task.scenarioId}"
            
            // Only log dependency if its state has changed
            if (lastLoggedStates[depKey] != depStateString) {
              arbigentInfoLog(" └ $icon ${depScenario.id}: $status")
              lastLoggedStates[depKey] = depStateString
            }
          }
        }
      }
    }
  }
  

  private fun getScenarioIcon(arbigentProject: ArbigentProject, scenario: ArbigentScenario): String {
    val assignment = arbigentProject.scenarioAssignments().find { it.scenario.id == scenario.id }
    return if (assignment != null) {
      when (assignment.scenarioExecutor.scenarioState()) {
        ArbigentScenarioExecutorState.Success -> "🟢"
        ArbigentScenarioExecutorState.Failed -> "🔴"
        ArbigentScenarioExecutorState.Running -> "🔄"
        else -> "⏸️"
      }
    } else "⏸️"
  }

  private fun logScenarioWithDependenciesStatus(arbigentProject: ArbigentProject, scenario: ArbigentScenario, indent: String = "") {
    val assignment = arbigentProject.scenarioAssignments().find { it.scenario.id == scenario.id }
    
    if (assignment != null) {
      val icon = getScenarioIcon(arbigentProject, scenario)
      val state = assignment.scenarioExecutor.scenarioState().name()
      arbigentInfoLog("$indent$icon ${scenario.id}: $state")
    } else {
      // Skip scenarios without assignments (dependency scenarios not selected for execution)
      arbigentInfoLog("$indent⭕ ${scenario.id}: Dependency")
    }
    
    // Show dependencies if this scenario has multiple agent tasks
    if (scenario.agentTasks.size > 1) {
      scenario.agentTasks.dropLast(1).forEach { task ->
        val depScenario = arbigentProject.scenarios.find { it.id == task.scenarioId }
        if (depScenario != null) {
          logScenarioWithDependenciesStatus(arbigentProject, depScenario, "$indent └ ")
        }
      }
    }
  }

  private fun logFinalScenarioStatus(arbigentProject: ArbigentProject, scenarios: List<ArbigentScenario>) {
    arbigentInfoLog("")
    arbigentInfoLog("📋 Final Results:")
    
    // Show only the main scenarios without dependencies to avoid confusion
    scenarios.forEach { scenario ->
      val assignment = arbigentProject.scenarioAssignments().find { it.scenario.id == scenario.id }
      if (assignment != null) {
        val icon = getScenarioIcon(arbigentProject, scenario)
        val state = assignment.scenarioExecutor.scenarioState().name()
        arbigentInfoLog("$icon ${scenario.id}: $state")
      }
    }
    
    arbigentInfoLog("")
  }
}

fun logResultsLocation(resultFile: File, resultDir: File) {
  arbigentInfoLog("")
  arbigentInfoLog("📁 Results will be saved to:")
  arbigentInfoLog("  • YAML Results: ${resultFile.absolutePath}")
  arbigentInfoLog("  • Screenshots: ${ArbigentFiles.screenshotsDir.absolutePath}/")
  arbigentInfoLog("  • API Logs: ${ArbigentFiles.jsonlsDir.absolutePath}/")
  arbigentInfoLog("  • HTML Report: ${File(resultDir, "report.html").absolutePath}")
}

fun logResultsAvailable(resultFile: File, resultDir: File) {
  arbigentInfoLog("")
  arbigentInfoLog("📊 Results available:")
  arbigentInfoLog("  • YAML Results: ${resultFile.absolutePath}")
  arbigentInfoLog("  • Screenshots: ${ArbigentFiles.screenshotsDir.absolutePath}/")
  arbigentInfoLog("  • API Logs: ${ArbigentFiles.jsonlsDir.absolutePath}/")
  arbigentInfoLog("  • HTML Report: ${File(resultDir, "report.html").absolutePath}")
}

@Composable
fun ScenarioRow(scenario: ArbigentScenario, scenarioExecutor: ArbigentScenarioExecutor) {
  val runningInfo by scenarioExecutor.runningInfoFlow.collectAsState(scenarioExecutor.runningInfo())
  val scenarioState by scenarioExecutor.scenarioStateFlow.collectAsState(scenarioExecutor.scenarioState())
  Row {
    val (icon, bg) = when (scenarioState) {
      ArbigentScenarioExecutorState.Running -> "🔄" to Yellow
      ArbigentScenarioExecutorState.Success -> "🟢" to Green
      ArbigentScenarioExecutorState.Failed -> "🔴" to Red
      ArbigentScenarioExecutorState.Idle -> "⏸️" to White
    }
    Text(
      icon,
      modifier = Modifier
        .background(bg)
        .padding(horizontal = 1)
        .wrapContentWidth(),
      color = Black,
    )
    if (runningInfo != null) {
      Text(
        runningInfo.toString().lines().firstOrNull() ?: "",
        modifier = Modifier
          .padding(horizontal = 1)
          .background(Companion.Magenta),
        color = White,
      )
    }
    Text(
      "${scenario.id}: ${scenario.agentTasks.lastOrNull()?.goal?.lines()?.firstOrNull() ?: ""}",
      modifier = Modifier.padding(horizontal = 1),
    )
  }
}

@Composable
fun ScenarioWithDependenciesRow(
  arbigentProject: ArbigentProject, 
  scenario: ArbigentScenario, 
  assignments: List<ArbigentScenarioAssignment>
) {
  // Find assignment for this scenario
  val assignment = assignments.find { it.scenario.id == scenario.id }
  
  // Display main scenario
  if (assignment != null) {
    ScenarioRow(scenario, assignment.scenarioExecutor)
  } else {
    // Scenario not selected for execution
    Row {
      Text(
        "⭕",
        modifier = Modifier
          .background(White)
          .padding(horizontal = 1)
          .wrapContentWidth(),
        color = Black,
      )
      Text(
        "${scenario.id}: ${scenario.agentTasks.lastOrNull()?.goal?.lines()?.firstOrNull() ?: ""}",
        modifier = Modifier.padding(horizontal = 1),
      )
    }
  }
  
  // Display dependencies only if parent scenario is running and has multiple agent tasks
  if (scenario.agentTasks.size > 1 && assignment != null) {
    val parentState by assignment.scenarioExecutor.scenarioStateFlow.collectAsState(assignment.scenarioExecutor.scenarioState())
    if (parentState == ArbigentScenarioExecutorState.Running) {
      val runningInfo by assignment.scenarioExecutor.runningInfoFlow.collectAsState(assignment.scenarioExecutor.runningInfo())
      
      scenario.agentTasks.dropLast(1).forEachIndexed { index, task ->
        val depScenario = arbigentProject.scenarios.find { it.id == task.scenarioId }
        if (depScenario != null) {
          Row {
            Text(
              "  └ ",
              modifier = Modifier
                .padding(horizontal = 1)
                .wrapContentWidth(),
              color = White
            )
            
            // Determine task status based on running info
            val currentRunningInfo = runningInfo
            val (icon, bg) = if (currentRunningInfo != null) {
              val currentTaskIndex = currentRunningInfo.runningTasks - 1 // Convert to 0-based
              when {
                index < currentTaskIndex -> "🟢" to Green // Completed
                index == currentTaskIndex -> "🔄" to Yellow // Currently running
                else -> "⏸️" to White // Pending
              }
            } else {
              "⏸️" to White // No running info available
            }
            
            Text(
              icon,
              modifier = Modifier
                .background(bg)
                .padding(horizontal = 1)
                .wrapContentWidth(),
              color = Black,
            )
            Text(
              "${depScenario.id}: ${depScenario.agentTasks.lastOrNull()?.goal?.lines()?.firstOrNull() ?: ""}",
              modifier = Modifier.padding(horizontal = 1),
              color = White
            )
          }
        }
      }
    }
  }
}

@Composable
fun LogComponent() {
  val logs by ArbigentGlobalStatus.console.collectAsState(emptyList())
  val terminal = LocalTerminalState.current
  
  // Calculate available lines for logs (terminal height - status lines - separator)
  val availableLines = maxOf(10, terminal.size.height - 5) // Reserve space for status and separator, minimum 10 lines
  
  // Show logs that fit in terminal and prevent full rerender
  logs.takeLast(availableLines).forEach { (instant, message) ->
    val timeText = instant.toString().substring(11, 19) // Extract HH:mm:ss from timestamp
    Text(
      "$timeText $message",
      color = White,
      modifier = Modifier.padding(horizontal = 1)
    )
  }
}

fun runNoRawMosaicBlocking(block: @Composable () -> Unit) = runBlocking {
  runMosaicBlocking(onNonInteractive = NonInteractivePolicy.AssumeAndIgnore) {
    block()
  }
}