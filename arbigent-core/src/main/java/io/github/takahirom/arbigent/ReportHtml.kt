package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentAgentResults
import io.github.takahirom.arbigent.result.ArbigentProjectExecutionResult
import kotlinx.serialization.encodeToString
import okio.FileSystem
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
        let counterController = MyComposables.ArbigentReportApp(document.getElementById('result').innerText);
    };
</script>
</body>
</html>
"""

public class ArbigentHtmlReport {
  private fun modifyScreenshotPathToRelativePath(projectResult: ArbigentProjectExecutionResult, from: File): ArbigentProjectExecutionResult {
    return projectResult.copy(
      scenarios = projectResult.scenarios.map { scenario ->
        scenario.copy(
          histories = scenario.histories.map { agentResults: ArbigentAgentResults ->
            agentResults.copy(
              agentResult = agentResults.agentResult.map { agentResult ->
                agentResult.copy(
                  steps = agentResult.steps.map { step ->
                    step.copy(
                      screenshotFilePath = from.toPath().relativize(File(step.screenshotFilePath).toPath()).toString()
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
  public fun saveReportHtml(outputDir: String, projectExecutionResult: ArbigentProjectExecutionResult) {
    val modifiedProjectExecutionResult = modifyScreenshotPathToRelativePath(projectExecutionResult, File(outputDir))
    File(outputDir).mkdirs()
    val yaml = ArbigentProjectExecutionResult.yaml.encodeToString(modifiedProjectExecutionResult)
    val reportHtml = arbigentReportHtml.replace(arbigentReportTemplateString, yaml)
    File(outputDir, "report.html").writeText(reportHtml)

    val resourceFileSystem = this::class.java.classLoader
      .asResourceFileSystem()
    resourceFileSystem
      .list("/arbigent-core-web-report-resources".toPath()).forEach { path: okio.Path ->
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

