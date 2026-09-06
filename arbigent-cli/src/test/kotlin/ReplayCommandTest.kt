package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The replay subcommand reads its range from the command line, so these tests drive it through
 * `--show`, which is the whole command minus the device: the log is read, the range is applied, and
 * what would be sent is printed.
 */
class ReplayCommandTest {
  @Test
  fun `showing a log prints every step and what it would send`() {
    val result = arbigentCli().test("replay ${logFile().path} --show --with-init")

    assertEquals(0, result.statusCode, result.output)
    assertContains(result.output, "launch app.id")
    assertContains(result.output, "step 1: tap sign in")
    assertContains(result.output, "tap on text='Sign in'")
    assertContains(result.output, "step 2: press back")
    assertContains(result.output, "expect: resource ids home")
  }

  @Test
  fun `a single step shows only that step`() {
    val result = arbigentCli().test("replay ${logFile().path} --show --step 2")

    assertEquals(0, result.statusCode, result.output)
    assertContains(result.output, "step 2: press back")
    assertFalse(result.output.contains("step 1:"), result.output)
    assertFalse(result.output.contains("launch app.id"), result.output)
  }

  @Test
  fun `a step number below one is rejected`() {
    val result = arbigentCli().test("replay ${logFile().path} --show --step 0")

    assertEquals(1, result.statusCode, result.output)
    assertContains(result.output, "--step must be 1 or more")
  }

  @Test
  fun `an inverted range is rejected`() {
    val result = arbigentCli().test("replay ${logFile().path} --show --from 2 --until 1")

    assertEquals(1, result.statusCode, result.output)
    assertContains(result.output, "--from 2 is after --until 1")
  }

  @Test
  fun `a log that is not one finished run is rejected`() {
    val file = File.createTempFile("replay-truncated", ".jsonl")
    file.deleteOnExit()
    file.writeText(replayLog.lineSequence().filter { it.isNotBlank() }.toList().dropLast(1).joinToString("\n"))

    val result = arbigentCli().test("replay ${file.path} --show")

    assertEquals(1, result.statusCode, result.output)
    assertContains(result.output, "does not end with a successful scenario_end")
  }

  @Test
  fun `a log that is not there is rejected`() {
    val result = arbigentCli().test("replay /no/such/replay.jsonl --show")

    assertEquals(1, result.statusCode, result.output)
    assertContains(result.output, "is not a file")
  }

  private fun logFile(): File {
    val file = File.createTempFile("replay", ".jsonl")
    file.deleteOnExit()
    file.writeText(replayLog)
    return file
  }
}

/** A two-step successful run, written the way the recorder writes one. */
private val replayLog = """
{"type":"scenario_start","task":"s","taskIndex":0,"step":0,"ts":1,"schemaVersion":1,"platform":"android","goal":"reach the home screen","appId":"app.id"}
{"type":"init","task":"s","taskIndex":0,"step":0,"ts":1,"event":{"type":"launch_app","appId":"app.id","clearState":false,"stopApp":true,"timestamp":1}}
{"type":"decision","task":"s","taskIndex":0,"step":1,"ts":2,"action":"Click","log":"tap sign in","goal":"reach the home screen"}
{"type":"target","task":"s","taskIndex":0,"step":1,"ts":2,"text":"Sign in","occurrence":0}
{"type":"device","task":"s","taskIndex":0,"step":1,"ts":2,"event":{"type":"tap_element","textRegex":"Sign in","index":0,"timestamp":2}}
{"type":"decision","task":"s","taskIndex":0,"step":2,"ts":3,"action":"BackPress","log":"press back","goal":"reach the home screen"}
{"type":"device","task":"s","taskIndex":0,"step":2,"ts":3,"event":{"type":"key_press","keyName":"BACK","timestamp":3}}
{"type":"scenario_end","task":"s","taskIndex":0,"step":2,"ts":9,"status":"success","signature":["home"]}
""".trimIndent() + "\n"
