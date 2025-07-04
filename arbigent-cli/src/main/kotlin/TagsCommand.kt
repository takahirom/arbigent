@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.github.takahirom.arbigent.*
import java.io.File

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
    
    val arbigentProject = ArbigentProject(
      file = File(projectFile),
      aiFactory = { throw UnsupportedOperationException("AI not needed for listing") },
      deviceFactory = { throw UnsupportedOperationException("Device not needed for listing") },
      appSettings = CliAppSettings(
        workingDirectory = workingDirectory,
        path = null,
      )
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