@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.github.takahirom.arbigent.*

/**
 * Prints the scenario dependency graph (dependency edges and reusable `uses` edges)
 * as Mermaid text, e.g. for embedding in Markdown.
 */
class ArbigentGraphCommand : CliktCommand(name = "graph") {
  private val projectFile by projectFileOption()
  private val logLevel by logLevelOption()

  override fun run() {
    applyLogLevel(logLevel)
    val projectFileContent = loadArbigentProjectFileContent(requireProjectFile(projectFile))
    echo(ArbigentScenarioGraph.from(projectFileContent).toMermaid())
  }
}
