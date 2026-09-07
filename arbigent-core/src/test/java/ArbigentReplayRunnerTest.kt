package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.ArbigentDevice
import io.github.takahirom.arbigent.ArbigentDeviceEvent
import io.github.takahirom.arbigent.ArbigentDeviceOs
import io.github.takahirom.arbigent.ArbigentElement
import io.github.takahirom.arbigent.ArbigentElementList
import io.github.takahirom.arbigent.ArbigentReplayLog
import io.github.takahirom.arbigent.ArbigentReplayLogException
import io.github.takahirom.arbigent.ArbigentReplayRunner
import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import maestro.KeyCode
import maestro.TreeNode
import maestro.orchestra.MaestroCommand
import kotlin.test.assertFailsWith
import io.github.takahirom.arbigent.ArbigentReplayWait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The replay runner drives a recorded log with no AI, so these tests pin the two things a caller
 * branches on: which commands reach the device, and which exit code comes back.
 */
class ArbigentReplayRunnerTest {
  @Test
  fun `the recorded events are sent in the order they were recorded`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(number = 0, events = listOf(ArbigentDeviceEvent.LaunchApp("app.id", timestamp = 1))),
        TestStep(
          number = 1,
          target = Attributes(text = "Sign in"),
          events = listOf(ArbigentDeviceEvent.TapElement(textRegex = "Sign in", timestamp = 2)),
        ),
        TestStep(
          number = 2,
          target = Attributes(resourceId = "home"),
          events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.ENTER.name, timestamp = 3)),
        ),
      ),
      signature = listOf("home"),
    )
    val device = FakeReplayDevice(listOf(screen(Attributes(text = "Sign in"), Attributes(resourceId = "home"))))
    val runner = runner(log, device)

    assertNull(runner.verifyReplayable(log.select(withInit = true)))
    val exitCode = runner.run(log.select(withInit = true))

    assertEquals(ArbigentReplayRunner.EXIT_OK, exitCode)
    assertEquals(
      listOf("launch app.id", "tap Sign in", "press ENTER"),
      device.commands.map { it.describeForTest() },
    )
  }

  @Test
  fun `a recorded tap index reaches the device exactly as it was recorded`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(
          number = 1,
          target = Attributes(text = "Play"),
          events = listOf(ArbigentDeviceEvent.TapElement(textRegex = "Play", index = 0, timestamp = 2)),
        ),
        TestStep(
          number = 2,
          target = Attributes(text = "Play"),
          events = listOf(ArbigentDeviceEvent.TapElement(textRegex = "Play", index = 2, timestamp = 3)),
        ),
        TestStep(
          number = 3,
          target = Attributes(text = "Play"),
          events = listOf(ArbigentDeviceEvent.TapElement(textRegex = "Play", timestamp = 4)),
        ),
      ),
      signature = listOf("player"),
    )
    val device = FakeReplayDevice(
      listOf(screen(Attributes(text = "Play"), Attributes(resourceId = "player"))),
    )

    val exitCode = runner(log, device).run(log.select(withInit = true))

    assertEquals(ArbigentReplayRunner.EXIT_OK, exitCode)
    // Maestro reads a missing index as "the first clickable match" and any explicit index as a
    // plain position, so an index of 0 must not be dropped on the way to the device.
    assertEquals(
      listOf("0", "2", null),
      device.commands.map { it.tapOnElement!!.selector.index },
    )
  }

  @Test
  fun `a target that appears late is waited for`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(
          number = 1,
          target = Attributes(text = "Later"),
          events = listOf(ArbigentDeviceEvent.TapElement(textRegex = "Later", timestamp = 2)),
        ),
      ),
    )
    val device = FakeReplayDevice(
      listOf(
        screen(),
        screen(),
        screen(Attributes(text = "Later")),
      ),
    )

    val exitCode = runner(log, device).run(log.select())

    assertEquals(ArbigentReplayRunner.EXIT_OK, exitCode)
    assertEquals(listOf("tap Later"), device.commands.map { it.describeForTest() })
    assertTrue(device.elementReads >= 3, "expected the runner to poll the screen, read ${device.elementReads} times")
  }

  @Test
  fun `a target that never appears diverges without sending the step`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(
          number = 1,
          target = Attributes(text = "Never"),
          events = listOf(ArbigentDeviceEvent.TapElement(textRegex = "Never", timestamp = 2)),
        ),
      ),
    )
    val device = FakeReplayDevice(listOf(screen(Attributes(text = "Something else"))))
    val err = StringBuilder()

    val exitCode = runner(log, device, err = err).run(log.select())

    assertEquals(ArbigentReplayRunner.EXIT_DIVERGED, exitCode)
    assertEquals(emptyList<String>(), device.commands.map { it.describeForTest() })
    assertTrue(err.contains("DIVERGED"), err.toString())
    assertTrue(err.contains("the target never appeared"), err.toString())
    assertTrue(err.contains("resume with: arbigent replay <log> --from 1"), err.toString())
  }

  @Test
  fun `an element the recording tapped that is gone diverges`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(
          number = 1,
          events = listOf(ArbigentDeviceEvent.TapElement(textRegex = "Gone", timestamp = 2)),
        ),
      ),
    )
    val device = FakeReplayDevice(
      listOf(screen()),
      failExecuteWith = { maestro.MaestroException.ElementNotFound("not found", TreeNode(), "not found") },
    )

    val exitCode = runner(log, device).run(log.select())

    assertEquals(ArbigentReplayRunner.EXIT_DIVERGED, exitCode)
  }

  @Test
  fun `a device that cannot be driven reports a device failure`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.BACK.name, timestamp = 2))),
      ),
    )
    val device = FakeReplayDevice(
      listOf(screen()),
      failExecuteWith = { java.io.IOException("adb is gone") },
    )
    val err = StringBuilder()

    val exitCode = runner(log, device, err = err).run(log.select())

    assertEquals(ArbigentReplayRunner.EXIT_DEVICE, exitCode)
    assertTrue(err.contains("adb is gone"), err.toString())
  }

  @Test
  fun `a log recorded on another platform is refused before anything is sent`() = runTest {
    val log = readLog(
      platform = "ios",
      steps = listOf(
        TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.BACK.name, timestamp = 2))),
      ),
    )
    val device = FakeReplayDevice(listOf(screen()), os = ArbigentDeviceOs.Android)

    val reason = runner(log, device).verifyReplayable(log.select())

    assertNotNull(reason)
    assertTrue(reason.contains("recorded on ios"), reason)
    assertEquals(emptyList<String>(), device.commands.map { it.describeForTest() })
  }

  @Test
  fun `a command the replay cannot reproduce is refused before anything is sent`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.BACK.name, timestamp = 2))),
        TestStep(number = 2, events = listOf(ArbigentDeviceEvent.Unsupported("hideKeyboard", timestamp = 3))),
      ),
    )
    val device = FakeReplayDevice(listOf(screen()))

    val reason = runner(log, device).verifyReplayable(log.select())

    assertNotNull(reason)
    assertTrue(reason.contains("step 2: hideKeyboard"), reason)
    assertEquals(emptyList<String>(), device.commands.map { it.describeForTest() })
  }

  @Test
  fun `an empty selection is refused before anything is sent`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.BACK.name, timestamp = 2))),
      ),
    )

    val reason = runner(log, FakeReplayDevice(listOf(screen()))).verifyReplayable(emptyList())

    assertEquals("nothing to replay for the given range", reason)
  }

  @Test
  fun `a key this Maestro does not know is refused before anything is sent`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.BACK.name, timestamp = 2))),
        TestStep(number = 2, events = listOf(ArbigentDeviceEvent.KeyPress("NOT_A_KEY", timestamp = 3))),
      ),
    )
    val device = FakeReplayDevice(listOf(screen()))

    val reason = runner(log, device).verifyReplayable(log.select())

    assertNotNull(reason)
    assertTrue(reason.contains("step 2: the recorded key 'NOT_A_KEY'"), reason)
    assertEquals(emptyList<String>(), device.commands.map { it.describeForTest() })
  }

  @Test
  fun `a screen that can never be read while waiting is a device failure`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(
          number = 1,
          target = Attributes(text = "Sign in"),
          events = listOf(ArbigentDeviceEvent.TapElement(textRegex = "Sign in", timestamp = 2)),
        ),
      ),
    )
    // Every read throws, so nothing is known about the screen: that is a broken device, not the
    // recorded target being absent.
    val device = FakeReplayDevice(listOf(screen()), failElementsAfter = 0)

    val exitCode = runner(log, device).run(log.select())

    assertEquals(ArbigentReplayRunner.EXIT_DEVICE, exitCode)
    assertEquals(emptyList<String>(), device.commands.map { it.describeForTest() })
  }

  @Test
  fun `an end screen that appears late is waited for`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.BACK.name, timestamp = 2))),
      ),
      signature = listOf("expected-id"),
    )
    val device = FakeReplayDevice(
      listOf(
        screen(Attributes(resourceId = "somewhere-else")),
        screen(Attributes(resourceId = "somewhere-else")),
        screen(Attributes(resourceId = "expected-id")),
      ),
    )

    val exitCode = runner(log, device).run(log.select())

    assertEquals(ArbigentReplayRunner.EXIT_OK, exitCode)
  }

  @Test
  fun `a tap recorded on a bigger screen is scaled to this one`() = runTest {
    val log = readLog(
      width = 1000,
      height = 2000,
      steps = listOf(
        TestStep(number = 1, events = listOf(ArbigentDeviceEvent.Tap(500, 1000, timestamp = 2))),
      ),
    )
    val device = FakeReplayDevice(listOf(screen(width = 500, height = 1000)))

    val exitCode = runner(log, device).run(log.select())

    assertEquals(ArbigentReplayRunner.EXIT_OK, exitCode)
    assertEquals(listOf("tap 250,500"), device.commands.map { it.describeForTest() })
  }

  @Test
  fun `a tap recorded on this screen size is sent unchanged`() = runTest {
    val log = readLog(
      width = 1000,
      height = 2000,
      steps = listOf(
        TestStep(number = 1, events = listOf(ArbigentDeviceEvent.Tap(500, 1000, timestamp = 2))),
      ),
    )
    val device = FakeReplayDevice(listOf(screen(width = 1000, height = 2000)))

    runner(log, device).run(log.select())

    assertEquals(listOf("tap 500,1000"), device.commands.map { it.describeForTest() })
  }

  @Test
  fun `an end screen with none of the recorded ids diverges`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.BACK.name, timestamp = 2))),
      ),
      signature = listOf("expected-id"),
    )
    val device = FakeReplayDevice(listOf(screen(Attributes(resourceId = "somewhere-else"))))
    val err = StringBuilder()

    val exitCode = runner(log, device, err = err).run(log.select())

    assertEquals(ArbigentReplayRunner.EXIT_DIVERGED, exitCode)
    assertTrue(err.contains("none of the recorded end-screen ids"), err.toString())
  }

  @Test
  fun `a partial replay is not judged against the recorded end screen`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.BACK.name, timestamp = 2))),
        TestStep(number = 2, events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.ENTER.name, timestamp = 3))),
      ),
      signature = listOf("expected-id"),
    )
    val device = FakeReplayDevice(listOf(screen(Attributes(resourceId = "somewhere-else"))))

    val exitCode = runner(log, device).run(log.select(step = 1))

    assertEquals(ArbigentReplayRunner.EXIT_OK, exitCode)
  }

  @Test
  fun `a screen that cannot be read at the end is a device failure`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.BACK.name, timestamp = 2))),
      ),
      signature = listOf("expected-id"),
    )
    val device = FakeReplayDevice(listOf(screen()), failElementsAfter = 0)

    val exitCode = runner(log, device).run(log.select())

    assertEquals(ArbigentReplayRunner.EXIT_DEVICE, exitCode)
  }

  @Test
  fun `a step without a target waits for one of the recorded screen hints`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(
          number = 1,
          screen = listOf(Attributes(text = "Feed"), Attributes(resourceId = "list")),
          events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.BACK.name, timestamp = 2)),
        ),
      ),
    )
    val device = FakeReplayDevice(
      listOf(screen(), screen(Attributes(resourceId = "list")), screen(Attributes(resourceId = "list"))),
    )

    val exitCode = runner(log, device).run(log.select())

    assertEquals(ArbigentReplayRunner.EXIT_OK, exitCode)
    assertEquals(listOf("press BACK"), device.commands.map { it.describeForTest() })
  }

  @Test
  fun `screen hints that never appear only warn`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(
          number = 1,
          screen = listOf(Attributes(text = "Feed")),
          events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.BACK.name, timestamp = 2)),
        ),
      ),
    )
    val device = FakeReplayDevice(listOf(screen(Attributes(text = "Nothing like it"))))
    val err = StringBuilder()

    val exitCode = runner(log, device, err = err).run(log.select())

    assertEquals(ArbigentReplayRunner.EXIT_OK, exitCode)
    assertEquals(listOf("press BACK"), device.commands.map { it.describeForTest() })
    assertTrue(err.contains("none of the recorded screen hints appeared"), err.toString())
  }

  @Test
  fun `no-wait sends every step without polling the screen`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(
          number = 1,
          target = Attributes(text = "Never"),
          events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.BACK.name, timestamp = 2)),
        ),
      ),
    )
    val device = FakeReplayDevice(listOf(screen()))

    val exitCode = runner(log, device, waitForScreens = false).run(log.select())

    assertEquals(ArbigentReplayRunner.EXIT_OK, exitCode)
    assertEquals(listOf("press BACK"), device.commands.map { it.describeForTest() })
  }

  @Test
  fun `a recorded wait does not reach the device`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(
          number = 1,
          events = listOf(
            ArbigentDeviceEvent.Wait(2_000, timestamp = 2),
            ArbigentDeviceEvent.KeyPress(KeyCode.BACK.name, timestamp = 3),
          ),
        ),
      ),
    )
    val device = FakeReplayDevice(listOf(screen()))

    runner(log, device).run(log.select())

    assertEquals(listOf("press BACK"), device.commands.map { it.describeForTest() })
  }

  @Test
  fun `a step with neither a target nor a screen hint is still paced`() = runTest {
    val scheduler = testScheduler
    val log = readLog(
      steps = listOf(
        TestStep(
          number = 1,
          events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.ENTER.name, timestamp = 2)),
        ),
      ),
    )
    val device = FakeReplayDevice(listOf(screen()))
    val out = StringBuilder()
    val startedAt = scheduler.currentTime

    val exitCode = runner(log, device, out = out).run(log.select())

    assertEquals(ArbigentReplayRunner.EXIT_OK, exitCode)
    // An opaque screen records no target and no hints, and without pacing this one step would be
    // the only one sent the instant the previous action returned.
    assertEquals(0, device.elementReads, "there is nothing on the screen to poll for")
    assertTrue(
      scheduler.currentTime - startedAt >= ArbigentReplayWait.MIN_WAIT_MILLIS,
      "the step should have waited out its budget, waited ${scheduler.currentTime - startedAt}ms",
    )
    assertTrue(out.contains("nothing recorded to wait for"), out.toString())
  }

  @Test
  fun `a launch replays the permissions and keychain reset it recorded`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(
          number = 0,
          events = listOf(
            ArbigentDeviceEvent.LaunchApp(
              appId = "app.id",
              permissions = mapOf("all" to "deny"),
              clearKeychain = true,
              timestamp = 1,
            ),
          ),
        ),
        TestStep(
          number = 1,
          target = Attributes(resourceId = "home"),
          events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.ENTER.name, timestamp = 2)),
        ),
      ),
    )
    val device = FakeReplayDevice(listOf(screen(Attributes(resourceId = "home"))))

    runner(log, device).run(log.select(withInit = true))

    val launch = device.commands.first().launchAppCommand
    assertEquals(mapOf("all" to "deny"), launch?.permissions)
    assertEquals(true, launch?.clearKeychain)
  }

  @Test
  fun `a log whose setup was the whole run replays that setup`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(number = 0, events = listOf(ArbigentDeviceEvent.LaunchApp("app.id", timestamp = 1))),
      ),
    )
    val device = FakeReplayDevice(listOf(screen()))

    // The app launched straight onto the goal screen, so the run recorded setup and nothing else.
    // Its own markdown prints `replay <log> --with-init`, which has to work.
    val exitCode = runner(log, device).run(log.select(withInit = true))

    assertEquals(ArbigentReplayRunner.EXIT_OK, exitCode)
    assertEquals(listOf("launch app.id"), device.commands.map { it.describeForTest() })
    val failure = assertFailsWith<ArbigentReplayLogException> { log.select() }
    assertTrue(failure.message!!.contains("--with-init"), failure.message!!)
  }

  @Test
  fun `showing a log needs no device`() {
    val log = readLog(
      steps = listOf(
        TestStep(number = 0, events = listOf(ArbigentDeviceEvent.LaunchApp("app.id", timestamp = 1))),
        TestStep(
          number = 1,
          target = Attributes(text = "Sign in"),
          events = listOf(ArbigentDeviceEvent.TapElement(textRegex = "Sign in", timestamp = 2)),
        ),
      ),
      signature = listOf("home"),
    )
    val out = StringBuilder()

    ArbigentReplayRunner.show(log, log.select(withInit = true), out)

    val text = out.toString()
    assertTrue(text.contains("launch app.id"), text)
    assertTrue(text.contains("tap on text='Sign in'"), text)
    assertTrue(text.contains("expect: resource ids home"), text)
  }

  @Test
  fun `launch arguments reach the device with their recorded types`() = runTest {
    val log = readLog(
      steps = listOf(
        TestStep(
          number = 0,
          events = listOf(
            ArbigentDeviceEvent.LaunchApp(
              appId = "app.id",
              launchArguments = mapOf(
                "flag" to JsonPrimitive(true),
                "count" to JsonPrimitive(3),
                "name" to JsonPrimitive("x"),
              ),
              timestamp = 1,
            ),
          ),
        ),
        TestStep(
          number = 1,
          target = Attributes(resourceId = "home"),
          events = listOf(ArbigentDeviceEvent.KeyPress(KeyCode.ENTER.name, timestamp = 2)),
        ),
      ),
      signature = listOf("home"),
    )
    val device = FakeReplayDevice(listOf(screen(Attributes(resourceId = "home"))))

    val exitCode = runner(log, device).run(log.select(withInit = true))

    assertEquals(ArbigentReplayRunner.EXIT_OK, exitCode)
    // A boolean has to stay a boolean and a number a number, because an app reading an extra by
    // type does not see a quoted "true".
    val launch = device.commands.first().launchAppCommand
    assertEquals(
      mapOf("flag" to true, "count" to 3, "name" to "x"),
      launch?.launchArguments,
    )
  }

  private fun runner(
    log: ArbigentReplayLog,
    device: ArbigentDevice,
    out: Appendable = StringBuilder(),
    err: Appendable = StringBuilder(),
    waitForScreens: Boolean = true,
  ) = ArbigentReplayRunner(log, device, out, err, waitForScreens)
}

/** Element attributes a test cares about: what an identity is matched on. */
internal data class Attributes(
  val text: String? = null,
  val resourceId: String? = null,
  val accessibilityId: String? = null,
)

internal data class TestStep(
  val number: Int,
  val target: Attributes? = null,
  val screen: List<Attributes> = emptyList(),
  val events: List<ArbigentDeviceEvent> = emptyList(),
) {
  val isInit: Boolean get() = number == 0
}

/**
 * A device whose screens are scripted: [screens] is consumed one entry per read, and the last entry
 * repeats, so a test says "absent, absent, then there" without counting the runner's polls.
 */
internal class FakeReplayDevice(
  private val screens: List<ArbigentElementList>,
  private val failExecuteWith: (() -> Throwable)? = null,
  /** Reads beyond this many throw, for the case where the hierarchy stops being readable. */
  private val failElementsAfter: Int? = null,
  private val os: ArbigentDeviceOs = ArbigentDeviceOs.Android,
) : ArbigentDevice {
  val commands: MutableList<MaestroCommand> = mutableListOf()
  var elementReads: Int = 0
    private set

  override fun executeActions(actions: List<MaestroCommand>) {
    failExecuteWith?.let { throw it() }
    commands += actions
  }

  override fun elements(): ArbigentElementList {
    elementReads++
    failElementsAfter?.let { limit ->
      if (elementReads > limit) throw java.io.IOException("the hierarchy cannot be read")
    }
    return screens[minOf(elementReads - 1, screens.size - 1)]
  }

  override fun os(): ArbigentDeviceOs = os
  override fun viewTreeString(): ArbigentUiTreeStrings = ArbigentUiTreeStrings("", "")
  override fun focusedTreeString(): String = ""
  override fun waitForAppToSettle(appId: String?) = Unit
  override fun close() = Unit
  override fun isClosed(): Boolean = false
}

internal fun screen(
  vararg elements: Attributes,
  width: Int = 1000,
  height: Int = 2000,
): ArbigentElementList = ArbigentElementList(
  elements = elements.mapIndexed { index, attributes ->
    ArbigentElement(
      index = index,
      textForAI = attributes.text.orEmpty(),
      rawText = attributes.text.orEmpty(),
      identifierData = ArbigentElement.IdentifierData(emptyList(), index),
      treeNode = TreeNode(
        attributes = mutableMapOf<String, String>().apply {
          attributes.text?.let { put("text", it) }
          attributes.resourceId?.let { put("resource-id", it) }
          attributes.accessibilityId?.let { put("content-desc", it) }
        },
      ),
      x = 0,
      y = index * 100,
      width = 100,
      height = 100,
      isVisible = true,
    )
  },
  screenWidth = width,
  screenHeight = height,
)

private val testJson = Json { encodeDefaults = true }

/**
 * The jsonl a recorded run would have written for [steps], read back through the real reader, so a
 * test exercises the same parsing a replay does.
 */
internal fun readLog(
  scenarioId: String = "scenario",
  platform: String = "android",
  schemaVersion: Int = 1,
  goal: String = "reach the screen",
  width: Int? = null,
  height: Int? = null,
  signature: List<String> = emptyList(),
  steps: List<TestStep>,
  status: String = "success",
  includeEnd: Boolean = true,
): ArbigentReplayLog = ArbigentReplayLog.parse(
  replayLogText(scenarioId, platform, schemaVersion, goal, width, height, signature, steps, status, includeEnd),
  "test.jsonl",
)

internal fun replayLogText(
  scenarioId: String = "scenario",
  platform: String = "android",
  schemaVersion: Int = 1,
  goal: String = "reach the screen",
  width: Int? = null,
  height: Int? = null,
  signature: List<String> = emptyList(),
  steps: List<TestStep> = emptyList(),
  status: String = "success",
  includeEnd: Boolean = true,
): String {
  val lines = mutableListOf<String>()
  lines += buildJsonObject {
    put("type", "scenario_start")
    put("task", scenarioId)
    put("taskIndex", 0)
    put("step", 0)
    put("ts", 1)
    put("schemaVersion", schemaVersion)
    put("platform", platform)
    put("goal", goal)
    width?.let { put("width", it) }
    height?.let { put("height", it) }
  }.toString()
  steps.forEach { step ->
    if (!step.isInit) {
      lines += buildJsonObject {
        put("type", "decision")
        put("task", scenarioId)
        put("taskIndex", 0)
        put("step", step.number)
        put("ts", step.events.firstOrNull()?.timestamp ?: 1L)
        put("action", "Click")
        put("log", "step ${step.number}")
        put("goal", goal)
        if (step.screen.isNotEmpty()) {
          put(
            "screen",
            kotlinx.serialization.json.buildJsonArray {
              step.screen.forEach { hint -> add(hint.toJsonObject()) }
            },
          )
        }
      }.toString()
      step.target?.let { target ->
        lines += buildJsonObject {
          put("type", "target")
          put("task", scenarioId)
          put("taskIndex", 0)
          put("step", step.number)
          put("ts", step.events.firstOrNull()?.timestamp ?: 1L)
          target.text?.let { put("text", it) }
          target.resourceId?.let { put("resourceId", it) }
          target.accessibilityId?.let { put("accessibilityId", it) }
          put("occurrence", 0)
        }.toString()
      }
    }
    step.events.forEach { event ->
      lines += buildJsonObject {
        put("type", if (step.isInit) "init" else "device")
        put("task", scenarioId)
        put("taskIndex", 0)
        put("step", step.number)
        put("ts", event.timestamp)
        put("event", testJson.encodeToJsonElement(ArbigentDeviceEvent.serializer(), event))
      }.toString()
    }
  }
  if (includeEnd) {
    lines += buildJsonObject {
      put("type", "scenario_end")
      put("task", scenarioId)
      put("taskIndex", 0)
      put("step", steps.lastOrNull()?.number ?: 0)
      put("ts", 99)
      put("status", status)
      put(
        "signature",
        kotlinx.serialization.json.buildJsonArray { signature.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } },
      )
    }.toString()
  }
  return lines.joinToString(separator = "\n", postfix = "\n")
}

private fun Attributes.toJsonObject() = buildJsonObject {
  text?.let { put("text", it) }
  resourceId?.let { put("resourceId", it) }
  accessibilityId?.let { put("accessibilityId", it) }
}

/** What a command does, in the shortest form a test can assert on. */
internal fun MaestroCommand.describeForTest(): String = when {
  launchAppCommand != null -> "launch ${launchAppCommand!!.appId}"
  stopAppCommand != null -> "stop ${stopAppCommand!!.appId}"
  clearStateCommand != null -> "clearState ${clearStateCommand!!.appId}"
  openLinkCommand != null -> "openLink ${openLinkCommand!!.link}"
  tapOnElement != null -> "tap ${tapOnElement!!.selector.textRegex ?: tapOnElement!!.selector.idRegex}"
  tapOnPointV2Command != null -> "tap ${tapOnPointV2Command!!.point}"
  pressKeyCommand != null -> "press ${pressKeyCommand!!.code.name}"
  inputTextCommand != null -> "inputText ${inputTextCommand!!.text}"
  swipeCommand != null -> "swipe ${swipeCommand!!.startPoint} -> ${swipeCommand!!.endPoint}"
  else -> toString()
}

/** Assertions about which logs the reader refuses, and why. */
class ArbigentReplayLogTest {
  @Test
  fun `a log that does not end with a successful run is refused`() {
    val text = replayLogText(
      steps = listOf(TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 2)))),
      includeEnd = false,
    )

    assertRefused(text, "does not end with a successful scenario_end")
  }

  @Test
  fun `a failed run is refused`() {
    val text = replayLogText(
      steps = listOf(TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 2)))),
      status = "failed",
    )

    assertRefused(text, "does not end with a successful scenario_end")
  }

  @Test
  fun `two runs in one file are refused`() {
    val single = replayLogText(
      steps = listOf(TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 2)))),
    )

    assertRefused(single + single, "more than one scenario_start line")
  }

  @Test
  fun `a file mixing two scenarios is refused`() {
    val text = replayLogText(
      steps = listOf(TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 2)))),
    ).replace("\"task\":\"scenario\",\"taskIndex\":0,\"step\":1", "\"task\":\"other\",\"taskIndex\":0,\"step\":1")

    assertRefused(text, "mixes lines from scenarios")
  }

  @Test
  fun `a log written by a newer schema is refused`() {
    val text = replayLogText(
      schemaVersion = 99,
      steps = listOf(TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 2)))),
    )

    assertRefused(text, "declares schema version 99")
  }

  @Test
  fun `a log without a platform is refused`() {
    val text = replayLogText(
      steps = listOf(TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 2)))),
    ).replace("\"platform\":\"android\",", "")

    assertRefused(text, "does not declare a platform")
  }

  @Test
  fun `an inverted range is refused`() {
    val log = readLog(
      steps = listOf(
        TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 2))),
        TestStep(number = 2, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 3))),
      ),
    )

    val failure = runCatching { log.select(from = 2, until = 1) }.exceptionOrNull()

    assertTrue(failure is ArbigentReplayLogException, "$failure")
    assertTrue(failure.message!!.contains("--from 2 is after --until 1"), failure.message!!)
  }

  @Test
  fun `a range that selects only setup is refused`() {
    val log = readLog(steps = threeStepsWithTwoSetups())

    val failure = runCatching { log.select(step = 9, withInit = true) }.exceptionOrNull()

    assertTrue(failure is ArbigentReplayLogException, "$failure")
    assertTrue(failure.message!!.contains("--step 9 selects no step"), failure.message!!)
    assertTrue(failure.message!!.contains("steps run 1..3"), failure.message!!)
  }

  @Test
  fun `a field of the wrong shape is reported as an unreadable log`() {
    val text = replayLogText(
      signature = listOf("expected-id"),
      steps = listOf(TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 2)))),
    ).replace("\"signature\":[\"expected-id\"]", "\"signature\":{}")

    assertRefused(text, "is not a replay log this arbigent can read")
  }

  @Test
  fun `a device record without an event is refused`() {
    val lines = replayLogText(
      steps = listOf(
        TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 2))),
        TestStep(number = 2, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 3))),
      ),
    ).trim().lines().toMutableList()
    val deviceLine = lines.indexOfLast { it.contains("\"type\":\"device\"") }
    lines[deviceLine] = lines[deviceLine].substringBefore(",\"event\":") + "}"

    // Replaying the surrounding steps and reporting success would leave a hole in the middle of
    // the run, so the log is refused before anything reaches the device.
    assertRefused(lines.joinToString(separator = "\n", postfix = "\n"), "step 2 has a device record without an event")
  }

  @Test
  fun `a step number below one is refused`() {
    val log = readLog(
      steps = listOf(TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 2)))),
    )

    val failure = runCatching { log.select(step = 0) }.exceptionOrNull()

    assertTrue(failure is ArbigentReplayLogException, "$failure")
  }

  @Test
  fun `a single step is selected with the setup that prepared it`() {
    val log = readLog(steps = threeStepsWithTwoSetups())

    val selected = log.select(step = 2, withInit = true)

    assertEquals(listOf(0, 0, 2), selected.map { it.number })
  }

  @Test
  fun `a single step without with-init leaves the setup out`() {
    val log = readLog(steps = threeStepsWithTwoSetups())

    val selected = log.select(step = 2)

    assertEquals(listOf(2), selected.map { it.number })
  }

  @Test
  fun `a from range keeps the setup that preceded the first step it replays`() {
    val log = readLog(steps = threeStepsWithTwoSetups())

    val selected = log.select(from = 3, withInit = true)

    assertEquals(listOf(0, 3), selected.map { it.number })
  }

  /**
   * A run whose second task relaunched the app: two setup blocks, the second in the middle. The
   * middle block belongs to step 2, which is the step it prepared.
   */
  private fun threeStepsWithTwoSetups(): List<TestStep> = listOf(
    TestStep(number = 0, events = listOf(ArbigentDeviceEvent.LaunchApp("app.id", timestamp = 1))),
    TestStep(number = 1, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 2))),
    TestStep(number = 0, events = listOf(ArbigentDeviceEvent.LaunchApp("app.id", timestamp = 3))),
    TestStep(number = 2, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 4))),
    TestStep(number = 3, events = listOf(ArbigentDeviceEvent.KeyPress("BACK", timestamp = 5))),
  )

  private fun assertRefused(text: String, expectedMessage: String) {
    val failure = runCatching { ArbigentReplayLog.parse(text, "test.jsonl") }.exceptionOrNull()
    assertTrue(failure is ArbigentReplayLogException, "expected a refusal but got $failure")
    assertTrue(
      failure.message!!.contains(expectedMessage),
      "expected '$expectedMessage' in: ${failure.message}",
    )
  }
}
