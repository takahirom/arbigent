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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

@ArbigentInternalApi
class ArbigentRunCommand : CliktCommand(name = "run") {
  override val invokeWithoutSubcommand = true
  
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

  private val iosAppleTeamId by defaultOption(
    "--ios-xctest-apple-team-id",
    help = "Apple developer team id used to sign the XCTest runner for a physical iPhone (--os=ios). " +
      "Falls back to ${ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID}, then single-identity auto-detect."
  )
  private val iosRealDeviceId by defaultOption(
    "--ios-real-device-id",
    help = "Hardware UDID selecting a specific physical iPhone (--os=ios)."
  )
  private val iosRealDevicePort by defaultOption(
    "--ios-real-device-port",
    help = "Host/device port for the XCTest runner on a physical iPhone (default 22087)."
  )

  // Common options using extension functions with automatic property file detection
  private val projectFile by projectFileOption()
  private val logLevel by logLevelOption()
  private val logFile by logFileOption()
  private val workingDirectory by workingDirectoryOption()

  private val path by defaultOption("--path",help = "Path to a file")

  private val variables by defaultOption(
    "--variables",
    help = """Variables to replace in goals. Format: key1=value1,key2=value2
             |Quote values with spaces: key="value with spaces"
             |Example: name=John,message="Hello World",url="https://example.com"""".trimMargin()
  )
    .convert { input -> parseVariables(input) }

  private val scenarioIds by defaultOption(
    "--scenario-ids",
    help = "Scenario IDs to execute with their dependencies (comma-separated or multiple flags)"
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

  override fun run() {
    // If a subcommand (like "task") was invoked, let it handle execution
    if (currentContext.invokedSubcommand != null) return

    // Check that project-file is provided either via CLI args or settings file
    val projectFilePath = requireProjectFile(projectFile)
    if (tags.isNotEmpty() && isJourneyProjectSource(projectFilePath)) {
      throw CliktError("--tags is not supported for Journeys XML projects because journey scenarios have no tags")
    }

    validateAiConfig(aiType)
    applyLogLevel(logLevel)
    
    arbigentDebugLog("=== Configuration Priority Demonstration ===")
    arbigentDebugLog("Command: run")
    arbigentDebugLog("Loaded configuration values:")
    arbigentDebugLog("  ai-type: ${when(aiType) {
      is OpenAIAiConfig -> "openai"
      is GeminiAiConfig -> "gemini"
      is AzureOpenAiConfig -> "azureopenai"
      else -> "unknown"
    }}")
    arbigentDebugLog("  log-level: $logLevel")
    arbigentDebugLog("==========================================")
    
    val (resultDir, resultFile) = setupArbigentFiles(workingDirectory, logFile)
    val ai = createAi(aiType, aiApiLoggingEnabled)

    var device: ArbigentDevice? = null
    val appSettings = CliAppSettings(
      workingDirectory = workingDirectory,
      path = path,
      variables = variables
    )
    val arbigentProject = loadArbigentProject(
      projectFile = projectFilePath,
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

    if (dryRun) {
      echo("[Execution Plan] Selected scenarios for execution: ${scenarios.map { it.id }}")
      echo("Dry run mode is enabled. Exiting without executing scenarios.")
      return
    }

    device = connectDevice(
      os = os,
      iosAppleTeamId = iosAppleTeamId,
      iosRealDeviceId = iosRealDeviceId,
      iosRealDevicePort = parseIosRealDevicePort(iosRealDevicePort),
    )
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
      runInteractiveMode(arbigentProject, scenarios, scenarioIdSet, shard, resultFile, resultDir)
    } else {
      runNonInteractiveMode(arbigentProject, scenarios, resultFile, resultDir)
    }
  }

  private fun runInteractiveMode(
    arbigentProject: ArbigentProject,
    scenarios: List<ArbigentScenario>,
    scenarioIdSet: Set<String>,
    shard: ArbigentShard,
    resultFile: File,
    resultDir: File
  ) {
    // Route logs to ArbigentGlobalStatus for Mosaic UI LogComponent display
    printLogger = { log -> ArbigentGlobalStatus.log(log) }

    runNoRawMosaicBlocking {
      LaunchedEffect(Unit) {
        logResultsLocation(resultFile, resultDir)

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
    resultDir: File
  ) {
    // Keep default printLogger for console output
    runBlocking {
      logResultsLocation(resultFile, resultDir)

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
      scenario.agentTasks.forEach { task ->
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
      
      // Check if parent or any dependencies have state changes
      var hasAnyChange = false
      val stateString = state.name()
      
      // Check parent state change
      if (lastLoggedStates[scenario.id] != stateString) {
        hasAnyChange = true
      }
      
      // Check dependency state changes if scenario has multiple tasks
      if (scenario.agentTasks.size > 1) {
        scenario.agentTasks.forEachIndexed { index, task ->
          val currentRunningInfo = runningInfo
          val status = if (currentRunningInfo != null) {
            val currentTaskIndex = currentRunningInfo.runningTasks - 1
            when {
              index < currentTaskIndex -> "Completed"
              index == currentTaskIndex -> "Running"
              else -> "Pending"
            }
          } else {
            "Pending"
          }
          
          val depKey = "${scenario.id}-dep-${task.scenarioId}"
          if (lastLoggedStates[depKey] != status) {
            hasAnyChange = true
          }
        }
      }
      
      // If any state has changed, log parent and all dependencies together
      if (hasAnyChange) {
        // Log parent scenario
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
        
        // Log all dependencies
        if (scenario.agentTasks.size > 1) {
          scenario.agentTasks.forEachIndexed { index, task ->
            val depScenario = arbigentProject.scenarios.find { it.id == task.scenarioId }
            if (depScenario != null) {
              val currentRunningInfo = runningInfo
              val (icon, status) = if (currentRunningInfo != null) {
                val currentTaskIndex = currentRunningInfo.runningTasks - 1
                when {
                  index < currentTaskIndex -> "🟢" to "Completed"
                  index == currentTaskIndex -> "🔄" to "Running"
                  else -> "⏸️" to "Pending"
                }
              } else {
                "⏸️" to "Pending"
              }
              
              arbigentInfoLog(" └ $icon ${depScenario.id}: $status")
              
              val depKey = "${scenario.id}-dep-${task.scenarioId}"
              lastLoggedStates[depKey] = status
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
      scenario.agentTasks.forEach { task ->
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
      
      scenario.agentTasks.forEachIndexed { index, task ->
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