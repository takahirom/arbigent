package io.github.takahirom.arbigent

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
  public fun saveReportHtml(outputDir: String, projectExecutionResult: ArbigentProjectExecutionResult) {
    File(outputDir).mkdirs()
    val yaml = ArbigentProjectExecutionResult.yaml.encodeToString(projectExecutionResult)
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

