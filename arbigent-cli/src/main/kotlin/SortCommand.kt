@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import io.github.takahirom.arbigent.*
import java.io.File

/**
 * Rewrites the project file so each scenario follows the scenario it depends on and carries a
 * position comment (`# tree: root > ... > id | children: ...`). Text outside the reordered
 * blocks is left untouched. `--diff` reports what would change instead of writing, for CI.
 */
class ArbigentSortCommand : CliktCommand(name = "sort") {
  private val projectFile by projectFileOption()
  private val logLevel by logLevelOption()
  private val diff by option(
    "--diff",
    help = "Print a unified diff of what sort would change and exit with status 1 if there is any; the file is not modified"
  ).flag(default = false)
  private val comments: Boolean? by option(
    help = "Write (default) or remove the position comments above each scenario; the default is the project's settings.positionComments"
  ).switch("--comments" to true, "--no-comments" to false)

  override fun help(context: Context): String =
    "Reorder scenarios into depth-first dependency order and refresh the position comments above them"

  override fun run() {
    applyLogLevel(logLevel)
    val path = requireProjectFile(projectFile)
    if (isJourneyProjectSource(path)) {
      throw CliktError("sort only works on an arbigent YAML project file, not on a Journeys XML source: $path")
    }
    val file = File(path)
    if (!file.isFile) throw CliktError("Project file not found: $path")
    val original = file.readText()
    // Loading first turns a broken `dependency` graph into the usual validation report.
    val content = loadArbigentProjectFileContent(path)
    val result = try {
      ArbigentScenarioSorter.sort(
        yamlText = original,
        content = content,
        positionComments = comments ?: content.settings.positionComments,
      )
    } catch (e: IllegalArgumentException) {
      throw CliktError("Cannot sort $path: ${e.message}")
    }

    if (result.yaml == original) {
      echo("Already sorted: $path")
      return
    }
    val summary = summarize(result)
    if (diff) {
      echo(unifiedDiff(path, original, result.yaml))
      echo("$summary Run `arbigent sort` to apply.")
      throw ProgramResult(1)
    }
    file.writeText(result.yaml)
    echo("Sorted $path: $summary")
  }

  private fun summarize(result: ArbigentScenarioSorter.Result): String {
    fun count(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"
    return count(result.movedScenarioIds.size, "scenario") + " out of place, " +
      count(result.staleCommentScenarioIds.size, "position comment") + " stale" +
      (if (result.headerStale) ", header comment stale." else ".")
  }

  private fun unifiedDiff(path: String, original: String, revised: String): String {
    val originalLines = original.lines()
    val revisedLines = revised.lines()
    val patch = DiffUtils.diff(originalLines, revisedLines)
    return UnifiedDiffUtils.generateUnifiedDiff(path, "$path (sorted)", originalLines, patch, 3)
      .joinToString("\n")
  }
}
