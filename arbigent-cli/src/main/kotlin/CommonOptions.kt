@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import io.github.takahirom.arbigent.ArbigentInternalApi

const val defaultResultPath = "arbigent-result"
const val defaultCachePath = "arbigent-cache"

// Common options shared between commands
fun CliktCommand.projectFileOption() = defaultOption("--project-file", help = "Path to the project YAML file")

fun CliktCommand.workingDirectoryOption() = defaultOption("--working-directory", help = "Working directory for the project")

fun CliktCommand.logLevelOption() = defaultOption("--log-level", help = "Log level")
  .choice("debug", "info", "warn", "error")
  .default("info")

fun CliktCommand.logFileOption() = defaultOption("--log-file", help = "Log file path")
  .default("$defaultResultPath/arbigent.log")