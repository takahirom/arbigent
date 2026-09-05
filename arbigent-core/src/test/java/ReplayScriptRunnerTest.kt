package io.github.takahirom.arbigent.sample.test

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

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

  @Test
  fun `setup that ran after a step stays after it`() {
    val dir = Files.createTempDirectory("replay-runner-order").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val log = File(dir, "sample-scenario.jsonl")
    log.writeText(readResource(fixture))

    val (code, output) = run(
      dir,
      listOf("python3", script.absolutePath, log.absolutePath, "--show", "--with-init"),
    )
    assertEquals(0, code, output)
    val setups = output.lines().withIndex().filter { it.value.startsWith("0. setup") }.map { it.index }
    val lastStep = output.lines().indexOfFirst { it.startsWith("3. ") }
    assertEquals(2, setups.size, "a task that launched the app again must show two setup blocks:\n$output")
    assertTrue(setups[0] < lastStep && lastStep < setups[1], "the second setup must follow step 3:\n$output")
    assertTrue(output.contains("tap(text='Account')"), output)
  }

  @Test
  fun `a setup block is replayed only with the step it prepared for`() {
    val dir = Files.createTempDirectory("replay-runner-init-range").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val log = File(dir, "sample-scenario.jsonl")
    log.writeText(readResource(fixture))

    // The relaunch after step 3 is not needed when the replay stops at step 2, but the launch
    // before step 1 is: it is how the app gets on screen at all.
    val (untilCode, untilOutput) = run(
      dir,
      listOf("python3", script.absolutePath, log.absolutePath, "--show", "--with-init", "--until", "2"),
    )
    assertEquals(0, untilCode, untilOutput)
    assertEquals(1, untilOutput.lines().count { it.startsWith("0. setup") }, untilOutput)
    assertTrue(untilOutput.lines().first { it.startsWith("0. setup") }.isNotEmpty())

    val (fromCode, fromOutput) = run(
      dir,
      listOf("python3", script.absolutePath, log.absolutePath, "--show", "--with-init", "--from", "3"),
    )
    assertEquals(0, fromCode, fromOutput)
    assertEquals(2, fromOutput.lines().count { it.startsWith("0. setup") }, fromOutput)
    assertTrue(!fromOutput.contains("1. Press the down key"), fromOutput)
  }

  @Test
  fun `step numbers below one and inverted ranges are usage errors`() {
    val dir = Files.createTempDirectory("replay-runner-range").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val log = File(dir, "sample-scenario.jsonl")
    log.writeText(readResource(fixture))

    listOf(listOf("--from", "0"), listOf("--step", "-1"), listOf("--until", "0"), listOf("--from", "3", "--until", "2"))
      .forEach { flags ->
        val (code, output) = run(dir, listOf("python3", script.absolutePath, log.absolutePath, "--show") + flags)
        assertEquals(1, code, "$flags: $output")
        assertTrue(output.contains("replay.sh:"), "$flags: $output")
        assertTrue(!output.contains("nothing to replay"), "$flags should be rejected as an argument, not an empty range: $output")
      }
  }

  @Test
  fun `a log that cannot be read is a usage error rather than a traceback`() {
    val dir = Files.createTempDirectory("replay-runner-unreadable").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val log = File(dir, "sample-scenario.jsonl")
    log.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte(), '{'.code.toByte()))

    val (code, output) = run(dir, listOf("python3", script.absolutePath, log.absolutePath, "--show"))
    assertEquals(1, code, output)
    assertTrue(output.contains("could not read event log"), output)
    assertTrue(!output.contains("Traceback"), output)
  }

  @Test
  fun `the wrapper refuses to run without python3 instead of exiting 127`() {
    val dir = Files.createTempDirectory("replay-runner-nopython").toFile()
    val script = File(dir, "replay.sh")
    script.writeText(readResource("replay.sh"))
    val process = ProcessBuilder("/bin/sh", script.absolutePath, "whatever.jsonl")
      .directory(dir)
      .redirectErrorStream(true)
    process.environment()["PATH"] = dir.absolutePath
    val started = process.start()
    val output = started.inputStream.bufferedReader().readText()
    started.waitFor(60, TimeUnit.SECONDS)
    assertEquals(1, started.exitValue(), output)
    assertTrue(output.contains("python3 is required"), output)
  }

  @Test
  fun `--step with --with-init replays the launch and the setup that prepared that step`() {
    val dir = Files.createTempDirectory("replay-runner-step-init").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val log = File(dir, "sample-scenario.jsonl")
    log.writeText(readResource(fixture))

    // Step 2 needs only the launch; step 3 also owns the relaunch recorded right after it.
    val (code, output) = run(
      dir,
      listOf("python3", script.absolutePath, log.absolutePath, "--show", "--with-init", "--step", "2"),
    )
    assertEquals(0, code, output)
    assertEquals(1, output.lines().count { it.startsWith("0. setup") }, "--step must not drop --with-init:\n$output")
    assertTrue(output.contains("2. Press the center key"), output)
    assertTrue(!output.contains("1. Press the down key"), output)

    val (lastCode, lastOutput) = run(
      dir,
      listOf("python3", script.absolutePath, log.absolutePath, "--show", "--with-init", "--step", "3"),
    )
    assertEquals(0, lastCode, lastOutput)
    assertEquals(2, lastOutput.lines().count { it.startsWith("0. setup") }, lastOutput)
  }

  @Test
  fun `--show on an empty range is a usage error`() {
    val dir = Files.createTempDirectory("replay-runner-empty").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val log = File(dir, "sample-scenario.jsonl")
    log.writeText(readResource(fixture))

    val (code, output) = run(
      dir,
      listOf("python3", script.absolutePath, log.absolutePath, "--show", "--from", "9"),
    )
    assertEquals(1, code, output)
    assertTrue(output.contains("nothing to replay"), output)
  }

  @Test
  fun `a dump that adb could not take at all is a device failure, not an empty screen`() {
    val dir = Files.createTempDirectory("replay-runner-dump").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val probe = File(dir, "probe.py")
    probe.writeText(
      """
      import json, runpy
      module = runpy.run_path(SCRIPT_PATH, run_name="replay_runner")
      Options = module["Options"]
      DeviceCommandFailed = module["DeviceCommandFailed"]
      Diverged = module["Diverged"]
      module["time"].sleep = lambda seconds: None
      calls = []

      def fake_adb(kind):
          def adb(options, args, capture=False):
              calls.append(args)
              if args[:2] == ["shell", "rm"]:
                  return 0, b"", b""
              if kind == "no-device":
                  return 1, b"", b"adb: device 'bogus' not found"
              if kind == "busy":
                  return 0, b"ERROR: could not get idle state.", b""
              return 0, b"", b""
          return adb

      options = Options()
      options.backend = "uiautomator"
      result = {}
      module["adb"] = fake_adb("no-device")
      module["dump_tree_uiautomator"].__globals__["adb"] = module["adb"]
      try:
          module["dump_tree"](options)
          result["noDevice"] = "returned"
      except DeviceCommandFailed as error:
          result["noDevice"] = "failed: %s" % error
      module["dump_tree_uiautomator"].__globals__["adb"] = fake_adb("busy")
      result["busy"] = module["dump_tree"](options)
      try:
          module["send_event"](options, {"type": "input_text", "text": "50%s off"}, {}, [])
          result["percent"] = "sent"
      except Diverged as error:
          result["percent"] = "diverged"
      print(json.dumps(result))
      """.trimIndent().replace("SCRIPT_PATH", "\"" + script.absolutePath + "\"")
    )

    val (code, output) = run(dir, listOf("python3", probe.absolutePath))
    assertEquals(0, code, output)
    assertTrue(output.contains("\"noDevice\": \"failed: uiautomator dump failed: adb: device 'bogus' not found\""), output)
    // A screen that is merely still animating is not a device failure.
    assertTrue(output.contains("\"busy\": []"), output)
    assertTrue(output.contains("\"percent\": \"diverged\""), output)
  }

  @Test
  fun `a log without a successful end is refused`() {
    val dir = Files.createTempDirectory("replay-runner-torn").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val torn = File(dir, "torn.jsonl")
    torn.writeText(readResource(fixture).lines().filter { !it.contains("scenario_end") }.joinToString("\n"))
    val (code, output) = run(dir, listOf("python3", script.absolutePath, torn.absolutePath, "--show"))
    assertEquals(1, code, output)
    assertTrue(output.contains("does not end with a successful scenario_end"), output)

    val broken = File(dir, "broken.jsonl")
    broken.writeText(readResource(fixture) + "{not json\n")
    val (brokenCode, brokenOutput) = run(dir, listOf("python3", script.absolutePath, broken.absolutePath, "--show"))
    assertEquals(1, brokenCode, brokenOutput)
    assertTrue(brokenOutput.contains("is not JSON"), brokenOutput)
  }

  @Test
  fun `a log that is more than one finished run is refused`() {
    val dir = Files.createTempDirectory("replay-runner-shape").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val lines = readResource(fixture).lines().filter { it.isNotBlank() }
    val deviceLine = lines.first { it.contains("\"type\":\"device\"") || it.contains("\"type\": \"device\"") }
    val startLine = lines.first { it.contains("scenario_start") }

    fun refuse(name: String, content: List<String>, expected: String) {
      val log = File(dir, name)
      log.writeText(content.joinToString("\n"))
      val (code, output) = run(dir, listOf("python3", script.absolutePath, log.absolutePath, "--show"))
      assertEquals(1, code, output)
      assertTrue(output.contains(expected), output)
    }

    // A device line appended after the successful end is not part of the run that succeeded.
    refuse("appended.jsonl", lines + deviceLine, "does not end with a successful scenario_end")
    // Two runs concatenated into one file.
    refuse("doubled.jsonl", lines + lines, "more than one scenario_start")
    refuse("restarted.jsonl", listOf(startLine) + lines, "more than one scenario_start")
    refuse("headless.jsonl", lines.drop(1), "does not start with a scenario_start")
    // A line from another scenario spliced into the middle.
    val foreign = deviceLine.replace("\"open-settings\"", "\"other-scenario\"")
    assertTrue(foreign != deviceLine)
    refuse("mixed.jsonl", lines.dropLast(1) + foreign + lines.last(), "mixes lines from scenarios")
  }

  @Test
  fun `a failed setup block points the resume hint at the step it prepared`() {
    val dir = Files.createTempDirectory("replay-runner-resume").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val probe = File(dir, "probe.py")
    probe.writeText(
      """
      import io, json, runpy
      module = runpy.run_path(SCRIPT_PATH, run_name="replay_runner")
      options = module["Options"]()
      options.log = "open settings.jsonl"

      def step(number):
          return {"number": number, "isInit": number == 0}

      def hint(current, remaining):
          out = io.StringIO()
          module["write_resume_hint"](out, options, current, remaining)
          return out.getvalue().strip()

      print(json.dumps({
          "normal": hint(step(2), [step(3)]),
          "firstSetup": hint(step(0), [step(1), step(2)]),
          "laterSetup": hint(step(0), [step(3)]),
          "trailingSetup": hint(step(0), []),
      }))
      """.trimIndent().replace("SCRIPT_PATH", "\"" + script.absolutePath + "\"")
    )

    val (code, output) = run(dir, listOf("python3", probe.absolutePath))
    assertEquals(0, code, output)
    assertTrue(output.contains("\"normal\": \"resume with: ./replay.sh 'open settings.jsonl' --from 2\""), output)
    // Step zero is not a valid --from, so the setup is replayed through --with-init on the next step.
    assertTrue(output.contains("\"firstSetup\": \"resume with: ./replay.sh 'open settings.jsonl' --with-init --from 1\""), output)
    assertTrue(output.contains("\"laterSetup\": \"resume with: ./replay.sh 'open settings.jsonl' --with-init --from 3\""), output)
    assertTrue(output.contains("\"trailingSetup\": \"\""), output)
  }

  @Test
  fun `a timeout that could never elapse is a usage error`() {
    val dir = Files.createTempDirectory("replay-runner-timeout").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val log = File(dir, "sample-scenario.jsonl")
    log.writeText(readResource(fixture))
    for (value in listOf("nan", "inf", "0", "-3")) {
      val (code, output) = run(
        dir,
        listOf("python3", script.absolutePath, log.absolutePath, "--show", "--timeout", value),
      )
      assertEquals(1, code, "--timeout $value should be refused:\n$output")
      assertTrue(output.contains("positive number of seconds"), output)
    }
  }

  @Test
  fun `remote arguments are quoted for the device shell and element taps resolve like maestro`() {
    val dir = Files.createTempDirectory("replay-runner-quote").toFile()
    val script = File(dir, "runner.py")
    script.writeText(extractPython())
    val probe = File(dir, "probe.py")
    probe.writeText(
      """
      import json, runpy
      module = runpy.run_path(SCRIPT_PATH, run_name="replay_runner")
      adb_command = module["adb_command"]
      escape_text = module["escape_text"]
      resolve = module["resolve_element"]
      nodes = [
          {"text": "Account", "resourceId": "com.example.app:id/row", "accessibilityId": None},
          {"text": "account settings", "resourceId": "com.example.app:id/row", "accessibilityId": None},
          {"text": None, "resourceId": "settings_tab", "accessibilityId": "Open menu"},
      ]
      print(json.dumps({
          "shell": adb_command(["shell", "am", "start", "--es", "entry", "a b; rm -rf /"]),
          "text": escape_text("100% sure"),
          "exact": resolve(nodes, {"textRegex": "account", "index": 0})["text"],
          "second": resolve(nodes, {"textRegex": ".*account.*", "index": 1})["text"],
          "shortId": resolve(nodes, {"idRegex": "com.example.app:id/settings_tab"})["accessibilityId"],
          "label": resolve(nodes, {"textRegex": "Open Menu"})["resourceId"],
          "none": resolve(nodes, {"textRegex": "Nowhere"}) is None,
      }))
      """.trimIndent().replace("SCRIPT_PATH", "\"" + script.absolutePath + "\"")
    )

    val (code, output) = run(dir, listOf("python3", probe.absolutePath))
    assertEquals(0, code, output)
    assertTrue(output.contains("\"shell\": [\"adb\", \"shell\", \"am start --es entry 'a b; rm -rf /'\"]"), output)
    assertTrue(output.contains("\"text\": \"100%%ssure\""), output)
    assertTrue(output.contains("\"exact\": \"Account\""), output)
    assertTrue(output.contains("\"second\": \"account settings\""), output)
    assertTrue(output.contains("\"shortId\": \"Open menu\""), output)
    assertTrue(output.contains("\"label\": \"settings_tab\""), output)
    assertTrue(output.contains("\"none\": true"), output)
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

  /**
   * Output goes to a file so the time limit applies before anything is read: a hung runner keeps
   * its stdout open, and reading the pipe first would wait on it forever.
   */
  private fun run(workingDir: File, command: List<String>): Pair<Int, String> {
    val outputFile = File.createTempFile("replay-runner-output", ".txt", workingDir)
    val process = ProcessBuilder(command)
      .directory(workingDir)
      .redirectErrorStream(true)
      .redirectOutput(outputFile)
      .start()
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly().waitFor()
      fail("the runner did not finish within 60 seconds:\n${outputFile.readText()}")
    }
    return process.exitValue() to outputFile.readText()
  }
}
