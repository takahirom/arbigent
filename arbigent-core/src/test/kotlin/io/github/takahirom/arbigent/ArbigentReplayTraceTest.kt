package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.sample.test.FakeAi
import io.github.takahirom.arbigent.sample.test.FakeDevice
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.github.takahirom.arbigent.result.ArbigentStepSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
  fun `candidate keeps the recorded focus of replayed steps and the run's own focus after them`() {
    val recordedSteps = timestampedSteps(listOf(1_000, 5_000, 12_000)).mapIndexed { index, step ->
      step.copy(focusedElement = focusAt(100 + index * 100))
    }
    val recordedTrace = trace(recordedSteps.map { it to requireNotNull(it.agentAction) })
    // What a step that spent its whole budget reads: the screen it was still on, not the recorded one.
    val replayedSteps = recordedSteps.take(2).mapIndexed { index, step ->
      step.copy(
        timestamp = 100_000L + index * 100L,
        stepSource = ArbigentStepSource.Replay,
        focusedElement = focusAt(900),
      )
    }
    val aiStep = timestampedSteps(listOf(102_200)).single().copy(stepId = "ai", focusedElement = focusAt(900))
    val context = ArbigentContextHolder("goal", 10).apply {
      (replayedSteps + aiStep).forEach(::addStep)
    }

    val candidate = ArbigentReplayTrace.candidateFrom(candidateKey(), context, recordedTrace = recordedTrace)

    assertEquals(
      listOf(focusAt(100), focusAt(200), focusAt(900)),
      candidate.steps.map { it.decisionOutput.step.focusedElement },
    )
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
    assertEquals(
      10,
      device.elementsCallCount,
      "ten 500ms polls fit in the budget, and the read that would start at the deadline is not one",
    )
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
  @Test
  fun `a replayed step with no target waits until focus reaches where it was recorded`() = runTest {
    withVirtualClock {
      val elsewhere = focusAt(200)
      val recorded = focusAt(400)
      val device = ScriptedDevice(focuses = listOf(elsewhere, elsewhere, recorded))
      var proceeded = false

      ArbigentReplayPacingStepInterceptor(traceWithFocus(listOf(focusAt(400)))).intercept(
        stepInput(device),
      ) {
        proceeded = true
        ArbigentAgent.StepResult.Continue
      }

      assertTrue(proceeded, "the step should have been captured once focus arrived")
      assertEquals(3, device.focusedElementCallCount, "the wait should end on the poll that saw the focus")
      assertEquals(1000, currentTime, "the recorded gap is a ceiling, not a floor, once focus matches")
    }
  }

  @Test
  fun `a first step does not exit on a screen that already had the recorded focus`() = runTest {
    withVirtualClock {
      // The first step of the first task has nothing recorded before it, so a screen that matches
      // from the start cannot say whether the action before it has landed — the same control holds
      // focus on both screens. Only watching focus arrive proves it, so this waits as pacing did.
      val device = ScriptedDevice(focuses = listOf(focusAt(400)))
      ArbigentReplayPacingStepInterceptor(traceWithFocus(listOf(focusAt(400))))
        .intercept(stepInput(device)) { ArbigentAgent.StepResult.Continue }

      assertEquals(
        10_000,
        currentTime,
        "a match that was already there when the wait started is not an arrival",
      )
    }
  }

  @Test
  fun `the first step of a task trusts the focus the previous task ended on`() = runTest {
    withVirtualClock {
      // The previous task ended by reporting its goal reached, which touches nothing: no action was
      // left in flight for this step to wait out, so the screen already showing the recorded focus
      // is the screen this step was recorded on.
      val device = ScriptedDevice(focuses = listOf(focusAt(400)))
      ArbigentReplayPacingStepInterceptor(
        trace = traceWithFocus(listOf(focusAt(400))),
        previousTaskTrace = { traceWithFocus(listOf(focusAt(400))) },
      ).intercept(stepInput(device)) { ArbigentAgent.StepResult.Continue }

      assertEquals(0, currentTime, "nothing was in flight, so there was nothing to wait for")
      assertEquals(1, device.focusedElementCallCount)
    }
  }

  @Test
  fun `the first step of a task whose initializers ran watches the focus arrive`() = runTest {
    withVirtualClock {
      // Initializers run between the previous task's last step and this one, so whatever the
      // previous task left behind says nothing about the screen this step starts on: the recorded
      // focus is on both the screen the initializers left and the one they are moving towards.
      val device = ScriptedDevice(focuses = listOf(focusAt(400)))
      ArbigentReplayPacingStepInterceptor(
        trace = traceWithFocus(listOf(focusAt(400))),
        previousTaskTrace = { traceWithFocus(listOf(focusAt(400))) },
        runsInitializers = true,
      ).intercept(stepInput(device)) { ArbigentAgent.StepResult.Continue }

      assertEquals(10_000, currentTime, "the initializers moved the device after the recording")
    }
  }

  @Test
  fun `the first step of a task whose initializers ran does not trust a different recorded focus`() =
    runTest {
      withVirtualClock {
        // The previous task ended with focus somewhere else, so outside initializers this step
        // would exit on the first read. Initializers pass through screens nobody recorded, and one
        // of them can hold the focus this step is waiting for while still on its way to it — the
        // focus recorded before they ran is not the screen they left, so it rejects nothing.
        val device = ScriptedDevice(focuses = listOf(focusAt(400)))
        ArbigentReplayPacingStepInterceptor(
          trace = traceWithFocus(listOf(focusAt(400))),
          previousTaskTrace = { traceWithFocus(listOf(focusAt(900))) },
          runsInitializers = true,
        ).intercept(stepInput(device)) { ArbigentAgent.StepResult.Continue }

        assertEquals(
          10_000,
          currentTime,
          "a focus the initializers may be passing through is not the focus having arrived",
        )
      }
    }

  @Test
  fun `the first step of a task waits out the action the previous task ended with`() = runTest {
    withVirtualClock {
      // The previous task ended on an action that moves focus, so the screen it left behind is not
      // the screen this step was recorded on — the recorded focus has to be seen replacing it.
      val device = ScriptedDevice(focuses = listOf(focusAt(200), focusAt(400)))
      ArbigentReplayPacingStepInterceptor(
        trace = traceWithFocus(listOf(focusAt(400))),
        previousTaskTrace = { traceEndingWithClick(focusAt(200)) },
      ).intercept(stepInput(device)) { ArbigentAgent.StepResult.Continue }

      assertEquals(500, currentTime, "the screen the previous task left behind is not an arrival")
      assertEquals(2, device.focusedElementCallCount)
    }
  }

  @Test
  fun `the first step of a task watches focus arrive when the previous task ended on it`() = runTest {
    withVirtualClock {
      // The previous task's last action was recorded with focus already where this step wants it,
      // so a screen matching it says nothing about whether that action has landed here yet.
      val device = ScriptedDevice(focuses = listOf(focusAt(400)))
      ArbigentReplayPacingStepInterceptor(
        trace = traceWithFocus(listOf(focusAt(400))),
        previousTaskTrace = { traceEndingWithClick(focusAt(400)) },
      ).intercept(stepInput(device)) { ArbigentAgent.StepResult.Continue }

      assertEquals(10_000, currentTime, "focus that never moved cannot show the action landed")
    }
  }

  @Test
  fun `the first step of a task accepts a focus the previous task did not end on`() = runTest {
    withVirtualClock {
      // The previous task's recording ends somewhere else, so a screen already showing this step's
      // focus is one that recording can tell from the screen it left behind — nothing has to be
      // watched moving. Without that recording the same read would have to be waited out.
      val device = ScriptedDevice(focuses = listOf(focusAt(400)))
      ArbigentReplayPacingStepInterceptor(
        trace = traceWithFocus(listOf(focusAt(400))),
        previousTaskTrace = { traceEndingWithClick(focusAt(200)) },
      ).intercept(stepInput(device)) { ArbigentAgent.StepResult.Continue }

      assertEquals(0, currentTime, "a focus the previous task did not end on is an arrival")
      assertEquals(1, device.focusedElementCallCount)
    }
  }

  @Test
  fun `the previous task's recording is read when the step runs, not when the wait is built`() =
    runTest {
      withVirtualClock {
        // Whether the task before this one kept to its recording is not known when this is built —
        // it falls back while running, after every task's wait already exists. Reading the answer
        // at construction would hand this step a predecessor that never ran.
        var previousTaskKeptToItsRecording = true
        val device = ScriptedDevice(focuses = listOf(focusAt(400)))
        val interceptor = ArbigentReplayPacingStepInterceptor(
          trace = traceWithFocus(listOf(focusAt(400))),
          previousTaskTrace = {
            traceEndingWithClick(focusAt(200)).takeIf { previousTaskKeptToItsRecording }
          },
        )
        previousTaskKeptToItsRecording = false

        interceptor.intercept(stepInput(device)) { ArbigentAgent.StepResult.Continue }

        assertEquals(
          10_000,
          currentTime,
          "a recording the previous task stopped following cannot rule out the screen it left",
        )
      }
    }

  @Test
  fun `a task whose predecessor fell back has no recording of what ran before it`() {
    val traces = listOf(
      traceWithFocus(listOf(focusAt(200))),
      traceWithFocus(listOf(focusAt(400))),
    )

    assertEquals(
      null,
      previousReplayedTaskTrace(
        index = 1,
        replayTraces = traces,
        attemptMode = ArbigentAttemptMode.ReplayWithFallback,
        fellBackTaskIndexes = setOf(0),
      ),
      "the task before this one finished under the AI, not on its recording",
    )
    assertEquals(
      traces[0],
      previousReplayedTaskTrace(
        index = 1,
        replayTraces = traces,
        attemptMode = ArbigentAttemptMode.ReplayWithFallback,
        fellBackTaskIndexes = setOf(1),
      ),
      "this task's own fallback says nothing about the one before it",
    )
  }

  @Test
  fun `the first task of a scenario and a normal attempt have nothing recorded before them`() {
    val traces = listOf(traceWithFocus(listOf(focusAt(200))))

    assertEquals(
      null,
      previousReplayedTaskTrace(
        index = 0,
        replayTraces = traces,
        attemptMode = ArbigentAttemptMode.ReplayWithFallback,
        fellBackTaskIndexes = emptySet(),
      ),
      "nothing replayed before the first task of a scenario",
    )
    assertEquals(
      null,
      previousReplayedTaskTrace(
        index = 1,
        replayTraces = traces,
        attemptMode = ArbigentAttemptMode.Normal,
        fellBackTaskIndexes = emptySet(),
      ),
      "nothing replayed at all when the attempt is not a replay",
    )
  }

  @Test
  fun `a few pixels of layout drift still counts as the recorded focus`() = runTest {
    withVirtualClock {
      // The same screen lays out a few pixels apart between machines, so the bounds recorded on one
      // never arrive exactly on another; only a move to a different item may keep a step waiting.
      val device = ScriptedDevice(focuses = listOf(focusAt(200), focusAt(403)))
      ArbigentReplayPacingStepInterceptor(traceWithFocus(listOf(focusAt(400))))
        .intercept(stepInput(device)) { ArbigentAgent.StepResult.Continue }

      assertEquals(2, device.focusedElementCallCount)
      assertEquals(500, currentTime, "focus arriving three pixels off is focus arriving")
    }
  }

  @Test
  fun `focus on a different row of the same container is not the recorded focus`() = runTest {
    withVirtualClock {
      // Every row of a sidebar shares one container id, so only the bounds say which one has focus.
      val device = ScriptedDevice(focuses = listOf(focusAt(465)))
      ArbigentReplayPacingStepInterceptor(traceWithFocus(listOf(focusAt(400))))
        .intercept(stepInput(device)) { ArbigentAgent.StepResult.Continue }

      assertEquals(
        10_000,
        currentTime,
        "with no recorded interval to derive a budget from, the wait should last the minimum",
      )
    }
  }

  @Test
  fun `focus recorded in the same place as the step before it is waited out, not polled`() = runTest {
    // Two steps recorded with focus in the same place cannot tell the screen before the previous
    // action landed from the screen after it, so matching focus proves nothing about either.
    val trace = traceWithFocus(
      focuses = listOf(focusAt(400), focusAt(400)),
      timestamps = listOf(0L, 3_000L),
    )
    // The first step watches focus arrive, which is what its own wait requires; the second is the
    // one under test.
    val device = ScriptedDevice(focuses = listOf(focusAt(200), focusAt(400)))
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
      interceptor.intercept(stepInput(device, contextHolder)) { ArbigentAgent.StepResult.Continue }

      assertEquals(
        500 + 3_000,
        currentTime,
        "the first step exits on the poll that saw focus arrive, and the second waits out the " +
          "3000ms recorded between them rather than exiting on a focus that never moved",
      )
      assertEquals(2, device.focusedElementCallCount, "only the first step had a focus worth polling for")
    }
  }

  @Test
  fun `focus that moved by less than the tolerance is waited out, not polled`() = runTest {
    // The two recordings are not equal, so they are not the same place by the strict test — but a
    // screen still showing the first is within tolerance of the second, so exiting on it would be
    // exiting before the previous action landed. The wait has to reject that screen too.
    val trace = traceWithFocus(
      focuses = listOf(focusAt(400), focusAt(402)),
      timestamps = listOf(0L, 3_000L),
    )
    val device = ScriptedDevice(focuses = listOf(focusAt(200), focusAt(400)))
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
      interceptor.intercept(stepInput(device, contextHolder)) { ArbigentAgent.StepResult.Continue }

      assertEquals(
        500 + 3_000,
        currentTime,
        "a screen that still matches the previous focus is not the screen the step was recorded on",
      )
    }
  }

  @Test
  fun `focus the same height as recorded is required, not just the same corner`() = runTest {
    withVirtualClock {
      // An expanded row keeps its id and its top-left corner; only its height says it has grown.
      val device = ScriptedDevice(focuses = listOf(focusAt(400, height = 90)))
      ArbigentReplayPacingStepInterceptor(traceWithFocus(listOf(focusAt(400))))
        .intercept(stepInput(device)) { ArbigentAgent.StepResult.Continue }

      assertEquals(
        10_000,
        currentTime,
        "with no recorded interval to derive a budget from, the wait should last the minimum",
      )
    }
  }

  @Test
  fun `a step whose predecessor recorded no focus is waited out, not polled`() = runTest {
    // A step with no recorded focus means the recording could not read it, not that focus was
    // nowhere. Without it there is no way to tell a move from a screen that never changed, so
    // polling could end on the screen the previous action has not left yet.
    val trace = traceWithFocus(
      focuses = listOf(null, focusAt(400)),
      timestamps = listOf(0L, 3_000L),
    )
    val device = ScriptedDevice(focuses = listOf(focusAt(400)))
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
      interceptor.intercept(stepInput(device, contextHolder)) { ArbigentAgent.StepResult.Continue }

      assertEquals(
        10_000L + 3_000L,
        currentTime,
        "the first step has no recorded focus to wait for so it waits the minimum, and the second " +
          "waits out the 3000ms recorded between them rather than trusting an unknown predecessor",
      )
      assertEquals(0, device.focusedElementCallCount, "neither step had a focus worth polling for")
    }
  }

  @Test
  fun `a slow focus read does not push the wait past the recorded gap`() = runTest {
    val device = ScriptedDevice(focuses = listOf(focusAt(999)))
    val scheduler = testScheduler
    val previous = TimeProvider.get()
    // Every focus read appears to take 300ms. Only this provider can express that: a read is
    // synchronous, so it cannot advance the test scheduler the way `delay` does.
    TimeProvider.set(
      object : TimeProvider {
        override fun currentTimeMillis(): Long =
          scheduler.currentTime + FocusReadCostMillis * device.focusedElementCallCount
      },
    )
    try {
      ArbigentReplayPacingStepInterceptor(traceWithFocus(listOf(focusAt(400))))
        .intercept(stepInput(device)) { ArbigentAgent.StepResult.Continue }

      assertEquals(
        10_000L,
        TimeProvider.get().currentTimeMillis(),
        "no read may start once the budget is gone: the budget is the recorded gap this step has " +
          "to fit inside, and a read that starts at the deadline spends its own time past it",
      )
    } finally {
      TimeProvider.set(previous)
    }
  }

  @Test
  fun `a focus read that would not finish inside the recorded gap is not started`() = runTest {
    // The deadline is not the only way to overrun it: a read started just before it still crosses
    // it. What is left is slept out rather than spent on a read that cannot finish in time.
    val trace = traceWithFocus(
      focuses = listOf(focusAt(200), focusAt(400)),
      timestamps = listOf(0L, 2_600L),
    )
    val device = ScriptedDevice(focuses = listOf(focusAt(999)))
    val contextHolder = ArbigentContextHolder("goal", 10)
    val interceptor = ArbigentReplayPacingStepInterceptor(trace)
    val scheduler = testScheduler
    val previous = TimeProvider.get()
    TimeProvider.set(
      object : TimeProvider {
        override fun currentTimeMillis(): Long =
          scheduler.currentTime + FocusReadCostMillis * device.focusedElementCallCount
      },
    )
    try {
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
        10_000L + 2_600L,
        TimeProvider.get().currentTimeMillis(),
        "the first step waits out the minimum and the second waits out the 2600ms recorded " +
          "between them, neither of them a millisecond more",
      )
    } finally {
      TimeProvider.set(previous)
    }
  }

  @Test
  fun `a trace recorded before focus was captured deserializes and paces as it always did`() {
    // Old traces outlive the change that added the field; they must not start waiting differently.
    val json = """{"decisionOutput":{"agentActions":[],"step":{"stepId":"step-1",""" +
      """"cacheKey":"cache-key","screenshotFilePath":"screenshot.png","timestamp":0}}}"""
    val step = Json { ignoreUnknownKeys = true }
      .decodeFromString<ArbigentReplayTraceStep>(json)

    assertEquals(null, step.decisionOutput.step.focusedElement)
    assertEquals(null, step.decisionOutput.step.targetElement)
  }

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


  private fun focusAt(y: Int, height: Int = 50): ArbigentFocusedElement = ArbigentFocusedElement(
    className = FocusClassName,
    resourceId = FocusResourceId,
    x = 0,
    y = y,
    width = 100,
    height = height,
  )

  /** A trace of steps that carry no target, only the focus each was recorded with. */
  private fun traceWithFocus(
    focuses: List<ArbigentFocusedElement?>,
    timestamps: List<Long>? = null,
  ): ArbigentReplayTrace {
    val action = GoalAchievedAgentAction()
    return trace(
      focuses.mapIndexed { index, focus ->
        ArbigentContextHolder.Step(
          stepId = "step-${index + 1}",
          agentAction = action,
          cacheKey = "cache-key",
          screenshotFilePath = "screenshot.png",
          focusedElement = focus,
        ).let { step ->
          timestamps?.get(index)?.let { step.copy(timestamp = it) } ?: step
        } to action
      },
    )
  }

  /** A one-step trace whose task ended on an action that leaves the screen moving. */
  private fun traceEndingWithClick(focus: ArbigentFocusedElement): ArbigentReplayTrace {
    val action = ClickWithTextAgentAction("target")
    return trace(
      listOf(
        ArbigentContextHolder.Step(
          stepId = "previous-task-step",
          agentAction = action,
          cacheKey = "cache-key",
          screenshotFilePath = "screenshot.png",
          focusedElement = focus,
        ) to action,
      ),
    )
  }

  private companion object {
    private const val ReadCostMillis = 200L
    private const val FocusReadCostMillis = 300L
    private const val FocusResourceId = "sidebar_item_container"
    private const val FocusClassName = "android.view.ViewGroup"
  }

  /** Hands out a scripted screen per [elements] call, repeating the last one once exhausted. */
  private class ScriptedDevice(
    private val screens: List<ArbigentElementList> = emptyList(),
    private val focuses: List<ArbigentFocusedElement?> = emptyList(),
  ) : ArbigentDevice by FakeDevice() {
    var elementsCallCount: Int = 0
      private set
    var focusedElementCallCount: Int = 0
      private set

    override fun elements(): ArbigentElementList {
      val screen = screens[elementsCallCount.coerceAtMost(screens.lastIndex)]
      elementsCallCount++
      return screen
    }

    override fun focusedElement(): ArbigentFocusedElement? {
      if (focuses.isEmpty()) return null
      val focus = focuses[focusedElementCallCount.coerceAtMost(focuses.lastIndex)]
      focusedElementCallCount++
      return focus
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
  fun `a recorded twin resolves only among the same number of twins`() {
    // A list with two identical "Delete" rows, and the recording acted on the second one.
    val rows = listOf(row("Keep", y = 0), row("Delete", y = 10), row("Delete", y = 20))
    val identity = assertNotNull(ArbigentElementIdentity.from(rows[2], rows))
    assertEquals(1, identity.occurrence)

    // Same twins: the same position is the same row.
    assertEquals(rows[2], identity.findMatch(ArbigentElementList(rows, screenWidth = 100)))

    // A row was added above: position 1 now names a different row, so the step must diverge
    // instead of deleting whatever sits there.
    val grown = listOf(row("Delete", y = 0), row("Keep", y = 10), row("Delete", y = 20), row("Delete", y = 30))
    assertNull(identity.findMatch(ArbigentElementList(grown, screenWidth = 100)))

    // A twin disappeared: the one left may be either of the recorded two.
    val shrunk = listOf(row("Keep", y = 0), row("Delete", y = 10))
    assertNull(identity.findMatch(ArbigentElementList(shrunk, screenWidth = 100)))
  }

  @Test
  fun `a target whose every twin is gone is absent, not a changed number of twins`() {
    val rows = listOf(row("Keep", y = 0), row("Delete", y = 10), row("Delete", y = 20))
    val identity = assertNotNull(ArbigentElementIdentity.from(rows[2], rows))

    val resolution = identity.resolve(ArbigentElementList(listOf(row("Keep", y = 0)), screenWidth = 100))

    assertEquals(ArbigentElementIdentity.Resolution.Absent, resolution)
  }

  @Test
  fun `a changed number of twins is reported as such, not as an absent target`() = runTest {
    val rows = listOf(row("Keep", y = 0), row("Delete", y = 10), row("Delete", y = 20))
    val identity = assertNotNull(ArbigentElementIdentity.from(rows[2], rows))
    val action = ClickWithIndex(2)
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
              targetElement = identity,
              cacheKey = "cache-key",
              screenshotFilePath = "screenshot.png",
            ),
          ),
        ),
      ),
    )
    val interceptor = ArbigentReplayDecisionInterceptor(trace)
    val grown = listOf(row("Delete", y = 0), row("Keep", y = 10), row("Delete", y = 20), row("Delete", y = 30))

    val exception = assertFailsWith<ReplayDivergenceException> {
      interceptor.intercept(decisionInput(ArbigentElementList(grown, screenWidth = 100))) {
        error("Should not reach the AI")
      }
    }
    assertTrue(
      exception.message.contains("recorded among 2 identical elements but the current screen has 3"),
      "Expected the reason to name both twin counts, got: ${exception.message}",
    )
  }

  @Test
  fun `an identity recorded without a twin count keeps resolving by position`() {
    val legacy = ArbigentElementIdentity(text = "Delete", occurrence = 1)
    val grown = listOf(row("Delete", y = 0), row("Keep", y = 10), row("Delete", y = 20), row("Delete", y = 30))

    assertEquals(grown[2], legacy.findMatch(ArbigentElementList(grown, screenWidth = 100)))
  }

  @Test
  fun `a twin count survives the trace JSON round trip`() {
    val rows = listOf(row("Delete", y = 0), row("Delete", y = 10))
    val identity = assertNotNull(ArbigentElementIdentity.from(rows[1], rows))

    val decoded = Json.decodeFromString(
      ArbigentElementIdentity.serializer(),
      Json.encodeToString(ArbigentElementIdentity.serializer(), identity),
    )

    assertEquals(identity, decoded)
    assertEquals(2, decoded.twinCount)
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

  /**
   * A coordinate tap names a pixel, not an element: only a screen of the same size puts that pixel
   * in the same place. The check runs before anything is tapped and names both sizes.
   */
  @Test
  fun `a coordinate tap recorded on another screen size is divergence`() = runTest {
    val interceptor = ArbigentReplayDecisionInterceptor(
      coordinateTapTrace(recordedOn = ArbigentViewport(width = 1080, height = 1920)),
    )

    val exception = assertFailsWith<ReplayDivergenceException> {
      interceptor.intercept(
        decisionInput(emptyElements, viewport = ArbigentViewport(width = 1440, height = 2560)),
      ) { error("Should not reach the AI") }
    }
    assertTrue(
      exception.message.contains("recorded on a 1080x1920 screen but the current screen is 1440x2560"),
      "Expected the reason to name both sizes, got: ${exception.message}",
    )
  }

  @Test
  fun `a coordinate tap recorded on a screen the current device cannot size is divergence`() = runTest {
    val interceptor = ArbigentReplayDecisionInterceptor(
      coordinateTapTrace(recordedOn = ArbigentViewport(width = 1080, height = 1920)),
    )

    val exception = assertFailsWith<ReplayDivergenceException> {
      interceptor.intercept(decisionInput(emptyElements, viewport = null)) { error("Should not reach the AI") }
    }
    assertTrue(
      exception.message.contains("the current screen is of unknown size"),
      "Expected the reason to say the current size is unknown, got: ${exception.message}",
    )
  }

  /** Traces recorded before the screen size was captured diverge once, and are re-recorded with it. */
  @Test
  fun `a coordinate tap recorded without a screen size is divergence on a sized screen`() = runTest {
    val interceptor = ArbigentReplayDecisionInterceptor(coordinateTapTrace(recordedOn = null))

    val exception = assertFailsWith<ReplayDivergenceException> {
      interceptor.intercept(
        decisionInput(emptyElements, viewport = ArbigentViewport(width = 1280, height = 720)),
      ) { error("Should not reach the AI") }
    }
    assertTrue(
      exception.message.contains("recorded on a screen of unknown size but the current screen is 1280x720"),
      "Expected the reason to name the current size, got: ${exception.message}",
    )
  }

  @Test
  fun `a coordinate tap replays on a screen of the recorded size`() = runTest {
    val viewport = ArbigentViewport(width = 1080, height = 1920)
    val interceptor = ArbigentReplayDecisionInterceptor(coordinateTapTrace(recordedOn = viewport))

    val output = interceptor.intercept(decisionInput(emptyElements, viewport = viewport)) {
      error("Should not reach the AI")
    }

    assertEquals(ClickAtCoordinates(x = 195, y = 760), output.agentActions.single())
    assertEquals(ArbigentStepSource.Replay, output.step.stepSource)
  }

  @Test
  fun `an element-targeted step replays on a changed screen size`() = runTest {
    val action = ClickWithIndex(0)
    val trace = singleStepTrace(action, viewport = ArbigentViewport(width = 1080, height = 1920))
    val interceptor = ArbigentReplayDecisionInterceptor(trace)
    val elements = ArbigentElementList(
      listOf(element(text = "Only", resourceId = "only", accessibilityId = "Only")),
      screenWidth = 100,
    )

    val output = interceptor.intercept(
      decisionInput(elements, viewport = ArbigentViewport(width = 1440, height = 2560)),
    ) { error("Should not reach the AI") }

    assertEquals(action, output.agentActions.single())
  }

  private val emptyElements = ArbigentElementList(emptyList(), screenWidth = 100)

  private fun coordinateTapTrace(recordedOn: ArbigentViewport?): ArbigentReplayTrace =
    singleStepTrace(ClickAtCoordinates(x = 195, y = 760), viewport = recordedOn)

  private fun singleStepTrace(action: ArbigentAgentAction, viewport: ArbigentViewport?): ArbigentReplayTrace =
    ArbigentReplayTrace(
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
              viewport = viewport,
            ),
          ),
        ),
      ),
    )

  private fun decisionInput(
    elements: ArbigentElementList,
    viewport: ArbigentViewport? = null,
  ): ArbigentAi.DecisionInput =
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
      viewport = viewport,
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

  private fun row(text: String, y: Int): ArbigentElement = ArbigentElement(
    index = 0,
    textForAI = text,
    rawText = text,
    identifierData = ArbigentElement.IdentifierData(emptyList(), 0),
    treeNode = TreeNode(
      attributes = mutableMapOf("text" to text, "resource-id" to "", "accessibilityText" to ""),
      children = emptyList(),
    ),
    x = 0,
    y = y,
    width = 10,
    height = 10,
    isVisible = true,
  )

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

  // ---- anchors (what appeared with a step) and text read off the screen ----

  /**
   * An anchor is what tells the screen a step belongs on from the screen it came from, so only
   * what was not there before qualifies. Content titles rotate between runs; elements with an id
   * come first so the eight kept are the ones most likely to still be there.
   */
  @Test
  fun `anchors are the elements new since the previous screen, ids first, at most eight`() {
    val carriedOver = listOf(element("Home", "nav_home", ""), element("Shared", "", ""))
    val titles = (1..10).map { element("Title $it", "", "") }
    val badges = listOf(element("", "card_badge", ""), element("", "", "card_overlay"))
    val previous = ArbigentElementList(carriedOver, screenWidth = 100)
    val current = ArbigentElementList(carriedOver + titles + badges, screenWidth = 100)

    val anchors = ArbigentReplayAnchors.select(current, previous)

    assertEquals(ArbigentReplayAnchors.MAX_ANCHORS, anchors.size)
    assertEquals(listOf("card_badge", null), anchors.take(2).map { it.resourceId })
    assertEquals("card_overlay", anchors[1].accessibilityId)
    assertEquals((1..6).map { "Title $it" }, anchors.drop(2).map { it.text })
    assertTrue(anchors.none { it.text == "Home" || it.text == "Shared" }, "what was already there is not an anchor")
  }

  @Test
  fun `a first screen has nothing before it, so everything on it qualifies as an anchor`() {
    val current = ArbigentElementList(listOf(element("Home", "nav_home", "")), screenWidth = 100)

    assertEquals(listOf("nav_home"), ArbigentReplayAnchors.select(current, previous = null).map { it.resourceId })
  }

  @Test
  fun `a screen that gained nothing has no anchors`() {
    val screen = ArbigentElementList(listOf(element("Home", "nav_home", "")), screenWidth = 100)

    assertEquals(emptyList(), ArbigentReplayAnchors.select(screen, screen))
  }

  /** A clock or a counter changes between runs on its own: it cannot anchor anything by its text. */
  @Test
  fun `volatile text is dropped from an anchor's identity or disqualifies the element`() {
    val current = ArbigentElementList(
      listOf(
        element("12:34", "clock", "12:34"),
        element("42", "", ""),
        element("3", "", ""),
        element("", "", "99%"),
        element("", "battery", "99%"),
      ),
      screenWidth = 100,
    )

    val anchors = ArbigentReplayAnchors.select(current, previous = null)

    assertEquals(
      listOf(ArbigentElementIdentity(resourceId = "clock"), ArbigentElementIdentity(resourceId = "battery")),
      anchors,
    )
  }

  @Test
  fun `an anchor is matched by presence alone, not by which twin it was`() {
    val twins = listOf(element("Play", "", ""), element("Play", "", ""))
    val anchors = ArbigentReplayAnchors.select(ArbigentElementList(twins, screenWidth = 100), previous = null)

    assertEquals(listOf(ArbigentElementIdentity(text = "Play")), anchors)
    assertTrue(ArbigentReplayAnchors.present(anchors, ArbigentElementList(twins.take(1), screenWidth = 100)))
  }

  @Test
  fun `a step with none of its anchors present is divergence`() = runTest {
    val interceptor = ArbigentReplayDecisionInterceptor(
      anchoredTrace(GoalAchievedAgentAction(), anchors = listOf(ArbigentElementIdentity(text = "Details"))),
    )

    val exception = assertFailsWith<ReplayDivergenceException> {
      interceptor.intercept(decisionInput(elementsOf(element("Other", "", "")))) { error("Should not reach the AI") }
    }
    assertTrue(
      exception.message.contains("showed 1 new element(s), such as text='Details'") &&
        exception.message.contains("none of them is present"),
      "Expected the reason to name an anchor, got: ${exception.message}",
    )
  }

  @Test
  fun `one anchor still present is enough to replay the step`() = runTest {
    val interceptor = ArbigentReplayDecisionInterceptor(
      anchoredTrace(
        GoalAchievedAgentAction(),
        anchors = listOf(ArbigentElementIdentity(text = "Gone"), ArbigentElementIdentity(text = "Details")),
      ),
    )

    val output = interceptor.intercept(decisionInput(elementsOf(element("Details", "", "")))) {
      error("Should not reach the AI")
    }

    assertEquals(ArbigentStepSource.Replay, output.step.stepSource)
  }

  @Test
  fun `a trace recorded before anchors were captured replays without the check`() = runTest {
    val interceptor = ArbigentReplayDecisionInterceptor(anchoredTrace(GoalAchievedAgentAction(), anchors = null))

    val output = interceptor.intercept(decisionInput(emptyElements)) { error("Should not reach the AI") }

    assertEquals(ArbigentStepSource.Replay, output.step.stepSource)
  }

  /** The anchors are checked on top of the focus wait, and read only once focus has arrived. */
  @Test
  fun `a step waits for an anchor on top of its recorded focus`() = runTest {
    withVirtualClock {
      val elsewhere = focusAt(200)
      val recorded = focusAt(400)
      val withoutAnchor = elementsOf(element("Loading", "", ""))
      val withAnchor = elementsOf(element("Details", "", ""))
      val device = ScriptedDevice(
        screens = listOf(withoutAnchor, withAnchor),
        focuses = listOf(elsewhere, recorded, recorded),
      )
      var proceeded = false

      ArbigentReplayPacingStepInterceptor(
        anchoredTrace(GoalAchievedAgentAction(), anchors = listOf(ArbigentElementIdentity(text = "Details")), focus = recorded),
      ).intercept(stepInput(device)) {
        proceeded = true
        ArbigentAgent.StepResult.Continue
      }

      assertTrue(proceeded)
      assertEquals(3, device.focusedElementCallCount, "the wait should end on the poll that saw both")
      assertEquals(2, device.elementsCallCount, "elements are read only on polls whose focus matched")
    }
  }

  @Test
  fun `a step with no target and no focus polls for its anchors instead of sleeping out its budget`() = runTest {
    withVirtualClock {
      val device = ScriptedDevice(
        screens = listOf(elementsOf(element("Loading", "", "")), elementsOf(element("Details", "", ""))),
      )
      var proceeded = false

      ArbigentReplayPacingStepInterceptor(
        anchoredTrace(GoalAchievedAgentAction(), anchors = listOf(ArbigentElementIdentity(text = "Details"))),
      ).intercept(stepInput(device)) {
        proceeded = true
        ArbigentAgent.StepResult.Continue
      }

      assertTrue(proceeded)
      assertEquals(2, device.elementsCallCount)
      assertTrue(currentTime < 10_000, "took $currentTime ms, a whole budget")
    }
  }

  @Test
  fun `typed text is derived when it was on a screen of the task and the goal did not give it`() {
    val goal = "Search for the first title on the home screen"
    val seen = setOf("Home", "  Some  Title ")

    assertTrue(ArbigentDerivedInput.isDerived(InputTextAgentAction("some title"), goal, seen))
    assertTrue(!ArbigentDerivedInput.isDerived(InputTextAgentAction("Some Title"), "Search for Some Title", seen))
    assertTrue(!ArbigentDerivedInput.isDerived(InputTextAgentAction("made up"), goal, seen), "the AI's own words cannot go stale")
    assertTrue(!ArbigentDerivedInput.isDerived(ClickWithTextAgentAction("Home"), goal, seen))
  }

  @Test
  fun `a step that typed text read off the screen is decided by the AI again on replay`() = runTest {
    val interceptor = ArbigentReplayDecisionInterceptor(
      anchoredTrace(InputTextAgentAction("Old title"), anchors = null, derivedInput = true),
    )
    val input = decisionInput(emptyElements)
    val decidedAgain = InputTextAgentAction("New title")

    val output = interceptor.intercept(input) {
      ArbigentAi.DecisionOutput(
        agentActions = listOf(decidedAgain),
        step = ArbigentContextHolder.Step(
          stepId = input.stepId,
          agentAction = decidedAgain,
          cacheKey = input.cacheKey,
          screenshotFilePath = input.screenshotFilePath,
        ),
      )
    }

    assertEquals(decidedAgain, output.agentActions.single())
    assertEquals(ArbigentStepSource.ReplayDelegated, output.step.stepSource)
  }

  @Test
  fun `a re-decided step keeps the recorded anchors and stays delegated`() = runTest {
    val recordedAnchors = listOf(ArbigentElementIdentity(text = "Title", resourceId = "title"))
    val interceptor = ArbigentReplayDecisionInterceptor(
      anchoredTrace(InputTextAgentAction("Old title"), anchors = recordedAnchors, derivedInput = true),
    )
    val input = decisionInput(elementsOf(element("Title", "title", "")))
    val decidedAgain = InputTextAgentAction("New title")

    val output = interceptor.intercept(input) {
      ArbigentAi.DecisionOutput(
        agentActions = listOf(decidedAgain),
        step = ArbigentContextHolder.Step(
          stepId = input.stepId,
          agentAction = decidedAgain,
          cacheKey = input.cacheKey,
          screenshotFilePath = input.screenshotFilePath,
        ),
      )
    }

    assertEquals(recordedAnchors, output.step.anchors)
    assertTrue(output.step.derivedInput)
  }

  @Test
  fun `the AI choosing not to type at a step that typed read-off text is divergence`() = runTest {
    val interceptor = ArbigentReplayDecisionInterceptor(
      anchoredTrace(InputTextAgentAction("Old title"), anchors = null, derivedInput = true),
    )
    val input = decisionInput(emptyElements)

    val exception = assertFailsWith<ReplayDivergenceException> {
      interceptor.intercept(input) {
        ArbigentAi.DecisionOutput(
          agentActions = listOf(GoalAchievedAgentAction()),
          step = ArbigentContextHolder.Step(
            stepId = input.stepId,
            agentAction = GoalAchievedAgentAction(),
            cacheKey = input.cacheKey,
            screenshotFilePath = input.screenshotFilePath,
          ),
        )
      }
    }
    assertTrue(
      exception.message.contains("typed text when it was recorded but the AI now decided to"),
      "got: ${exception.message}",
    )
  }

  /** The steps after a re-decided one replayed against the same recording, so it keeps the recorded pace. */
  @Test
  fun `a step the AI decided inside a replay counts as replayed when the trace is written`() {
    val recordedSteps = timestampedSteps(listOf(1_000, 5_000, 12_000))
    val recordedTrace = trace(recordedSteps.map { it to requireNotNull(it.agentAction) })
    val sources = listOf(ArbigentStepSource.Replay, ArbigentStepSource.ReplayDelegated, ArbigentStepSource.Replay)
    val freshSteps = recordedSteps.mapIndexed { index, step ->
      step.copy(timestamp = 100_000L + index * 100L, stepSource = sources[index])
    }
    val context = ArbigentContextHolder("goal", 10).apply { freshSteps.forEach(::addStep) }

    val candidate = ArbigentReplayTrace.candidateFrom(candidateKey(), context, recordedTrace = recordedTrace)
    val writtenTimestamps = candidate.steps.map { it.decisionOutput.step.timestamp }

    assertEquals(listOf(4_000L, 7_000L), writtenTimestamps.zipWithNext { a, b -> b - a })
    assertEquals(sources, candidate.steps.map { it.decisionOutput.step.stepSource })
  }

  @Test
  fun `anchors and the derived flag survive a trace round trip`() {
    val anchors = listOf(ArbigentElementIdentity(text = "Details"), ArbigentElementIdentity(resourceId = "clock"))
    val written = anchoredTrace(InputTextAgentAction("x"), anchors = anchors, derivedInput = true)

    val read = Json { ignoreUnknownKeys = true }.decodeFromString(
      ArbigentReplayTrace.serializer(),
      Json.encodeToString(ArbigentReplayTrace.serializer(), written),
    )

    assertEquals(anchors, read.steps.single().decisionOutput.step.anchors)
    assertTrue(read.steps.single().decisionOutput.step.derivedInput)
  }

  private fun elementsOf(vararg elements: ArbigentElement): ArbigentElementList =
    ArbigentElementList(elements.toList(), screenWidth = 100)

  private fun anchoredTrace(
    action: ArbigentAgentAction,
    anchors: List<ArbigentElementIdentity>?,
    derivedInput: Boolean = false,
    focus: ArbigentFocusedElement? = null,
  ): ArbigentReplayTrace = trace(
    listOf(
      ArbigentContextHolder.Step(
        stepId = "step-1",
        agentAction = action,
        cacheKey = "cache-key",
        screenshotFilePath = "screenshot.png",
        anchors = anchors,
        derivedInput = derivedInput,
        focusedElement = focus,
      ) to action,
    ),
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
