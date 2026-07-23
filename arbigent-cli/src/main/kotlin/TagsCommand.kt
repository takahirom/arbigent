@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import kotlinx.coroutines.Dispatchers

import com.github.ajalt.clikt.core.CliktCommand
import io.github.takahirom.arbigent.*

class ArbigentTagsCommand : CliktCommand(name = "tags") {
  // Same common options as run command
  private val projectFile by projectFileOption()
  private val workingDirectory by workingDirectoryOption()
  private val logLevel by logLevelOption()
  
  override fun run() {
    // Set log level
    arbigentLogLevel =
      ArbigentLogLevel.entries.find { it.name.lowercase() == logLevel.lowercase() }
        ?: throw IllegalArgumentException("Invalid log level: $logLevel")
    
    val arbigentProject = loadArbigentProject(
      projectFile = requireProjectFile(projectFile),
      aiFactory = { throw UnsupportedOperationException("AI not needed for listing") },
      deviceFactory = { throw UnsupportedOperationException("Device not needed for listing") },
      appSettings = CliAppSettings(
        workingDirectory = workingDirectory,
        path = null,
      ),
      // Read-only command: the project builds executors on construction, so a dispatcher is required
      // even though no scenario is run here.
      dispatcher = Dispatchers.Default,
    )

    val allTags = arbigentProject.scenarios
      .flatMap { it.tags }
      .map { it.name }
      .distinct()
      .sorted()
    
    if (allTags.isEmpty()) {
      arbigentInfoLog("No tags found in $projectFile")
    } else {
      arbigentInfoLog("Tags in $projectFile:")
      allTags.forEach { tag ->
        arbigentInfoLog("- $tag")
      }
    }
  }
}