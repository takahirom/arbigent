package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.ArbigentAgent
import io.github.takahirom.arbigent.ArbigentAi
import io.github.takahirom.arbigent.ArbigentContextHolder
import io.github.takahirom.arbigent.ArbigentDeviceEvent
import io.github.takahirom.arbigent.ArbigentDeviceOs
import io.github.takahirom.arbigent.ArbigentElement
import io.github.takahirom.arbigent.ArbigentElementIdentity
import io.github.takahirom.arbigent.ArbigentExecuteActionsInterceptor
import io.github.takahirom.arbigent.ArbigentReplayLog
import io.github.takahirom.arbigent.ArbigentReplayScriptRecorder
import io.github.takahirom.arbigent.ArbigentReplayScriptWriter
import io.github.takahirom.arbigent.GoalAchievedAgentAction
import io.github.takahirom.arbigent.MCPClient
import io.github.takahirom.arbigent.toArbigentDeviceEvents
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun executeActionsInput(
  contextHolder: ArbigentContextHolder,
  target: ArbigentElementIdentity?,
): ArbigentAgent.ExecuteActionsInput {
  val step = ArbigentContextHolder.Step(
    stepId = "step",
    agentAction = GoalAchievedAgentAction(),
    memo = "remembered something",
    cacheKey = "cache",
    screenshotFilePath = "/screenshots/step-1.png",
    targetElement = target,
  )
  return ArbigentAgent.ExecuteActionsInput(
    stepId = "step",
    decisionOutput = ArbigentAi.DecisionOutput(listOf(GoalAchievedAgentAction()), step),
    arbigentContextHolder = contextHolder,
    screenshotFilePath = "/screenshots/step-1.png",
    device = FakeDevice(),
    cacheKey = "cache",
    elements = FakeDevice().elements(),
    mcpClient = MCPClient(),
  )
}

class ArbigentReplayScriptRecorderTest {
  private val target = ArbigentElementIdentity(text = "Settings", occurrence = 0)

  @Test
  fun `events sent outside a step are attributed to the task's init phase`() = runTest {
    val recorder = ArbigentReplayScriptRecorder()
    recorder.beginTask(taskIndex = 0, goal = "Open settings", discardPrevious = true)
    recorder.onDeviceEvent(ArbigentDeviceEvent.LaunchApp("com.example.app", clearState = true, timestamp = 1))

    val task = recorder.recordedTasks().single()
    assertEquals("Open settings", task.goal)
    val initStep = task.steps.single()
    assertTrue(initStep.isInit)
    assertEquals(1, initStep.events.size)
  }

  @Test
  fun `events sent while a step runs are attributed to that step`() = runTest {
    val recorder = ArbigentReplayScriptRecorder()
    recorder.beginTask(taskIndex = 0, goal = "Open settings", discardPrevious = true)
    recorder.onDeviceEvent(ArbigentDeviceEvent.LaunchApp("com.example.app", timestamp = 1))

    val contextHolder = ArbigentContextHolder("Open settings", 10)
    recorder.intercept(
      executeActionsInput(contextHolder, target),
      ArbigentExecuteActionsInterceptor.Chain {
        recorder.onDeviceEvent(ArbigentDeviceEvent.KeyPress("KEYCODE_DPAD_DOWN", timestamp = 2))
        recorder.onDeviceEvent(ArbigentDeviceEvent.KeyPress("KEYCODE_DPAD_CENTER", timestamp = 3))
        ArbigentAgent.ExecuteActionsOutput()
      },
    )
    // Anything after the chain returns belongs to the task again, not to the step that just ended.
    recorder.onDeviceEvent(ArbigentDeviceEvent.Wait(100, timestamp = 4))

    val steps = recorder.recordedTasks().single().steps
    assertEquals(listOf(true, false, true), steps.map { it.isInit })
    assertEquals(1, steps[0].events.size)
    assertEquals(2, steps[1].events.size)
    assertEquals(target, steps[1].target)
    assertEquals("remembered something", steps[1].memo)
    assertEquals("step-1.png", steps[1].screenshot)
    assertEquals(1, steps[2].events.size)
  }

  @Test
  fun `a task that reruns from the same screen discards what it recorded before`() = runTest {
    val recorder = ArbigentReplayScriptRecorder()
    recorder.beginTask(taskIndex = 0, goal = "Open settings", discardPrevious = true)
    recorder.onDeviceEvent(ArbigentDeviceEvent.KeyPress("KEYCODE_DPAD_DOWN", timestamp = 1))
    recorder.beginTask(taskIndex = 0, goal = "Open settings", discardPrevious = true)
    recorder.onDeviceEvent(ArbigentDeviceEvent.KeyPress("KEYCODE_DPAD_UP", timestamp = 2))

    val events = recorder.recordedTasks().single().steps.flatMap { it.events }
    assertEquals(listOf(ArbigentDeviceEvent.KeyPress("KEYCODE_DPAD_UP", 2)), events)
  }

  @Test
  fun `a task that carries on keeps what it already did in front of what it does next`() = runTest {
    val recorder = ArbigentReplayScriptRecorder()
    recorder.beginTask(taskIndex = 0, goal = "Open settings", discardPrevious = true)
    recorder.onDeviceEvent(ArbigentDeviceEvent.KeyPress("KEYCODE_DPAD_DOWN", timestamp = 1))
    recorder.beginTask(taskIndex = 0, goal = "Open settings", discardPrevious = false)
    recorder.onDeviceEvent(ArbigentDeviceEvent.KeyPress("KEYCODE_DPAD_UP", timestamp = 2))

    val events = recorder.recordedTasks().single().steps.flatMap { it.events }
    assertEquals(
      listOf(
        ArbigentDeviceEvent.KeyPress("KEYCODE_DPAD_DOWN", 1),
        ArbigentDeviceEvent.KeyPress("KEYCODE_DPAD_UP", 2),
      ),
      events,
    )
  }

  @Test
  fun `a step records where its target was and the screen it was on`() = runTest {
    val recorder = ArbigentReplayScriptRecorder()
    recorder.beginTask(taskIndex = 0, goal = "Open settings", discardPrevious = true)
    val contextHolder = ArbigentContextHolder("Open settings", 10)
    // The fake elements all carry text="text", so occurrence 2 picks a known one.
    val resolvable = ArbigentElementIdentity(text = "text", occurrence = 2)
    recorder.intercept(
      executeActionsInput(contextHolder, resolvable),
      ArbigentExecuteActionsInterceptor.Chain {
        recorder.onDeviceEvent(ArbigentDeviceEvent.KeyPress("KEYCODE_DPAD_CENTER", timestamp = 2))
        ArbigentAgent.ExecuteActionsOutput()
      },
    )

    val step = recorder.recordedTasks().single().steps.single()
    assertEquals(
      ArbigentReplayScriptRecorder.RecordedBounds(0, 0, 100, 100),
      step.targetBounds,
    )
    assertEquals(50, step.targetBounds!!.centerX)
    assertEquals(50, step.targetBounds!!.centerY)
    assertEquals(1000 to 2000, recorder.screenSize())
    assertTrue(step.screen.isNotEmpty(), "the step should remember what screen it was decided on")
    assertTrue(step.screen.size <= ArbigentReplayScriptRecorder.MaxScreenIdentities)
    assertTrue(step.screen.all { it.occurrence == 0 }, "screen hints are about presence, not position")
  }

  @Test
  fun `screen hints put labelled elements before bare containers`() {
    val root = element(resourceId = "com.example.app:id/root_layout")
    val title = element(text = "Settings")
    val identities = ArbigentReplayScriptRecorder.screenIdentities(listOf(root, title, root))
    assertEquals(listOf("Settings", null), identities.map { it.text })
    assertEquals(listOf(null, "com.example.app:id/root_layout"), identities.map { it.resourceId })
  }

  private fun element(text: String? = null, resourceId: String? = null): ArbigentElement {
    val attributes = buildMap {
      put("text", text.orEmpty())
      resourceId?.let { put("resource-id", it) }
    }
    return ArbigentElement(
      index = 0,
      textForAI = text.orEmpty(),
      rawText = text.orEmpty(),
      identifierData = ArbigentElement.IdentifierData(emptyList(), 0),
      treeNode = maestro.TreeNode(attributes = attributes.toMutableMap(), children = emptyList()),
      x = 0,
      y = 0,
      width = 10,
      height = 10,
      isVisible = true,
    )
  }

  @Test
  fun `restarting the scenario forgets everything`() = runTest {
    val recorder = ArbigentReplayScriptRecorder()
    recorder.beginTask(taskIndex = 0, goal = "Open settings", discardPrevious = true)
    recorder.onDeviceEvent(ArbigentDeviceEvent.KeyPress("KEYCODE_DPAD_DOWN", timestamp = 1))
    recorder.reset()
    assertEquals(emptyList(), recorder.recordedTasks())
  }
}

class ArbigentReplayScriptWriterTest {
  private suspend fun recordedRunWithTarget(): List<ArbigentReplayScriptRecorder.RecordedTask> {
    val recorder = ArbigentReplayScriptRecorder()
    recorder.beginTask(taskIndex = 0, goal = "Open the settings screen", discardPrevious = true)
    recorder.intercept(
      executeActionsInput(
        ArbigentContextHolder("Open the settings screen", 10),
        ArbigentElementIdentity(text = "text", occurrence = 2),
      ),
      ArbigentExecuteActionsInterceptor.Chain {
        recorder.onDeviceEvent(ArbigentDeviceEvent.KeyPress("KEYCODE_DPAD_CENTER", timestamp = 2))
        ArbigentAgent.ExecuteActionsOutput()
      },
    )
    // A second numbered step: with only one, a renderer that names every step "1", or that stops
    // after the first, would still pass.
    recorder.intercept(
      executeActionsInput(
        ArbigentContextHolder("Open the settings screen", 10),
        ArbigentElementIdentity(text = "text", occurrence = 2),
      ),
      ArbigentExecuteActionsInterceptor.Chain {
        recorder.onDeviceEvent(ArbigentDeviceEvent.KeyPress("KEYCODE_BACK", timestamp = 3))
        ArbigentAgent.ExecuteActionsOutput()
      },
    )
    return recorder.recordedTasks()
  }

  @Test
  fun `the log carries the screen size and the target geometry`() = runTest {
    val dir = Files.createTempDirectory("replay-scripts-bounds").toFile()
    ArbigentReplayScriptWriter(dir).write(
      scenarioId = "open-settings",
      goals = listOf("Open the settings screen"),
      tasks = recordedRunWithTarget(),
      signature = emptyList(),
      platform = ArbigentDeviceOs.Android,
      screenWidth = 1000,
      screenHeight = 2000,
    )
    val log = File(dir, "open-settings.jsonl").readText()
    assertTrue(log.contains("\"width\":1000"), log)
    assertTrue(log.contains("\"height\":2000"), log)
    assertTrue(log.contains("\"bounds\":\"[0,0][100,100]\""), log)
    assertTrue(log.contains("\"center\":{\"x\":50,\"y\":50}"), log)
    assertTrue(log.contains("\"screen\":[{\"text\":\"text\""), log)

    val markdown = File(dir, "open-settings.md").readText()
    assertTrue(markdown.contains("center: (50, 50)"), markdown)
  }

  @Test
  fun `the log declares its schema version and platform`() = runTest {
    val dir = Files.createTempDirectory("replay-scripts-platform").toFile()
    ArbigentReplayScriptWriter(dir).write(
      scenarioId = "open-settings",
      goals = listOf("Open the settings screen"),
      tasks = recordedRun(),
      signature = emptyList(),
      platform = ArbigentDeviceOs.Ios,
    )
    val start = File(dir, "open-settings.jsonl").readLines().first()
    assertTrue(start.contains("\"schemaVersion\":1"), start)
    assertTrue(start.contains("\"platform\":\"ios\""), start)
  }

  @Test
  fun `a screen size the device never reported is left out`() = runTest {
    val dir = Files.createTempDirectory("replay-scripts-nosize").toFile()
    ArbigentReplayScriptWriter(dir).write(
      scenarioId = "open-settings",
      goals = listOf("Open the settings screen"),
      tasks = recordedRun(),
      signature = emptyList(),
      platform = ArbigentDeviceOs.Android,
    )
    val start = File(dir, "open-settings.jsonl").readLines().first()
    assertTrue(!start.contains("\"width\""), start)
  }

  private fun recordedRun(): List<ArbigentReplayScriptRecorder.RecordedTask> {
    val recorder = ArbigentReplayScriptRecorder()
    recorder.beginTask(taskIndex = 0, goal = "Open the settings screen", discardPrevious = true)
    recorder.onDeviceEvent(ArbigentDeviceEvent.LaunchApp("com.example.app", clearState = true, timestamp = 1))
    return recorder.recordedTasks()
  }

  @Test
  fun `writes the log and the summary, with a file-safe name`() {
    val dir = Files.createTempDirectory("replay-scripts").toFile()
    ArbigentReplayScriptWriter(dir).write(
      scenarioId = "open settings/main",
      goals = listOf("Open the settings screen"),
      tasks = recordedRun(),
      signature = listOf("com.example.app:id/settings_title"),
      platform = ArbigentDeviceOs.Android,
    )

    val log = dir.listFiles().orEmpty().single { it.name.endsWith(".jsonl") }
    assertTrue(
      log.name.matches(Regex("open_settings_main-[0-9a-f]{6}\\.jsonl")),
      "a sanitized name carries a hash so 'open settings/main' and 'open settings main' do not collide: ${log.name}",
    )
    assertEquals("open-settings", ArbigentReplayScriptWriter.fileBaseName("open-settings"))
    assertTrue(
      ArbigentReplayScriptWriter.fileBaseName("a/b") != ArbigentReplayScriptWriter.fileBaseName("a b"),
    )
    assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") }, "temp file left behind")
    assertTrue(File(dir, log.name.removeSuffix(".jsonl") + ".md").isFile)

    val lines = log.readLines().filter { it.isNotBlank() }
    assertEquals("scenario_start", lineType(lines.first()))
    assertEquals("scenario_end", lineType(lines.last()))
    assertTrue(lines.any { lineType(it) == "init" })
    assertTrue(lines.all { it.contains("\"taskIndex\"") && it.contains("\"step\"") && it.contains("\"ts\"") })
    assertTrue(log.readText().contains("com.example.app"))
    assertEquals(
      "_flag_like", ArbigentReplayScriptWriter.sanitizeFileName("-flag like"),
      "a name starting with a dash would be read as an option by the runner",
    )
  }

  @Test
  fun `a write that cannot finish leaves no staging file behind`() {
    val dir = Files.createTempDirectory("replay-scripts-torn").toFile()
    // A non-empty directory in the target's place makes both the atomic and the plain move fail
    // (an empty one would be replaced).
    val target = File(dir, "blocked.jsonl").apply { mkdirs() }
    File(target, "inner.txt").writeText("occupied")
    val failed = runCatching { ArbigentReplayScriptWriter.writeAtomically(target, "content") }
    assertTrue(failed.isFailure, "replacing a directory should not succeed")
    assertEquals(listOf("blocked.jsonl"), dir.list().orEmpty().toList(), "the staging file must be cleaned up")
    // The atomic attempt is what a reader of the stack trace needs to understand the fallback.
    assertTrue(
      failed.exceptionOrNull()?.suppressed?.isNotEmpty() == true,
      "the atomic move failure must survive as a suppressed exception",
    )
  }

  @Test
  fun `a log that cannot be published leaves the previous pair of artifacts alone`() {
    val dir = Files.createTempDirectory("replay-scripts-partial").toFile()
    val markdown = File(dir, "open-settings.md").apply { writeText("previous summary") }
    // A non-empty directory in the log's place makes both the atomic and the plain move fail.
    File(dir, "open-settings.jsonl").apply { mkdirs() }.let { File(it, "inner.txt").writeText("occupied") }

    val failed = runCatching {
      ArbigentReplayScriptWriter(dir).write(
        scenarioId = "open-settings",
        goals = listOf("Open the settings screen"),
        tasks = recordedRun(),
        signature = emptyList(),
        platform = ArbigentDeviceOs.Android,
      )
    }

    assertTrue(failed.isFailure, "replacing a directory should not succeed")
    // The summary must not advertise a run whose log was never written, and no staging file may
    // be left beside the artifacts CI uploads.
    assertEquals("previous summary", markdown.readText())
    assertEquals(
      listOf("open-settings.jsonl", "open-settings.md"),
      dir.list().orEmpty().sorted(),
    )
  }

  @Test
  fun `a run that sent nothing writes no files`() {
    val dir = Files.createTempDirectory("replay-scripts").toFile()
    ArbigentReplayScriptWriter(dir).write(
      scenarioId = "empty",
      goals = listOf("Nothing happened"),
      tasks = listOf(ArbigentReplayScriptRecorder.RecordedTask(0, "Nothing happened")),
      signature = emptyList(),
      platform = ArbigentDeviceOs.Android,
    )
    assertEquals(emptyList(), dir.listFiles()?.toList().orEmpty())
  }

  @Test
  fun `the markdown names the steps, the target and the way to replay them`() {
    val dir = Files.createTempDirectory("replay-scripts").toFile()
    ArbigentReplayScriptWriter(dir).write(
      scenarioId = "open-settings",
      goals = listOf("Open the settings screen"),
      tasks = recordedRun(),
      signature = listOf("com.example.app:id/settings_title"),
      platform = ArbigentDeviceOs.Android,
    )
    val markdown = File(dir, "open-settings.md").readText()
    assertTrue(markdown.startsWith("# open-settings"))
    assertTrue(markdown.contains("launch(com.example.app, clearState)"))
    assertTrue(markdown.contains("./arbigentw replay open-settings.jsonl --with-init"))
    assertTrue(!markdown.contains("--step 0"), "setup is not a numbered step and has no single-step command")
    assertTrue(
      markdown.indexOf("--with-init") < markdown.indexOf("./arbigentw replay open-settings.jsonl\n"),
      "the command that replays the setup too comes first, since that is the one a fresh device needs",
    )
    assertTrue(markdown.contains("com.example.app:id/settings_title"))
  }

  @Test
  fun `the markdown names the permissions and keychain reset a launch asked for`() {
    val dir = Files.createTempDirectory("replay-scripts-permissions").toFile()
    val recorder = ArbigentReplayScriptRecorder()
    recorder.beginTask(taskIndex = 0, goal = "Open the settings screen", discardPrevious = true)
    recorder.onDeviceEvent(
      ArbigentDeviceEvent.LaunchApp(
        "com.example.app",
        permissions = mapOf("all" to "deny"),
        clearKeychain = true,
        timestamp = 1,
      ),
    )
    ArbigentReplayScriptWriter(dir).write(
      scenarioId = "open-settings",
      goals = listOf("Open the settings screen"),
      tasks = recorder.recordedTasks(),
      signature = emptyList(),
      platform = ArbigentDeviceOs.Android,
    )

    val markdown = File(dir, "open-settings.md").readText()
    assertTrue(
      markdown.contains("launch(com.example.app, clearKeychain, all=deny)"),
      "a launch that denies a permission starts the app in a different state, so the summary has " +
        "to say so: $markdown",
    )
  }

  @Test
  fun `each numbered step in the markdown carries its own single-step replay command`() = runTest {
    val dir = Files.createTempDirectory("replay-scripts-step-command").toFile()
    ArbigentReplayScriptWriter(dir).write(
      scenarioId = "open-settings",
      goals = listOf("Open the settings screen"),
      tasks = recordedRunWithTarget(),
      signature = emptyList(),
      platform = ArbigentDeviceOs.Android,
    )
    val markdown = File(dir, "open-settings.md").readText()
    // Each step's own block, so a command printed under the wrong step is caught: searching the
    // whole document would pass just as happily with the two numbers swapped.
    val blocks = markdown.split("\n\n").associateBy { it.trimStart().substringBefore(". ") }
    listOf(
      "device: KEYCODE_DPAD_CENTER" to 1,
      "device: KEYCODE_BACK" to 2,
    ).forEach { (command, step) ->
      val block = blocks["$step"]
      assertTrue(block != null, "step $step is missing from the summary:\n$markdown")
      assertTrue(block!!.contains(command), "step $step acted on something else:\n$block")
      assertTrue(
        block.contains("- replay: `./arbigentw replay open-settings.jsonl --step $step`"),
        "an agent driving one step at a time copies this instead of working the number out:\n$block",
      )
    }
  }

  @Test
  fun `typed text with its own newline stays on the event's line`() = runTest {
    val dir = Files.createTempDirectory("replay-scripts-typed-text").toFile()
    val recorder = ArbigentReplayScriptRecorder()
    recorder.beginTask(taskIndex = 0, goal = "Write a note", discardPrevious = true)
    recorder.intercept(
      executeActionsInput(
        ArbigentContextHolder("Write a note", 10),
        ArbigentElementIdentity(text = "note", occurrence = 1),
      ),
      ArbigentExecuteActionsInterceptor.Chain {
        recorder.onDeviceEvent(ArbigentDeviceEvent.InputText("first\nsecond \"quoted\"", timestamp = 2))
        ArbigentAgent.ExecuteActionsOutput()
      },
    )
    ArbigentReplayScriptWriter(dir).write(
      scenarioId = "write-note",
      goals = listOf("Write a note"),
      tasks = recorder.recordedTasks(),
      signature = emptyList(),
      platform = ArbigentDeviceOs.Android,
    )

    val markdown = File(dir, "write-note.md").readText()
    // The summary is read a line at a time, so a newline the app was told to type must not end the
    // event's line and leave the rest of the typed text standing as prose.
    assertTrue(
      markdown.lines().any { it.contains("""device: text("first\nsecond \"quoted\"")""") },
      "typed text has to survive as one line:\n$markdown",
    )
  }

  private fun lineType(line: String): String =
    Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(line)!!.groupValues[1]
}

/** A device that reports what it was asked to do, the way MaestroDevice does. */
private class RecordingFakeDevice(
  private val os: io.github.takahirom.arbigent.ArbigentDeviceOs = io.github.takahirom.arbigent.ArbigentDeviceOs.Android,
  private var refusedRegistrations: Int = 0,
) : io.github.takahirom.arbigent.ArbigentDevice by FakeDevice() {
  private val listeners = mutableListOf<io.github.takahirom.arbigent.ArbigentDeviceEventListener>()
  private val delegate = FakeDevice()

  override fun os(): io.github.takahirom.arbigent.ArbigentDeviceOs = os

  override fun addDeviceEventListener(listener: io.github.takahirom.arbigent.ArbigentDeviceEventListener) {
    if (refusedRegistrations > 0) {
      refusedRegistrations--
      throw IllegalStateException("device refused the listener")
    }
    if (!listeners.contains(listener)) listeners.add(listener)
  }

  override fun removeDeviceEventListener(listener: io.github.takahirom.arbigent.ArbigentDeviceEventListener) {
    listeners.remove(listener)
  }

  override fun recordDeviceEvent(event: ArbigentDeviceEvent) {
    listeners.toList().forEach { it.onDeviceEvent(event) }
  }

  override fun executeActions(actions: List<maestro.orchestra.MaestroCommand>) {
    delegate.executeActions(actions)
    actions.forEach { command ->
      command.toArbigentDeviceEvents(1080, 1920, os()).forEach { event ->
        listeners.toList().forEach { it.onDeviceEvent(event) }
      }
    }
  }
}

class ArbigentReplayScriptExecutorTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `a successful scenario writes its replay script`() = runTest {
    val dispatcher = coroutineContext[kotlinx.coroutines.CoroutineDispatcher]!!
    val dir = Files.createTempDirectory("replay-scripts-success").toFile()
    val agentConfig = io.github.takahirom.arbigent.AgentConfig {
      deviceFactory { RecordingFakeDevice() }
      aiFactory { FakeAi() }
    }
    io.github.takahirom.arbigent.ArbigentScenarioExecutor(dispatcher).execute(
      scenario(agentConfig, dir),
      MCPClient(),
    )
    advanceUntilIdle()

    assertTrue(File(dir, "settings-scenario.jsonl").isFile)
    assertTrue(File(dir, "settings-scenario.md").isFile)
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `a task whose events could not be recorded costs the scenario its script`() = runTest {
    val dispatcher = coroutineContext[kotlinx.coroutines.CoroutineDispatcher]!!
    val dir = Files.createTempDirectory("replay-scripts-unrecorded").toFile()
    // One device serves both tasks; it refuses the first task's listener and accepts the second's.
    val device = RecordingFakeDevice(refusedRegistrations = 1)
    val agentConfig = io.github.takahirom.arbigent.AgentConfig {
      deviceFactory { device }
      aiFactory { FakeAi() }
    }
    io.github.takahirom.arbigent.ArbigentScenarioExecutor(dispatcher).execute(
      scenario(agentConfig, dir, goals = listOf("Open the settings screen", "Open the account page")),
      MCPClient(),
    )
    advanceUntilIdle()

    // The second task recorded fine, but a log without the first task's actions would replay from a
    // screen the recording never showed how to reach.
    assertEquals(emptyList(), dir.listFiles()?.toList().orEmpty())
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `a scenario run on iOS records its platform`() = runTest {
    val dispatcher = coroutineContext[kotlinx.coroutines.CoroutineDispatcher]!!
    val dir = Files.createTempDirectory("replay-scripts-ios").toFile()
    val agentConfig = io.github.takahirom.arbigent.AgentConfig {
      deviceFactory { RecordingFakeDevice(os = io.github.takahirom.arbigent.ArbigentDeviceOs.Ios) }
      aiFactory { FakeAi() }
    }
    io.github.takahirom.arbigent.ArbigentScenarioExecutor(dispatcher).execute(
      scenario(agentConfig, dir),
      MCPClient(),
    )
    advanceUntilIdle()

    val start = File(dir, "settings-scenario.jsonl").readLines().first()
    assertTrue(start.contains("\"platform\":\"ios\""), start)
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `a failed scenario leaves the previous script untouched`() = runTest {
    val dispatcher = coroutineContext[kotlinx.coroutines.CoroutineDispatcher]!!
    val dir = Files.createTempDirectory("replay-scripts-failure").toFile()
    val existing = File(dir, "settings-scenario.jsonl")
    existing.writeText("previous run\n")

    val agentConfig = io.github.takahirom.arbigent.AgentConfig {
      deviceFactory { RecordingFakeDevice() }
      aiFactory { FakeAi().apply { status = NeverAchievesGoal() } }
    }
    runCatching {
      io.github.takahirom.arbigent.ArbigentScenarioExecutor(dispatcher).execute(
        scenario(agentConfig, dir),
        MCPClient(),
      )
    }
    advanceUntilIdle()

    assertEquals("previous run\n", existing.readText())
  }

  private fun scenario(
    agentConfig: io.github.takahirom.arbigent.AgentConfig,
    dir: File,
    goals: List<String> = listOf("Open the settings screen"),
  ) = io.github.takahirom.arbigent.ArbigentScenario(
    id = "settings-scenario",
    agentTasks = goals.mapIndexed { index, goal ->
      io.github.takahirom.arbigent.ArbigentAgentTask("task${index + 1}", goal, agentConfig)
    },
    maxStepCount = 10,
    tags = emptySet(),
    isLeaf = true,
    replayScripts = io.github.takahirom.arbigent.ReplayScriptsSettings(
      enabled = true,
      outputDir = dir.absolutePath,
    ),
  )

  private class NeverAchievesGoal : FakeAi.Status() {
    override fun decideAgentActions(
      decisionInput: ArbigentAi.DecisionInput,
    ): ArbigentAi.DecisionOutput = createDecisionOutput()
  }
}

/**
 * The recorder writes the jsonl log and `arbigent replay` reads it back, so the two sides agreeing
 * about key names and nesting is the only thing that makes a recorded run replayable at all. These
 * drive the real writer and parse its file with the real reader, so a rename on either side fails
 * here rather than on a device.
 */
class ArbigentReplayLogRoundTripTest {
  @Test
  fun `a written log reads back with its setup, target and end screen`() = runTest {
    val recorder = ArbigentReplayScriptRecorder()
    recorder.beginTask(taskIndex = 0, goal = "Open the settings screen", discardPrevious = true)
    recorder.onDeviceEvent(ArbigentDeviceEvent.LaunchApp("app.id", timestamp = 1))
    recorder.intercept(
      executeActionsInput(
        ArbigentContextHolder("Open the settings screen", 10),
        ArbigentElementIdentity(text = "text", occurrence = 2),
      ),
      ArbigentExecuteActionsInterceptor.Chain {
        recorder.onDeviceEvent(
          ArbigentDeviceEvent.TapElement(textRegex = "text", index = 2, timestamp = 2),
        )
        ArbigentAgent.ExecuteActionsOutput()
      },
    )
    recorder.intercept(
      executeActionsInput(ArbigentContextHolder("Open the settings screen", 10), null),
      ArbigentExecuteActionsInterceptor.Chain {
        recorder.onDeviceEvent(ArbigentDeviceEvent.KeyPress("KEYCODE_BACK", timestamp = 3))
        ArbigentAgent.ExecuteActionsOutput()
      },
    )
    val dir = Files.createTempDirectory("replay-round-trip").toFile()
    ArbigentReplayScriptWriter(dir).write(
      scenarioId = "open-settings",
      goals = listOf("Open the settings screen"),
      tasks = recorder.recordedTasks(),
      signature = listOf("settings_root"),
      platform = ArbigentDeviceOs.Android,
      screenWidth = 1000,
      screenHeight = 2000,
    )

    val log = ArbigentReplayLog.read(File(dir, "open-settings.jsonl"))

    assertEquals(ArbigentDeviceOs.Android, log.platform)
    assertEquals(1000, log.screenWidth)
    assertEquals(2000, log.screenHeight)
    assertEquals(listOf("settings_root"), log.signature)
    assertEquals("app.id", log.appId)
    assertEquals(2, log.lastStepNumber())

    val steps = log.select(withInit = true)
    assertEquals(listOf(0, 1, 2), steps.map { it.number })
    assertTrue(steps[0].isInit)
    assertEquals("app.id", (steps[0].events.single() as ArbigentDeviceEvent.LaunchApp).appId)
    assertEquals("text", steps[1].target?.identity?.text)
    assertEquals(2, steps[1].target?.identity?.occurrence)
    assertEquals(50, steps[1].target?.centerX)
    assertEquals(50, steps[1].target?.centerY)
    assertTrue(steps[1].screen.isNotEmpty(), "the recorded screen hints should survive the round trip")
    assertEquals("text", (steps[1].events.single() as ArbigentDeviceEvent.TapElement).textRegex)
    assertEquals("KEYCODE_BACK", (steps[2].events.single() as ArbigentDeviceEvent.KeyPress).keyName)
  }
}

/**
 * A wait sends no command, so unless the wait itself is recorded the step leaves no event and is
 * dropped from the log. The steps after it were recorded against the screen the wait produced.
 */
class ArbigentRecordedWaitTest {
  @Test
  fun `a wait the AI decided is recorded`() {
    val device = RecordingFakeDevice()
    val recorded = mutableListOf<ArbigentDeviceEvent>()
    device.addDeviceEventListener { recorded += it }

    io.github.takahirom.arbigent.WaitAgentAction(5).runDeviceAction(
      io.github.takahirom.arbigent.ArbigentAgentAction.RunInput(device, device.elements()),
    )

    assertEquals(listOf(5L), recorded.map { (it as ArbigentDeviceEvent.Wait).millis })
  }

  @Test
  fun `a wait an initializer performs is recorded`() {
    val config = io.github.takahirom.arbigent.AgentConfigBuilder(
      prompt = io.github.takahirom.arbigent.ArbigentPrompt(),
      scenarioType = io.github.takahirom.arbigent.ArbigentScenarioType.Scenario,
      deviceFormFactor = io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor.Mobile,
      initializationMethods = listOf(
        io.github.takahirom.arbigent.ArbigentScenarioContent.InitializationMethod.Wait(durationMs = 5),
      ),
      imageAssertions = io.github.takahirom.arbigent.ArbigentImageAssertions(),
      cacheOptions = io.github.takahirom.arbigent.ArbigentScenarioCacheOptions(),
    ).apply {
      deviceFactory { FakeDevice() }
      aiFactory { FakeAi() }
    }.build()
    val initializer = config.interceptors
      .filterIsInstance<io.github.takahirom.arbigent.ArbigentInitializerInterceptor>()
      .single()

    val device = RecordingFakeDevice()
    val recorded = mutableListOf<ArbigentDeviceEvent>()
    device.addDeviceEventListener { recorded += it }
    initializer.intercept(device, object : io.github.takahirom.arbigent.ArbigentInitializerInterceptor.Chain {
      override fun proceed(device: io.github.takahirom.arbigent.ArbigentDevice) {
      }
    })

    assertEquals(listOf(5L), recorded.map { (it as ArbigentDeviceEvent.Wait).millis })
  }
}
