@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.github.takahirom.arbigent.*
import java.io.File

/**
 * Prints the scenario dependency graph (dependency edges and reusable `uses` edges)
 * as Mermaid text, e.g. for embedding in Markdown.
 */
class ArbigentGraphCommand : CliktCommand(name = "graph") {
  private val projectFile by projectFileOption()
  private val logLevel by logLevelOption()

  override fun run() {
    applyLogLevel(logLevel)
    val projectFilePath = requireProjectFile(projectFile)
    val projectFileContent = if (isJourneyProjectSource(projectFilePath)) {
      ArbigentJourneyXmlImporter.loadProjectContent(File(projectFilePath))
    } else {
      ArbigentProjectSerializer().load(File(projectFilePath))
    }
    echo(ArbigentScenarioGraph.from(projectFileContent).toMermaid())
  }
}
