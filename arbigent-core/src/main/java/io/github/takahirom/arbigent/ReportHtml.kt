package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentAgentResults
import io.github.takahirom.arbigent.result.ArbigentProjectExecutionResult
import kotlinx.serialization.encodeToString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.asResourceFileSystem
import okio.buffer
import java.io.File

public const val arbigentReportTemplateString: String = "TEMPLATE_YAML"

public const val arbigentReportHtml: String = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Report</title>
</head>
<body>
<pre id="result">
$arbigentReportTemplateString
</pre>
<div id="container" style="padding-top:20px;"></div>
<script type="application/javascript" src="arbigent-core-web-report.js"></script>
<script type="application/javascript">
    window.onload = (event) => {
        let counterController = MyComposables.ArbigentReportApp(document.getElementById('result').innerHTML);
    };
</script>
</body>
</html>
"""

public class ArbigentHtmlReport {
  public fun saveReportHtml(outputDir: String, projectExecutionResult: ArbigentProjectExecutionResult, needCopy:Boolean = true) {
    File(outputDir).mkdirs()
    val yaml = if (needCopy) {
      val copiedResult = copyArtifactToOutputDirAndModify(outputDir, projectExecutionResult)
      val modifiedProjectExecutionResult = modifyArtifactToRelativePath(copiedResult, File(outputDir))
      ArbigentProjectExecutionResult.yaml.encodeToString(modifiedProjectExecutionResult)
    } else {
      val modifiedProjectExecutionResult = modifyArtifactToRelativePath(projectExecutionResult, File(outputDir))
      ArbigentProjectExecutionResult.yaml.encodeToString(modifiedProjectExecutionResult)
    }
    writeHtmlReport(yaml, outputDir)
  }

  private fun copyArtifactToOutputDirAndModify(outputDir: String, projectExecutionResult: ArbigentProjectExecutionResult): ArbigentProjectExecutionResult {
    val screenshotsDir = File(outputDir, "screenshots")
    screenshotsDir.mkdirs()
    val jsonlsDir = File(outputDir, "jsonls")
    jsonlsDir.mkdirs()
    return projectExecutionResult.copy(
      scenarios = projectExecutionResult.scenarios.map { scenario ->
        scenario.copy(
          histories = scenario.histories.map { agentResults: ArbigentAgentResults ->
            agentResults.copy(
              agentResults = agentResults.agentResults.map { agentResult ->
                agentResult.copy(
                  steps = agentResult.steps.map { step ->
                    val screenshotFile = File(step.screenshotFilePath)
                    val newScreenshotFile = File(screenshotsDir, screenshotFile.name)
                    screenshotFile.copyTo(newScreenshotFile, overwrite = true)
                    // Only copy annotated file if it exists
                    val annotatedFile = screenshotFile.toAnnotatedFile()
                    if (annotatedFile.exists()) {
                      annotatedFile.copyTo(newScreenshotFile.toAnnotatedFile(), overwrite = true)
                    }
                    step.copy(
                      screenshotFilePath = newScreenshotFile.absolutePath
                    ).let {
                      step.apiCallJsonPath?.let { apiCallJsonPath ->
                        val jsonlFile = File(apiCallJsonPath)
                        val newJsonlFile = File(jsonlsDir, jsonlFile.name)
                        jsonlFile.copyTo(newJsonlFile, overwrite = true)
                        it.copy(
                          apiCallJsonPath = newJsonlFile.absolutePath
                        )
                      } ?: it
                    }
                  }
                )
              }
            )
          }
        )
      }
    )
  }
  private fun modifyArtifactToRelativePath(projectResult: ArbigentProjectExecutionResult, from: File): ArbigentProjectExecutionResult {
    return projectResult.copy(
      scenarios = projectResult.scenarios.map { scenario ->
        scenario.copy(
          histories = scenario.histories.map { agentResults: ArbigentAgentResults ->
            agentResults.copy(
              agentResults = agentResults.agentResults.map { agentResult ->
                agentResult.copy(
                  steps = agentResult.steps.map { step ->
                    step.copy(
                      screenshotFilePath = from.toPath().relativize(File(step.screenshotFilePath).toPath()).toString(),
                      apiCallJsonPath = step.apiCallJsonPath?.let {
                        from.toPath().relativize(File(it).toPath()).toString()
                      }
                    )
                  }
                )
              }
            )
          }
        )
      }
    )
  }

  private fun writeHtmlReport(yaml: String, outputDir: String) {
    val reportHtml = arbigentReportHtml.replace(arbigentReportTemplateString, yaml)
    File(outputDir, "report.html").writeText(reportHtml)

    val resourceFileSystem = this::class.java.classLoader
      .asResourceFileSystem()
    resourceFileSystem
      .list((File.separator + "arbigent-core-web-report-resources").toPath()).forEach { path: Path ->
        resourceFileSystem.source(path)
          .use { fromSource ->
            // Copy to File(outputDir, path.name)
            FileSystem.SYSTEM.sink(File(outputDir, path.name).toOkioPath())
              .use { toSink ->
                fromSource.buffer().readAll(toSink)
              }
          }
      }
  }
}
