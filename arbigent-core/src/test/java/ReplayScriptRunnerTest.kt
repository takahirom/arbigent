package io.github.takahirom.arbigent.sample.test

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the python runner shipped as a resource, because a syntax error in it would otherwise only
 * surface on the machine someone tries to replay on.
 */
class ReplayScriptRunnerTest {
  private val fixture = "replay-scripts/sample-scenario.jsonl"

  @Test
  fun `the runner is valid python for the interpreter a machine is likely to have`() {
    val dir = Files.createTempDirectory("replay-runner").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val (code, output) = run(dir, listOf("python3", "-m", "py_compile", script.absolutePath))
    assertEquals(0, code, "python3 -m py_compile failed:\n$output")
  }

  @Test
  fun `--show lists the steps without needing a device`() {
    val dir = Files.createTempDirectory("replay-runner-show").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val log = File(dir, "sample-scenario.jsonl")
    log.writeText(readResource(fixture))

    val (code, output) = run(
      dir,
      listOf("python3", script.absolutePath, log.absolutePath, "--show", "--with-init"),
    )
    assertEquals(0, code, "--show failed:\n$output")
    assertTrue(output.contains("goal: Open the settings screen"), output)
    assertTrue(output.contains("1. Press the down key 3 times"), output)
    assertTrue(output.contains("KEYCODE_DPAD_DOWN x3"), output)
    assertTrue(output.contains("target: text='Settings'"), output)
    assertTrue(output.contains("screen: text='Settings', resourceId='com.example.app:id/settings_tab'"), output)
    assertTrue(output.contains("launch(com.example.app, clearState, debug_menu=true, entry=\"settings\")"), output)
    assertTrue(output.contains("expect: resource ids"), output)
  }

  @Test
  fun `a range selects only those steps`() {
    val dir = Files.createTempDirectory("replay-runner-range").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val log = File(dir, "sample-scenario.jsonl")
    log.writeText(readResource(fixture))

    val (code, output) = run(
      dir,
      listOf("python3", script.absolutePath, log.absolutePath, "--show", "--from", "2"),
    )
    assertEquals(0, code, output)
    assertTrue(output.contains("2. Press the center key"), output)
    assertTrue(!output.contains("1. Press the down key"), output)
  }

  @Test
  fun `--show reports the center coordinates the md points a human at`() {
    val dir = Files.createTempDirectory("replay-runner-center").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val log = File(dir, "sample-scenario.jsonl")
    log.writeText(readResource(fixture))

    val (code, output) = run(
      dir,
      listOf("python3", script.absolutePath, log.absolutePath, "--show", "--backend", "maestro"),
    )
    // --backend is parsed but no hierarchy is read, so the unimplemented backend must not be hit.
    assertEquals(0, code, output)
    assertTrue(output.contains("center=(200,230)"), output)
  }

  @Test
  fun `an unknown backend is a usage error`() {
    val dir = Files.createTempDirectory("replay-runner-backend").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val log = File(dir, "sample-scenario.jsonl")
    log.writeText(readResource(fixture))

    val (code, output) = run(
      dir,
      listOf("python3", script.absolutePath, log.absolutePath, "--backend", "nope"),
    )
    assertEquals(1, code, output)
    assertTrue(output.contains("--backend must be one of"), output)
  }

  @Test
  fun `matching relaxes attributes, spans both resource id forms and both geometry shapes`() {
    val dir = Files.createTempDirectory("replay-runner-match").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val probe = File(dir, "probe.py")
    probe.writeText(
      """
      import json, runpy
      module = runpy.run_path(SCRIPT_PATH, run_name="replay_runner")
      find_match = module["find_match"]
      resolvable = module["identity_is_resolvable"]
      normalize = module["normalize"]
      target = {"text": "Settings", "resourceId": "com.example.app:id/tab", "occurrence": 0}
      strict = [{"text": "Settings", "resourceId": "com.example.app:id/tab"}]
      split = [{"text": None, "resourceId": "com.example.app:id/tab"}]
      compose = [{"text": None, "resourceId": None, "accessibilityId": None}]
      # The `android` CLI prints a resource id without its package prefix.
      short = [{"text": None, "resourceId": "tab"}]
      other = [{"text": None, "resourceId": "com.example.other:id/tab"}]
      print(json.dumps({
          "strict": find_match(strict, target) is not None,
          "relaxed": find_match(split, target) is not None,
          "missing": find_match(compose, target) is None,
          "shortId": find_match(short, target) is not None,
          "otherPackage": find_match(other, target) is None,
          "resolvableStrict": resolvable(strict, target),
          "resolvableCompose": resolvable(compose, target),
          "androidCenter": normalize({"center": "[12,34]"})["center"],
          "uiautomatorCenter": normalize({"bounds": "[0,0][100,50]"})["center"],
      }))
      """.trimIndent().replace("SCRIPT_PATH", "\"" + script.absolutePath + "\"")
    )

    val (code, output) = run(dir, listOf("python3", probe.absolutePath))
    assertEquals(0, code, output)
    assertTrue(output.contains("\"strict\": true"), output)
    assertTrue(output.contains("\"relaxed\": true"), output)
    assertTrue(output.contains("\"missing\": true"), output)
    assertTrue(output.contains("\"resolvableStrict\": true"), output)
    assertTrue(output.contains("\"resolvableCompose\": false"), output)
    assertTrue(output.contains("\"shortId\": true"), output)
    assertTrue(output.contains("\"otherPackage\": true"), output)
    assertTrue(output.contains("\"x\": 12"), output)
    assertTrue(output.contains("\"x\": 50"), output)
  }

  /** The shell wrapper feeds the runner to python on stdin, so the test needs the same slice. */
  private fun extractPython(): String {
    val source = readResource("replay.sh")
    val start = source.indexOf("<<'PY'\n") + "<<'PY'\n".length
    val end = source.indexOf("\nPY\n", start)
    assertTrue(start > 0 && end > start, "replay.sh no longer wraps the runner in a PY heredoc")
    return source.substring(start, end)
  }

  private fun readResource(name: String): String =
    checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing resource $name" }
      .use { it.readBytes().decodeToString() }

  private fun run(workingDir: File, command: List<String>): Pair<Int, String> {
    val process = ProcessBuilder(command)
      .directory(workingDir)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor(60, TimeUnit.SECONDS)
    return process.exitValue() to output
  }
}
