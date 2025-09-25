@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.sources.ValueSource
import com.github.ajalt.clikt.sources.ChainedValueSource
import io.github.takahirom.arbigent.ArbigentAppSettings
import io.github.takahirom.arbigent.ArbigentInternalApi
import java.io.File

/**
 * Custom implementation of [ArbigentAppSettings] for CLI.
 */
data class CliAppSettings(
  override val workingDirectory: String?,
  override val path: String?,
  override val variables: Map<String, String>? = null,
  override val mcpEnvironmentVariables: Map<String, String>? = null
) : ArbigentAppSettings

class ArbigentCli : CliktCommand(name = "arbigent") {
  init {
    context {
      // Configuration file priority order: local.yml > local.yaml > settings.yml > settings.yaml
      val configFileNames = listOf(
        ".arbigent/settings.local.yml",
        ".arbigent/settings.local.yaml", 
        ".arbigent/settings.yml",
        ".arbigent/settings.yaml"
      )
      
      val existingConfigFiles = configFileNames.mapNotNull { fileName ->
        val file = File(fileName)
        if (file.exists()) file.absolutePath else null
      }
      
      if (existingConfigFiles.isNotEmpty()) {
        val valueSources = mutableListOf<ValueSource>()
        
        // For each config file, add command-specific settings first, then global settings
        // This ensures proper priority: local.yml run.xxx > local.yml xxx > local.yaml run.xxx > local.yaml xxx > ...
        existingConfigFiles.forEach { configPath ->
          // Add command-specific settings (run.xxx, scenarios.xxx, etc.) - higher priority
          valueSources.add(
            YamlValueSource.from(
              configPath,
              getKey = ValueSource.getKey(joinSubcommands = ".")
            )
          )
          
          // Add global settings (xxx) - lower priority fallback for this file
          valueSources.add(
            YamlValueSource.from(
              configPath,
              getKey = { _, option -> option.names.first().removePrefix("--") }
            )
          )
        }
        
        valueSource = ChainedValueSource(valueSources)
      }
    }
  }
  override fun run() = Unit
}


fun main(args: Array<String>) {
  LoggingUtils.suppressSlf4jWarnings()
  
  ArbigentCli()
    .subcommands(ArbigentRunCommand(), ArbigentScenariosCommand(), ArbigentTagsCommand())
    .main(args)
}
