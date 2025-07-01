@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.ArbigentProject
import java.io.File

class ArbigentTagsCommand : CliktCommand(name = "tags") {
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
    
    val allTags = arbigentProject.scenarios
      .flatMap { it.tags }
      .map { it.name }
      .distinct()
      .sorted()
    
    if (allTags.isEmpty()) {
      println("No tags found in $projectFile")
    } else {
      println("Tags in $projectFile:")
      allTags.forEach { tag ->
        println("- $tag")
      }
    }
  }
}