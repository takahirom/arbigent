@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.sources.PropertiesValueSource
import com.github.ajalt.clikt.sources.ValueSource
import io.github.takahirom.arbigent.ArbigentAppSettings
import io.github.takahirom.arbigent.ArbigentInternalApi
import java.io.File

/**
 * Custom implementation of [ArbigentAppSettings] for CLI.
 */
data class CliAppSettings(
  override val workingDirectory: String?,
  override val path: String?
) : ArbigentAppSettings

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


fun main(args: Array<String>) {
  LoggingUtils.suppressSlf4jWarnings()
  
  ArbigentCli()
    .subcommands(ArbigentRunCommand(), ArbigentScenariosCommand(), ArbigentTagsCommand())
    .main(args)
}
