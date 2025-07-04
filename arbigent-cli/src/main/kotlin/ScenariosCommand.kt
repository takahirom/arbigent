@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.github.takahirom.arbigent.*
import java.io.File

class ArbigentScenariosCommand : CliktCommand(name = "scenarios") {
  // Same common options as run command
  private val projectFile by projectFileOption()
  private val workingDirectory by workingDirectoryOption()
  private val logLevel by logLevelOption()
  
  override fun run() {
    // Set log level
    arbigentLogLevel =
      ArbigentLogLevel.entries.find { it.name.lowercase() == logLevel.lowercase() }
        ?: throw IllegalArgumentException("Invalid log level: $logLevel")
    
    // Display loaded configuration values for debugging/testing
    arbigentDebugLog("=== Configuration Priority Demonstration ===")
    arbigentDebugLog("Command: scenarios")
    arbigentDebugLog("Loaded configuration values:")
    arbigentDebugLog("  log-level: $logLevel (Expected: info from scenarios.log-level)")
    arbigentDebugLog("  ai-type: Not applicable for scenarios command")
    arbigentDebugLog("  Note: ai-type from global would be 'global-openai' if this command used it")
    arbigentDebugLog("==========================================")
    val arbigentProject = ArbigentProject(
      file = File(projectFile),
      aiFactory = { throw UnsupportedOperationException("AI not needed for listing") },
      deviceFactory = { throw UnsupportedOperationException("Device not needed for listing") },
      appSettings = CliAppSettings(
        workingDirectory = workingDirectory,
        path = null,
      )
    )
    
    arbigentInfoLog("Scenarios in $projectFile:")
    arbigentProject.scenarios.forEach { scenario ->
      arbigentInfoLog("- ${scenario.id}: ${scenario.agentTasks.lastOrNull()?.goal?.take(80)}...")
    }
  }
}