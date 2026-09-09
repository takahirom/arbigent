package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.sample.test.FakeAi
import io.github.takahirom.arbigent.sample.test.FakeDevice
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.github.takahirom.arbigent.result.ArbigentStepSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import maestro.TreeNode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArbigentReplayTraceTest {
  @Test
  fun `fallback candidate preserves recorded prefix intervals and fresh AI intervals`() {
    val recordedSteps = timestampedSteps(listOf(1_000, 5_000, 12_000, 21_000))
    val recordedTrace = trace(recordedSteps.map { it to requireNotNull(it.agentAction) })
    val replayedSteps = recordedSteps.take(3).mapIndexed { index, step ->
      step.copy(timestamp = 100_000L + index * 100L, stepSource = ArbigentStepSource.Replay)
    }
    val aiSteps = timestampedSteps(listOf(102_200, 105_200)).mapIndexed { index, step ->
      step.copy(stepId = "ai-$index")
    }

    // Both sources of replayed steps share the recording's order, even when the prefix spans them.
    for (precedingCount in 0..replayedSteps.size) {
      val context = ArbigentContextHolder("goal", 10).apply {
        (replayedSteps.drop(precedingCount) + aiSteps).forEach(::addStep)
      }
      val candidate = ArbigentReplayTrace.candidateFrom(
        key = candidateKey(),
        contextHolder = context,
        precedingSteps = replayedSteps.take(precedingCount),
        recordedTrace = recordedTrace,
      )
      val writtenSteps = candidate.steps.map { it.decisionOutput.step }

      assertEquals(listOf(4_000L, 7_000L, 2_000L, 3_000L), writtenSteps.map { it.timestamp }.zipWithNext { a, b -> b - a })
      assertEquals(replayedSteps.last().timestamp, writtenSteps[2].timestamp)
      assertEquals(aiSteps, writtenSteps.drop(3))
      assertEquals(replayedSteps.drop(precedingCount) + aiSteps, context.steps())
    }
  }

  @Test
  fun `clean replay candidate preserves all recorded intervals`() {
    val recordedSteps = timestampedSteps(listOf(1_000, 5_000, 12_000, 21_000))
    val recordedTrace = trace(recordedSteps.map { it to requireNotNull(it.agentAction) })
    val freshSteps = recordedSteps.mapIndexed { index, step ->
      step.copy(timestamp = 100_000L + index * 100L, stepSource = ArbigentStepSource.Replay)
    }
    val context = ArbigentContextHolder("goal", 10).apply { freshSteps.forEach(::addStep) }

    val candidate = ArbigentReplayTrace.candidateFrom(candidateKey(), context, recordedTrace = recordedTrace)
    val writtenTimestamps = candidate.steps.map { it.decisionOutput.step.timestamp }

    assertEquals(listOf(4_000L, 7_000L, 9_000L), writtenTimestamps.zipWithNext { a, b -> b - a })
    assertEquals(freshSteps.last().timestamp, writtenTimestamps.last())
    assertEquals(freshSteps, context.steps())
  }

  @Test
  fun `normal candidate keeps its own timestamps`() {
    val freshSteps = timestampedSteps(listOf(100_000, 102_200, 105_200))
    val context = ArbigentContextHolder("goal", 10).apply { freshSteps.forEach(::addStep) }

    val candidate = ArbigentReplayTrace.candidateFrom(candidateKey(), context)

    assertEquals(freshSteps, candidate.steps.map { it.decisionOutput.step })
  }

  @Test
  fun `candidate rejects a replayed prefix longer than the recording`() {
    val context = ArbigentContextHolder("goal", 10).apply {
      timestampedSteps(listOf(100_000, 100_100)).forEach {
        addStep(it.copy(stepSource = ArbigentStepSource.Replay))
      }
    }

    assertFailsWith<IllegalArgumentException> {
      ArbigentReplayTrace.candidateFrom(candidateKey(), context, recordedTrace = minimalTrace(candidateKey()))
    }
  }

  @Test
  fun `candidate rejects a preceding step that did not come from replay`() {
    assertFailsWith<IllegalArgumentException> {
      ArbigentReplayTrace.candidateFrom(
        key = candidateKey(),
        contextHolder = ArbigentContextHolder("goal", 10),
        precedingSteps = timestampedSteps(listOf(100_000)),
        recordedTrace = minimalTrace(candidateKey()),
      )
    }
  }

  private fun candidateKey(): ArbigentReplayTraceKey = ArbigentReplayTraceKey(
    version = "1.2.3",
    scenarioId = "scenario",
    taskIndex = 0,
    taskIdentity = "scenario",
    goal = "goal",
    maxStep = 10,
  )

  private fun timestampedSteps(timestamps: List<Long>): List<ArbigentContextHolder.Step> {
    val step = minimalTrace(candidateKey()).steps.single().decisionOutput.step
    return timestamps.mapIndexed { index, timestamp ->
      step.copy(stepId = "step-$index", timestamp = timestamp)
    }
  }

  @Test
  fun `trace round trip preserves actions and target identity`() {
    val directory = Files.createTempDirectory("arbigent-replay-trace-test").toFile()
    val store = ArbigentReplayTraceStore { directory }
    val key = ArbigentReplayTraceKey(
      version = "1.2.3",
      scenarioId = "open-model-page",
      taskIndex = 0,
      taskIdentity = "open-model-page",
      goal = "Open the model page",
      maxStep = 10,
    )
    val action = ClickWithIndex(2)
    val trace = ArbigentReplayTrace(
      version = key.version,
      scenarioId = key.scenarioId,
      taskIndex = key.taskIndex,
      taskIdentity = key.taskIdentity,
      goalHash = key.goalHash,
      steps = listOf(
        ArbigentReplayTraceStep(
          decisionOutput = ArbigentAi.DecisionOutput(
            agentActions = listOf(action),
            step = ArbigentContextHolder.Step(
              stepId = "step-1",
              agentAction = action,
              memo = "Open the model",
              cacheKey = "cache-key",
              screenshotFilePath = "screenshot.png",
              targetElement = ArbigentElementIdentity(
                text = "Models",
                resourceId = "models_button",
                accessibilityId = "Open models",
                occurrence = 0,
              ),
            ),
          ),
        ),
        ArbigentReplayTraceStep(
          decisionOutput = ArbigentAi.DecisionOutput(
            agentActions = listOf(GoalAchievedAgentAction()),
            step = ArbigentContextHolder.Step(
              stepId = "step-2",
              agentAction = GoalAchievedAgentAction(),
              cacheKey = "goal-cache-key",
              screenshotFilePath = "goal.png",
            ),
          ),
        ),
      ),
    )

    store.write(key, trace)

    val restored = assertNotNull(store.read(key))
    assertEquals(trace.version, restored.version)
    assertEquals(trace.scenarioId, restored.scenarioId)
    assertEquals(trace.goalHash, restored.goalHash)
    assertEquals(action, restored.steps.first().decisionOutput.agentActions.single())
    assertEquals(
      trace.steps.first().decisionOutput.step.targetElement,
      restored.steps.first().decisionOutput.step.targetElement,
    )
    assertEquals("Open the model", restored.steps.first().decisionOutput.step.memo)
  }

  /**
   * A task identity is the resolved goal and hints of the task, so it is long free text, and in
   * Japanese it encodes to several bytes per character. Spelling it out in the file name produced
   * names past the 255 byte limit a file name component has, and every write failed, which left
   * replay permanently unable to find a trace.
   */
  @Test
  fun `a trace for a long non-ascii task identity can be stored and read back`() {
    val directory = Files.createTempDirectory("arbigent-replay-trace-long-name").toFile()
    val store = ArbigentReplayTraceStore { directory }
    val key = ArbigentReplayTraceKey(
      version = "1.2.3",
      // Synthetic multi-byte text: what matters here is only the encoded byte length.
      scenarioId = "\u3042".repeat(30),
      taskIndex = 3,
      taskIdentity = "\u3042".repeat(400),
      goal = "\u3042".repeat(30),
      maxStep = 10,
    )

    store.write(key, minimalTrace(key))

    assertNotNull(store.read(key), "the trace could not be read back")
    val fileName = directory.listFiles().orEmpty().single().name
    assertTrue(
      fileName.toByteArray().size <= 255,
      "file name is ${fileName.toByteArray().size} bytes, which no common filesystem accepts",
    )
  }

  @Test
  fun `traces that differ only in task index do not share a file`() {
    val directory = Files.createTempDirectory("arbigent-replay-trace-distinct").toFile()
    val store = ArbigentReplayTraceStore { directory }
    val first = ArbigentReplayTraceKey(
      version = "1.2.3",
      scenarioId = "scenario",
      taskIndex = 0,
      taskIdentity = "identity",
      goal = "Goal",
      maxStep = 10,
    )
    val second = first.copy(taskIndex = 1)

    store.write(first, minimalTrace(first))
    store.write(second, minimalTrace(second))

    assertEquals(2, directory.listFiles().orEmpty().size)
    assertEquals(0, assertNotNull(store.read(first)).taskIndex)
    assertEquals(1, assertNotNull(store.read(second)).taskIndex)
  }

  @Test
  fun `a replayed step waits until its recorded target is present`() = runTest {
    withVirtualClock {
      val absent = ArbigentElementList(emptyList(), screenWidth = 1000)
      val present = ArbigentElementList(listOf(element("target", "id", "desc")), screenWidth = 1000)
      val device = ScriptedDevice(listOf(absent, absent, present))
      var proceeded = false

      ArbigentReplayPacingStepInterceptor(traceWithTarget()).intercept(
        stepInput(device),
      ) {
        proceeded = true
        ArbigentAgent.StepResult.Continue
      }

      assertTrue(proceeded, "the step should have been captured once the target appeared")
      assertEquals(
        3,
        device.elementsCallCount,
        "the wait should end on the poll that found the target, not one later",
      )
      assertEquals(1000, currentTime, "each poll is one interval apart")
    }
  }

  @Test
  fun `a target that is present but still moving is not waited on`() = runTest {
    withVirtualClock {
      // Same text, same id, different place: the element is animating into position. Waiting for it
      // to stop was deliberately dropped — a screen with anything animating or ticking never stops
      // changing, and the recorded interval, not stability, is what bounds the wait.
      val moving = ArbigentElementList(listOf(element("target", "id", "desc", y = 100)), screenWidth = 1000)
      val stopped = ArbigentElementList(listOf(element("target", "id", "desc", y = 0)), screenWidth = 1000)
      val device = ScriptedDevice(listOf(moving, stopped, stopped))
      var proceeded = false

      ArbigentReplayPacingStepInterceptor(traceWithTarget()).intercept(stepInput(device)) {
        proceeded = true
        ArbigentAgent.StepResult.Continue
      }

      assertTrue(proceeded)
      assertEquals(1, device.elementsCallCount, "a present target is enough to capture the step")
      assertEquals(0, currentTime)
    }
  }

  @Test
  fun `a replayed step whose target never appears is captured once the deadline passes`() = runTest {
    withVirtualClock {
      val absent = ArbigentElementList(emptyList(), screenWidth = 1000)
      val device = ScriptedDevice(listOf(absent))
      var proceeded = false

      ArbigentReplayPacingStepInterceptor(traceWithTarget()).intercept(stepInput(device)) {
        proceeded = true
        ArbigentAgent.StepResult.Continue
      }

      assertTrue(proceeded, "a target that never appears is divergence to detect later, not here")
      assertEquals(
        10_000,
        currentTime,
        "with no recorded interval to derive a budget from, the wait should last the minimum",
      )
      assertTrue(device.elementsCallCount >= 2, "the budget should have been polled towards")
    }
  }

  @Test
  fun `a slow hierarchy read is charged to the wait budget`() = runTest {
    val absent = ArbigentElementList(emptyList(), screenWidth = 1000)
    val device = ScriptedDevice(listOf(absent))
    val scheduler = testScheduler
    val previous = TimeProvider.get()
    // Every read appears to take 200ms. Only this provider can express that: a read is synchronous,
    // so it cannot advance the test scheduler the way `delay` does.
    TimeProvider.set(
      object : TimeProvider {
        override fun currentTimeMillis(): Long =
          scheduler.currentTime + ReadCostMillis * device.elementsCallCount
      },
    )
    try {
      ArbigentReplayPacingStepInterceptor(traceWithTarget()).intercept(stepInput(device)) {
        ArbigentAgent.StepResult.Continue
      }
    } finally {
      TimeProvider.set(previous)
    }

    // Each poll now costs 200ms of reading plus a 500ms delay, so the 10s budget buys 15 reads and
    // 14 delays instead of 20 delays. Without charging the read the wait would have slept the whole
    // 10s and spent 20 reads on top, overrunning the interval it is meant to fit inside by 4s.
    assertEquals(15, device.elementsCallCount, "the read time should shorten the poll count")
    assertEquals(7_000, currentTime, "only the delays are time the wait actually slept")
  }

  @Test
  fun `a poll that resumes late is charged for the time it really took`() = runTest {
    val absent = ArbigentElementList(emptyList(), screenWidth = 1000)
    val device = ScriptedDevice(listOf(absent))
    val scheduler = testScheduler
    val previous = TimeProvider.get()
    // The clock runs twice as fast as the scheduler: every delay resumes as late again as it asked
    // for, the way a delay does on a loaded runtime.
    TimeProvider.set(
      object : TimeProvider {
        override fun currentTimeMillis(): Long = scheduler.currentTime * 2
      },
    )
    try {
      ArbigentReplayPacingStepInterceptor(traceWithTarget()).intercept(stepInput(device)) {
        ArbigentAgent.StepResult.Continue
      }
    } finally {
      TimeProvider.set(previous)
    }

    // The 10s budget is used up after 5s of requested delays. Summing the requested delays instead
    // would have kept polling for 10s of them, which is 20s on the clock the budget is measured on.
    assertEquals(5_000, currentTime, "the wait should stop when the clock, not the sum of delays, reaches the budget")
    assertEquals(11, device.elementsCallCount, "ten 500ms polls fit in the budget, plus the first read")
  }

  @Test
  fun `a replayed step with no recorded target keeps the recorded pace`() = runTest {
    val trace = traceWithoutTarget(firstTimestamp = 1_000, secondTimestamp = 4_000)
    val device = ScriptedDevice(listOf(ArbigentElementList(emptyList(), screenWidth = 1000)))
    val contextHolder = ArbigentContextHolder("goal", 10)
    val interceptor = ArbigentReplayPacingStepInterceptor(trace)

    withVirtualClock {
      // The first call has nothing to pace against; it is what records when replay reached step one.
      interceptor.intercept(stepInput(device, contextHolder)) { ArbigentAgent.StepResult.Continue }
      val action = GoalAchievedAgentAction()
      contextHolder.addStep(
        ArbigentContextHolder.Step(
          stepId = "step-1",
          agentAction = action,
          cacheKey = "cache-key",
          screenshotFilePath = "screenshot.png",
        ),
      )
      interceptor.intercept(stepInput(device, contextHolder)) { ArbigentAgent.StepResult.Continue }

      assertEquals(
        10_000L + 3_000L,
        currentTime,
        "the first step has no recorded interval so it waits the minimum, and the second waits " +
          "out the 3000ms recorded between them",
      )
      assertEquals(0, device.elementsCallCount, "pacing should not read the screen")
    }
  }

  @Test
  fun `a step whose budget is already spent still reads the screen once`() = runTest {
    val absent = ArbigentElementList(emptyList(), screenWidth = 1000)
    val device = ScriptedDevice(listOf(absent))
    val trace = traceWithTarget(secondTimestamp = 1_000)
    val contextHolder = ArbigentContextHolder("goal", 10)
    val interceptor = ArbigentReplayPacingStepInterceptor(trace)
    var proceeded = false

    withVirtualClock {
      interceptor.intercept(stepInput(device, contextHolder)) { ArbigentAgent.StepResult.Continue }
      val action = ClickWithTextAgentAction("target")
      contextHolder.addStep(
        ArbigentContextHolder.Step(
          stepId = "step-1",
          agentAction = action,
          cacheKey = "cache-key",
          screenshotFilePath = "screenshot.png",
        ),
      )
      // The replayed action took longer than the whole recorded interval, so nothing is left of it.
      delay(2_000)
      val readsBefore = device.elementsCallCount
      val startedAt = currentTime
      interceptor.intercept(stepInput(device, contextHolder)) {
        proceeded = true
        ArbigentAgent.StepResult.Continue
      }

      assertTrue(proceeded, "a spent budget must not stop the step from being captured")
      assertEquals(startedAt, currentTime, "there was nothing left of the interval to wait out")
      assertEquals(
        1,
        device.elementsCallCount - readsBefore,
        "the screen should be read exactly once: before the spent budget is consulted",
      )
    }
  }

  @Test
  fun `a replayed step waits out only what is left of the recorded interval`() = runTest {
    val absent = ArbigentElementList(emptyList(), screenWidth = 1000)
    val device = ScriptedDevice(listOf(absent))
    val trace = traceWithoutTarget(firstTimestamp = 1_000, secondTimestamp = 6_000)
    val contextHolder = ArbigentContextHolder("goal", 10)
    val interceptor = ArbigentReplayPacingStepInterceptor(trace)

    withVirtualClock {
      interceptor.intercept(stepInput(device, contextHolder)) { ArbigentAgent.StepResult.Continue }
      val action = GoalAchievedAgentAction()
      contextHolder.addStep(
        ArbigentContextHolder.Step(
          stepId = "step-1",
          agentAction = action,
          cacheKey = "cache-key",
          screenshotFilePath = "screenshot.png",
        ),
      )
      // The replayed action itself took 2000ms, which the recorded interval already covers.
      delay(2_000)
      val startedAt = currentTime
      interceptor.intercept(stepInput(device, contextHolder)) { ArbigentAgent.StepResult.Continue }

      assertEquals(
        3_000L,
        currentTime - startedAt,
        "the time the action itself took should be subtracted from the recorded interval",
      )
    }
  }

  /**
   * Runs [block] with the clock the interceptor measures elapsed time against driven by the test
   * scheduler, so time the test spends in [delay] counts as time the replayed action took.
   */
  /**
   * Runs [block] with the clock the wait measures against pointed at the test scheduler.
   *
   * The wait reads [TimeProvider] twice — for the time already spent since the previous step, and
   * for how long each hierarchy read took — while `delay` moves only the virtual clock. Left on
   * real time those two readings are a few stray milliseconds of whatever the machine was doing,
   * which is enough to make an exact assertion on `currentTime` fail on a slow CI runner.
   */
  private suspend fun TestScope.withVirtualClock(block: suspend () -> Unit) {
    val scheduler = testScheduler
    val previous = TimeProvider.get()
    TimeProvider.set(
      object : TimeProvider {
        override fun currentTimeMillis(): Long = scheduler.currentTime
      },
    )
    try {
      block()
    } finally {
      TimeProvider.set(previous)
    }
  }

  private companion object {
    private const val ReadCostMillis = 200L
  }

  /** Hands out a scripted screen per [elements] call, repeating the last one once exhausted. */
  private class ScriptedDevice(
    private val screens: List<ArbigentElementList>,
  ) : ArbigentDevice by FakeDevice() {
    var elementsCallCount: Int = 0
      private set

    override fun elements(): ArbigentElementList {
      val screen = screens[elementsCallCount.coerceAtMost(screens.lastIndex)]
      elementsCallCount++
      return screen
    }
  }

  private fun stepInput(
    device: ArbigentDevice,
    contextHolder: ArbigentContextHolder = ArbigentContextHolder("goal", 10),
  ): ArbigentAgent.StepInput = ArbigentAgent.StepInput(
    arbigentContextHolder = contextHolder,
    agentActionTypes = defaultAgentActionTypesForVisualMode(),
    device = device,
    deviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
    ai = FakeAi(),
    decisionChain = { error("Decision chain should not run") },
    imageAssertionChain = { ArbigentAi.ImageAssertionOutput(emptyList()) },
    executeActionChain = { ArbigentAgent.ExecuteActionsOutput() },
    prompt = ArbigentPrompt(),
    aiOptions = null,
    attemptMode = ArbigentAttemptMode.ReplayWithFallback,
  )

  /**
   * A trace whose steps all act on the same target. With a [secondTimestamp] it has two steps
   * recorded that far apart, so the second one has an interval to derive its budget from.
   */
  private fun traceWithTarget(secondTimestamp: Long? = null): ArbigentReplayTrace {
    val action = ClickWithTextAgentAction("target")
    val timestamps = listOfNotNull(0L.takeIf { secondTimestamp != null }, secondTimestamp)
      .ifEmpty { listOf(null) }
    return trace(
      timestamps.mapIndexed { index, timestamp ->
        ArbigentContextHolder.Step(
          stepId = "step-${index + 1}",
          agentAction = action,
          cacheKey = "cache-key",
          screenshotFilePath = "screenshot.png",
          targetElement = ArbigentElementIdentity(text = "target"),
        ).let { step -> if (timestamp == null) step else step.copy(timestamp = timestamp) } to action
      },
    )
  }

  private fun traceWithoutTarget(
    firstTimestamp: Long,
    secondTimestamp: Long,
  ): ArbigentReplayTrace {
    val action = GoalAchievedAgentAction()
    return trace(
      listOf(firstTimestamp, secondTimestamp).mapIndexed { index, timestamp ->
        ArbigentContextHolder.Step(
          stepId = "step-${index + 1}",
          agentAction = action,
          cacheKey = "cache-key",
          screenshotFilePath = "screenshot.png",
          timestamp = timestamp,
        ) to action
      },
    )
  }

  private fun trace(
    steps: List<Pair<ArbigentContextHolder.Step, ArbigentAgentAction>>,
  ): ArbigentReplayTrace = ArbigentReplayTrace(
    version = "1.2.3",
    scenarioId = "scenario",
    taskIndex = 0,
    taskIdentity = "scenario",
    goalHash = "goal-hash",
    steps = steps.map { (step, action) ->
      ArbigentReplayTraceStep(
        decisionOutput = ArbigentAi.DecisionOutput(agentActions = listOf(action), step = step),
      )
    },
  )

  /**
   * A replayed step costs an iteration just like one the AI decides, so a trace with more steps
   * than the task allows would run out of steps before its goal on every run. Lowering the limit
   * has to reject an already stored trace, which is why the limit is checked on read and is not
   * part of the file name.
   */
  @Test
  fun `a trace with more steps than the task allows is not replayed`() {
    val directory = Files.createTempDirectory("arbigent-replay-trace-over-max-step").toFile()
    val store = ArbigentReplayTraceStore { directory }
    val key = ArbigentReplayTraceKey(
      version = "1.2.3",
      scenarioId = "scenario",
      taskIndex = 0,
      taskIdentity = "scenario",
      goal = "Goal",
      maxStep = 2,
    )
    val click = ClickWithIndex(0)
    val trace = minimalTrace(key).let { goalOnly ->
      goalOnly.copy(
        steps = listOf(
          ArbigentReplayTraceStep(
            decisionOutput = ArbigentAi.DecisionOutput(
              agentActions = listOf(click),
              step = ArbigentContextHolder.Step(
                stepId = "step-0",
                agentAction = click,
                cacheKey = "cache-key",
                screenshotFilePath = "screenshot.png",
              ),
            ),
          ),
        ) + goalOnly.steps,
      )
    }

    store.write(key, trace)

    assertNotNull(store.read(key), "a trace that fits the limit should be replayed")
    assertNull(
      store.read(key.copy(maxStep = 1)),
      "a trace that cannot finish within the limit should not be replayed",
    )
  }

  /** The smallest trace [ArbigentReplayTrace.isValidFor] accepts: one step that reaches the goal. */
  private fun minimalTrace(key: ArbigentReplayTraceKey): ArbigentReplayTrace {
    val action = GoalAchievedAgentAction()
    return ArbigentReplayTrace(
      version = key.version,
      scenarioId = key.scenarioId,
      taskIndex = key.taskIndex,
      taskIdentity = key.taskIdentity,
      goalHash = key.goalHash,
      steps = listOf(
        ArbigentReplayTraceStep(
          decisionOutput = ArbigentAi.DecisionOutput(
            agentActions = listOf(action),
            step = ArbigentContextHolder.Step(
              stepId = "step-1",
              agentAction = action,
              cacheKey = "cache-key",
              screenshotFilePath = "screenshot.png",
            ),
          ),
        ),
      ),
    )
  }

  @Test
  fun `a trace store failure does not fail the run that just passed`() {
    val notADirectory = Files.createTempFile("arbigent-replay-trace-blocked", ".txt").toFile()
    val store = ArbigentReplayTraceStore { File(notADirectory, "traces") }
    val key = ArbigentReplayTraceKey(
      version = "1.2.3",
      scenarioId = "scenario",
      taskIndex = 0,
      taskIdentity = "scenario",
      goal = "Goal",
      maxStep = 10,
    )
    val action = GoalAchievedAgentAction()
    val trace = ArbigentReplayTrace(
      version = key.version,
      scenarioId = key.scenarioId,
      taskIndex = key.taskIndex,
      taskIdentity = key.taskIdentity,
      goalHash = key.goalHash,
      steps = listOf(
        ArbigentReplayTraceStep(
          decisionOutput = ArbigentAi.DecisionOutput(
            agentActions = listOf(action),
            step = ArbigentContextHolder.Step(
              stepId = "step-1",
              agentAction = action,
              cacheKey = "cache-key",
              screenshotFilePath = "screenshot.png",
            ),
          ),
        ),
      ),
    )

    store.write(key, trace)

    assertNull(store.read(key))
  }

  @Test
  fun `target identity accepts unchanged element and rejects changed element`() {
    val original = element(
      text = "Models",
      resourceId = "models_button",
      accessibilityId = "Open models",
    )
    val identity = assertNotNull(ArbigentElementIdentity.from(original, listOf(original)))

    assertNotNull(identity.findMatch(ArbigentElementList(listOf(original), screenWidth = 100)))

    val changed = element(
      text = "Settings",
      resourceId = "settings_button",
      accessibilityId = "Open settings",
    )
    assertNull(identity.findMatch(ArbigentElementList(listOf(changed), screenWidth = 100)))
  }

  @Test
  fun `a target whose text sits in a child is still identified and found after it moves`() {
    // The shape an Android TV card or tab has: the focusable container carries no identifying
    // attribute and the text is in a non-clickable child.
    val original = tvCardElement(text = "Special footage")
    val identity = assertNotNull(ArbigentElementIdentity.from(original, listOf(original)))
    assertEquals("Special footage", identity.text)

    // The same card after focus moved it to a different position in the element list.
    val moved = tvCardElement(text = "Special footage")
    val current = ArbigentElementList(
      listOf(tvCardElement(text = "Documentary"), tvCardElement(text = "Trailer"), moved),
      screenWidth = 100,
    )
    assertEquals(moved, identity.findMatch(current))

    assertNull(
      identity.findMatch(
        ArbigentElementList(listOf(tvCardElement(text = "Documentary")), screenWidth = 100),
      ),
    )
  }

  @Test
  fun `failed replay attempt keeps decision cache while normal execution purges it`() = runTest {
    val cache = ArbigentAiDecisionCache.Memory.create()
    val cachedAction = GoalAchievedAgentAction()
    cache.set(
      "layout-cache-key",
      ArbigentAi.DecisionOutput(
        agentActions = listOf(cachedAction),
        step = ArbigentContextHolder.Step(
          stepId = "step",
          agentAction = cachedAction,
          cacheKey = "layout-cache-key",
          screenshotFilePath = "screenshot.png",
        ),
      ),
    )
    val interceptor = ArbigentDecisionCacheInterceptor(
      aiDecisionCache = cache,
      cacheOptions = ArbigentScenarioCacheOptions(),
    )
    val contextHolder = ArbigentContextHolder("goal", 1).apply {
      addStep(
        ArbigentContextHolder.Step(
          stepId = "step",
          cacheKey = "layout-cache-key",
          screenshotFilePath = "screenshot.png",
        )
      )
    }

    interceptor.intercept(executeInput(ArbigentAttemptMode.ReplayWithFallback)) {
      ArbigentAgent.ExecutionResult.Failed(contextHolder)
    }
    assertNotNull(
      cache.get("layout-cache-key"),
      "A failed replay attempt must keep the layout cache for the fallback retry",
    )

    interceptor.intercept(executeInput(ArbigentAttemptMode.Normal)) {
      ArbigentAgent.ExecutionResult.Failed(contextHolder)
    }
    assertNull(
      cache.get("layout-cache-key"),
      "A failed normal attempt must purge the layout cache as before",
    )
  }

  /**
   * The actions that carry a recorded index read the element list directly, so an index that no
   * longer exists would surface as an IndexOutOfBoundsException from inside the action, after the
   * screen had already been touched. Replay must see it as divergence first.
   */
  @Test
  fun `an index beyond the current screen is divergence, not an exception from the action`() = runTest {
    val action = ClickWithIndex(16)
    val trace = ArbigentReplayTrace(
      version = "1.2.3",
      scenarioId = "scenario",
      taskIndex = 0,
      taskIdentity = "scenario",
      goalHash = "hash",
      steps = listOf(
        ArbigentReplayTraceStep(
          decisionOutput = ArbigentAi.DecisionOutput(
            agentActions = listOf(action),
            step = ArbigentContextHolder.Step(
              stepId = "step-1",
              agentAction = action,
              cacheKey = "cache-key",
              screenshotFilePath = "screenshot.png",
            ),
          ),
        ),
      ),
    )
    val interceptor = ArbigentReplayDecisionInterceptor(trace)
    val onlyOneElement = ArbigentElementList(
      listOf(element(text = "Only", resourceId = "only", accessibilityId = "Only")),
      screenWidth = 100,
    )

    val exception = assertFailsWith<ReplayDivergenceException> {
      interceptor.intercept(decisionInput(onlyOneElement)) { error("Should not reach the AI") }
    }
    assertTrue(
      exception.message.contains("targets element 16"),
      "Expected the reason to name the missing index, got: ${exception.message}",
    )
  }

  private fun decisionInput(elements: ArbigentElementList): ArbigentAi.DecisionInput =
    ArbigentAi.DecisionInput(
      stepId = "step",
      contextHolder = ArbigentContextHolder("goal", 1),
      formFactor = ArbigentScenarioDeviceFormFactor.Mobile,
      uiTreeStrings = io.github.takahirom.arbigent.result.ArbigentUiTreeStrings("", ""),
      focusedTreeString = null,
      agentActionTypes = defaultAgentActionTypesForVisualMode(),
      screenshotFilePath = "screenshot.png",
      requestUuid = "uuid",
      apiCallJsonLFilePath = "call.jsonl",
      elements = elements,
      prompt = ArbigentPrompt(),
      cacheKey = "cache-key",
      aiOptions = null,
    )

  private fun executeInput(attemptMode: ArbigentAttemptMode): ArbigentAgent.ExecuteInput {
    val device = FakeDevice()
    val ai = FakeAi()
    return ArbigentAgent.ExecuteInput(
      scenarioId = "scenario",
      goal = "goal",
      maxStep = 1,
      agentActionTypes = defaultAgentActionTypesForVisualMode(),
      deviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
      prompt = ArbigentPrompt(),
      device = device,
      ai = ai,
      aiOptions = null,
      attemptMode = attemptMode,
      createContextHolder = { goal, maxStep -> ArbigentContextHolder(goal, maxStep) },
      addContextHolder = {},
      updateIsRunning = {},
      updateCurrentGoal = {},
      initializerChain = {},
      stepChain = { ArbigentAgent.StepResult.Failed },
      decisionChain = { error("Decision chain should not run") },
      imageAssertionChain = { ArbigentAi.ImageAssertionOutput(emptyList()) },
      executeActionChain = { ArbigentAgent.ExecuteActionsOutput() },
    )
  }

  private fun tvCardElement(text: String): ArbigentElement = ArbigentElement(
    index = 0,
    textForAI = "View(text=$text, )",
    rawText = text,
    identifierData = ArbigentElement.IdentifierData(emptyList(), 0),
    treeNode = TreeNode(
      attributes = mutableMapOf(
        "class" to "android.view.View",
        "clickable" to "true",
        "text" to "",
        "resource-id" to "",
        "accessibilityText" to "",
      ),
      children = listOf(
        TreeNode(
          attributes = mutableMapOf(
            "class" to "android.widget.TextView",
            "clickable" to "false",
            "text" to text,
            "resource-id" to "",
            "accessibilityText" to "",
          ),
          children = emptyList(),
        ),
      ),
    ),
    x = 0,
    y = 0,
    width = 10,
    height = 10,
    isVisible = true,
  )

  private fun element(
    text: String,
    resourceId: String,
    accessibilityId: String,
    y: Int = 0,
  ): ArbigentElement = ArbigentElement(
    index = 0,
    textForAI = text,
    rawText = text,
    identifierData = ArbigentElement.IdentifierData(emptyList(), 0),
    treeNode = TreeNode(
      attributes = mutableMapOf(
        "text" to text,
        "resource-id" to resourceId,
        "accessibilityText" to accessibilityId,
      ),
      children = emptyList(),
    ),
    x = 0,
    y = y,
    width = 10,
    height = 10,
    isVisible = true,
  )
}
