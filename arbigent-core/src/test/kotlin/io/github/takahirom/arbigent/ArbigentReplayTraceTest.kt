package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.sample.test.FakeAi
import io.github.takahirom.arbigent.sample.test.FakeDevice
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
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
  fun `trace round trip preserves actions and target identity`() {
    val directory = Files.createTempDirectory("arbigent-replay-trace-test").toFile()
    val store = ArbigentReplayTraceStore { directory }
    val key = ArbigentReplayTraceKey(
      version = "1.2.3",
      scenarioId = "open-model-page",
      taskIndex = 0,
      taskIdentity = "open-model-page",
      goal = "Open the model page",
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
    )
    val second = first.copy(taskIndex = 1)

    store.write(first, minimalTrace(first))
    store.write(second, minimalTrace(second))

    assertEquals(2, directory.listFiles().orEmpty().size)
    assertEquals(0, assertNotNull(store.read(first)).taskIndex)
    assertEquals(1, assertNotNull(store.read(second)).taskIndex)
  }

  @Test
  fun `a replayed step waits until its recorded target is present on two polls in a row`() =
    runTest {
      val absent = ArbigentElementList(emptyList(), screenWidth = 1000)
      val present = ArbigentElementList(listOf(element("target", "id", "desc")), screenWidth = 1000)
      val device = ScriptedDevice(listOf(absent, absent, present, present))
      var proceeded = false

      ArbigentReplayPacingStepInterceptor(traceWithTarget()).intercept(
        stepInput(device),
      ) {
        proceeded = true
        ArbigentAgent.StepResult.Continue
      }

      assertTrue(proceeded, "the step should have been captured once the target settled")
      assertEquals(
        4,
        device.elementsCallCount,
        "the target should be confirmed on two consecutive polls, no more and no less",
      )
      assertEquals(1500, currentTime, "each poll is one interval apart")
    }

  @Test
  fun `a target that is still moving is not treated as settled`() = runTest {
    // Same text, same id, different place: the element is animating into position.
    val moving = ArbigentElementList(listOf(element("target", "id", "desc", y = 100)), screenWidth = 1000)
    val settled = ArbigentElementList(listOf(element("target", "id", "desc", y = 0)), screenWidth = 1000)
    val device = ScriptedDevice(listOf(moving, settled, settled))
    var proceeded = false

    ArbigentReplayPacingStepInterceptor(traceWithTarget()).intercept(stepInput(device)) {
      proceeded = true
      ArbigentAgent.StepResult.Continue
    }

    assertTrue(proceeded)
    assertEquals(
      3,
      device.elementsCallCount,
      "a change in bounds alone must count as an unsettled screen and cost one more poll",
    )
    assertEquals(1000, currentTime)
  }

  @Test
  fun `a replayed step whose target never appears is captured once the deadline passes`() = runTest {
    val absent = ArbigentElementList(emptyList(), screenWidth = 1000)
    val device = ScriptedDevice(listOf(absent))
    var proceeded = false

    ArbigentReplayPacingStepInterceptor(traceWithTarget()).intercept(stepInput(device)) {
      proceeded = true
      ArbigentAgent.StepResult.Continue
    }

    assertTrue(proceeded, "a target that never appears is divergence to detect later, not here")
    assertEquals(10_000, currentTime, "the wait should end at the ten second floor")
    assertTrue(device.elementsCallCount >= 2, "the deadline should have been polled towards")
  }

  @Test
  fun `a replayed step with no recorded target keeps the recorded pace`() = runTest {
    val trace = traceWithoutTarget(firstTimestamp = 1_000, secondTimestamp = 4_000)
    val device = ScriptedDevice(listOf(ArbigentElementList(emptyList(), screenWidth = 1000)))
    val contextHolder = ArbigentContextHolder("goal", 10)
    val interceptor = ArbigentReplayPacingStepInterceptor(trace)

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

    assertTrue(
      currentTime in 2_000..3_000,
      "the second step should have waited out the recorded gap, but waited ${currentTime}ms",
    )
    assertEquals(0, device.elementsCallCount, "pacing should not read the screen")
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

  private fun traceWithTarget(): ArbigentReplayTrace {
    val action = ClickWithTextAgentAction("target")
    return trace(
      listOf(
        ArbigentContextHolder.Step(
          stepId = "step-1",
          agentAction = action,
          cacheKey = "cache-key",
          screenshotFilePath = "screenshot.png",
          targetElement = ArbigentElementIdentity(text = "target"),
        ) to action,
      ),
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
