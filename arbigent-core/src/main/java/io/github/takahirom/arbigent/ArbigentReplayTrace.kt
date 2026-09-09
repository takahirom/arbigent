package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentStepSource

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import maestro.TreeNode
import java.io.File
import java.security.MessageDigest

public enum class ArbigentAttemptMode {
  Normal,
  ReplayWithFallback,
}

internal class ReplayDivergenceException(
  override val message: String,
) : Exception(message)

/**
 * Waits before a replayed step is captured, so the screen the step is about to be resolved against
 * is the screen the recorded action was decided on. This must run before [step] reads the elements
 * and UI tree: replay makes no AI call, so without it the next screen is snapshotted before it has
 * settled and an index-based action resolves against a half-built element list.
 *
 * How long a step may wait is the interval recorded between it and the step before it, minus what
 * replay has already spent executing the previous action. That interval is the recording run's own
 * time from one decision to the next, so it already contains everything the app was given then,
 * including the AI latency the recording paid and any `Wait` the AI itself decided on; within a
 * task, replay therefore waits no longer than the recording did, and no less than the app needs.
 * A step with no recorded interval — the first step of a task, which has no predecessor in the
 * trace — gets [MIN_WAIT_MILLIS] instead, because there is nothing to derive a budget from and a
 * task that starts by launching an app must not be stepped on at once; a task the recording got
 * through faster than that does wait longer here.
 *
 * Within that budget a step with a recorded target polls the elements until the target is present,
 * and proceeds the moment it is. Waiting further for the element list to stop changing was tried
 * and dropped: a screen with anything animating or ticking never stops changing, so the wait ran to
 * the budget every time and a screen that was in fact ready was reported as one the target never
 * reached. A step with no target to wait for (reaching the goal, back, scroll, wait, and text
 * actions recorded without an identity) waits its budget out.
 */
internal class ArbigentReplayPacingStepInterceptor(
  private val trace: ArbigentReplayTrace,
) : ArbigentStepInterceptor {
  private var previousStepStartedAtMillis: Long? = null

  override suspend fun intercept(
    stepInput: ArbigentAgent.StepInput,
    chain: ArbigentStepInterceptor.Chain,
  ): ArbigentAgent.StepResult {
    val replayIndex = stepInput.arbigentContextHolder.steps()
      .asSequence()
      .filter { step -> step.agentAction != null }
      .distinctBy { step -> step.stepId }
      .count()
    val budgetMillis = remainingBudgetMillis(replayIndex)
    val identity = trace.steps.getOrNull(replayIndex)?.decisionOutput?.step?.targetElement
    if (identity != null) {
      awaitTarget(replayIndex, identity, stepInput.device, budgetMillis)
    } else {
      waitOutBudget(replayIndex, budgetMillis)
    }
    previousStepStartedAtMillis = TimeProvider.get().currentTimeMillis()
    return chain.proceed(stepInput)
  }

  /**
   * How long this step may still wait: what is left of the recorded interval after the time replay
   * has already spent since the previous step began. The cap is applied to the recorded interval
   * itself, so a pathological recording does not become a minute of waiting plus whatever the
   * previous action took.
   */
  private fun remainingBudgetMillis(replayIndex: Int): Long {
    val recordedGap = recordedGapMillis(replayIndex) ?: return MIN_WAIT_MILLIS
    val budget = recordedGap.coerceAtMost(MAX_WAIT_MILLIS)
    // Nothing measured to subtract: this is the budget in full rather than a guess at what is left.
    val previousStartedAt = previousStepStartedAtMillis ?: return budget
    val alreadySpent = TimeProvider.get().currentTimeMillis() - previousStartedAt
    return (budget - alreadySpent).coerceAtLeast(0)
  }

  private fun recordedGapMillis(replayIndex: Int): Long? {
    val previousRecordedAt = trace.steps.getOrNull(replayIndex - 1)
      ?.decisionOutput?.step?.timestamp ?: return null
    val recordedAt = trace.steps.getOrNull(replayIndex)?.decisionOutput?.step?.timestamp
      ?: return null
    return (recordedAt - previousRecordedAt).takeIf { it > 0 }
  }

  /**
   * Polls until the recorded target is present, or until [budgetMillis] is spent. The screen is
   * read once before the budget is consulted, so a step whose budget is already gone still gets the
   * chance to find its target on a screen that is in fact ready — and a target that never appears
   * is left for the decision interceptor to report as a divergence, which is what it is.
   */
  private suspend fun awaitTarget(
    replayIndex: Int,
    identity: ArbigentElementIdentity,
    device: ArbigentDevice,
    budgetMillis: Long,
  ) {
    // Everything between here and the return is charged to the budget, measured on the clock
    // rather than summed from what was asked for: reading the hierarchy is synchronous and can
    // take a while on a busy device, and a delay can resume well after the interval it requested.
    // Adding up only the requested delays would let either one push the wait past the recorded
    // interval it is supposed to fit inside.
    val startedAtMillis = TimeProvider.get().currentTimeMillis()
    var waitedMillis = 0L
    while (true) {
      val present = isPresent(device, identity)
      waitedMillis = (TimeProvider.get().currentTimeMillis() - startedAtMillis).coerceAtLeast(0)
      if (present) {
        arbigentInfoLog(
          "Replay wait: target ${identity.description()} found after ${waitedMillis}ms " +
            "before capturing step ${replayIndex + 1}",
        )
        return
      }
      if (waitedMillis >= budgetMillis) break
      delay(POLL_INTERVAL_MILLIS.coerceAtMost(budgetMillis - waitedMillis))
    }
    arbigentInfoLog(
      "Replay wait: budget ${budgetMillis}ms spent waiting for target ${identity.description()} " +
        "before capturing step ${replayIndex + 1}; capturing anyway",
    )
  }

  private fun isPresent(device: ArbigentDevice, identity: ArbigentElementIdentity): Boolean {
    val elements = try {
      device.elements()
    } catch (exception: Exception) {
      // Reading the hierarchy can fail while the screen is mid-transition, which is exactly the
      // state this waits out. Treat it as "not there yet".
      arbigentDebugLog("Replay wait: could not read elements: $exception")
      return false
    }
    return identity.findMatch(elements) != null
  }

  private suspend fun waitOutBudget(replayIndex: Int, budgetMillis: Long) {
    if (budgetMillis <= 0) return
    arbigentInfoLog(
      "Replay pacing: waiting ${budgetMillis}ms before capturing step ${replayIndex + 1}",
    )
    delay(budgetMillis)
  }

  private companion object {
    // A recorded gap can be pathological (a hiccup while recording); do not inherit it unbounded.
    const val MAX_WAIT_MILLIS = 60_000L

    // Without a recorded interval there is nothing to derive a budget from, and the step this
    // happens on is the one that starts a task — the launch replay must not step on.
    const val MIN_WAIT_MILLIS = 10_000L
    const val POLL_INTERVAL_MILLIS = 500L
  }
}

internal class ArbigentReplayDecisionInterceptor(
  private val trace: ArbigentReplayTrace,
) : ArbigentDecisionInterceptor {
  override suspend fun intercept(
    decisionInput: ArbigentAi.DecisionInput,
    chain: ArbigentDecisionInterceptor.Chain,
  ): ArbigentAi.DecisionOutput {
    val replayIndex = decisionInput.contextHolder.steps()
      .asSequence()
      .filter { step -> step.agentAction != null }
      .distinctBy { step -> step.stepId }
      .count()
    val recorded = trace.steps.getOrNull(replayIndex)
      ?: throw ReplayDivergenceException(
        "trace ended before replay step ${replayIndex + 1}",
      )
    val recordedAction = recorded.decisionOutput.agentActions.singleOrNull()
      ?: throw ReplayDivergenceException(
        "trace step ${replayIndex + 1} does not contain exactly one action",
      )
    // An identity is an optimization, not a requirement: when the recorded element carried one we
    // re-locate it so the action survives a reordered list, and its absence is real divergence.
    // Elements with no identifying attributes at all (common for TV cards driven by D-pad index)
    // are replayed as recorded — the image assertions and the fallback are what keep replay honest.
    // A recorded index means nothing on a shorter element list, and the actions that carry one
    // index into it directly. Catching it here turns a stray IndexOutOfBoundsException thrown
    // mid-action into ordinary divergence, before anything has been tapped.
    recordedAction.recordedElementIndex()?.let { index ->
      if (index !in decisionInput.elements.elements.indices) {
        throw ReplayDivergenceException(
          "step ${replayIndex + 1} targets element $index but the current screen has " +
            "${decisionInput.elements.elements.size}",
        )
      }
    }
    val identity = recorded.decisionOutput.step.targetElement
    val reboundAction = if (identity != null) {
      val currentElement = identity.findMatch(decisionInput.elements)
        ?: throw ReplayDivergenceException(
          "step ${replayIndex + 1} target ${identity.description()} is absent from the current UI",
        )
      recordedAction.rebindTo(currentElement, decisionInput.elements)
    } else {
      recordedAction
    }

    arbigentInfoLog("Replay step ${replayIndex + 1}: ${reboundAction.stepLogText()}")
    return recorded.decisionOutput.copy(
      agentActions = listOf(reboundAction),
      step = recorded.decisionOutput.step.copy(
        stepId = decisionInput.stepId,
        agentAction = reboundAction,
        cacheKey = decisionInput.cacheKey,
        timestamp = TimeProvider.get().currentTimeMillis(),
        screenshotFilePath = decisionInput.screenshotFilePath,
        // No AI call was made, so nothing wrote a JSONL for this step. Carrying the path anyway
        // would name an artifact that does not exist, and the report copies every path it is given.
        apiCallJsonLFilePath = null,
        stepSource = ArbigentStepSource.Replay,
      ),
    )
  }

}

@Serializable
public data class ArbigentElementIdentity(
  public val text: String? = null,
  public val resourceId: String? = null,
  public val accessibilityId: String? = null,
  public val occurrence: Int = 0,
) {
  init {
    require(text != null || resourceId != null || accessibilityId != null) {
      "An element identity must contain at least one identifying attribute"
    }
  }

  public fun findMatch(elements: ArbigentElementList): ArbigentElement? {
    return elements.elements
      .filter(::matches)
      .getOrNull(occurrence)
  }

  private fun matches(element: ArbigentElement): Boolean =
    text.matchesAttribute(element, TEXT_ATTRIBUTE_KEYS) &&
      resourceId.matchesAttribute(element, RESOURCE_ID_ATTRIBUTE_KEYS) &&
      accessibilityId.matchesAttribute(element, ACCESSIBILITY_ATTRIBUTE_KEYS)

  public fun description(): String = listOfNotNull(
    text?.let { "text='$it'" },
    resourceId?.let { "resourceId='$it'" },
    accessibilityId?.let { "accessibilityId='$it'" },
  ).joinToString(", ") + " (occurrence $occurrence)"

  private fun String?.matchesAttribute(
    element: ArbigentElement,
    keys: List<String>,
  ): Boolean = this == null || element.firstNonBlank(keys) == this

  public companion object {
    public fun from(
      element: ArbigentElement,
      allElements: List<ArbigentElement>,
    ): ArbigentElementIdentity? {
      val text = element.firstNonBlank(TEXT_ATTRIBUTE_KEYS)
      val resourceId = element.firstNonBlank(RESOURCE_ID_ATTRIBUTE_KEYS)
      val accessibilityId = element.firstNonBlank(ACCESSIBILITY_ATTRIBUTE_KEYS)
      if (text == null && resourceId == null && accessibilityId == null) return null

      val identityWithoutOccurrence = ArbigentElementIdentity(
        text = text,
        resourceId = resourceId,
        accessibilityId = accessibilityId,
      )
      val occurrence = allElements.filter(identityWithoutOccurrence::matches).indexOf(element)
      if (occurrence < 0) return null
      return identityWithoutOccurrence.copy(occurrence = occurrence)
    }

    private val TEXT_ATTRIBUTE_KEYS = listOf("text", "value")
    private val RESOURCE_ID_ATTRIBUTE_KEYS = listOf("resource-id", "resourceId", "id")
    private val ACCESSIBILITY_ATTRIBUTE_KEYS = listOf(
      "accessibilityText",
      "content-desc",
      "contentDescription",
      "accessibility-id",
      "label",
      "name",
    )

    /**
     * First non-blank value for [keys], searched over the element's own node and then its
     * descendants, the same way the element text shown to the AI is built.
     *
     * Reading only the node's own attributes made this return null for the shape an Android TV
     * card or tab actually has: the focusable container carries no text, resource-id or
     * accessibility text at all, and the text lives in a non-clickable child TextView. No identity
     * meant no identity was recorded, so a replayed index action was neither rebound to the
     * element's current position nor reported as a divergence — it silently operated on whatever
     * happened to sit at the recorded index.
     */
    private fun ArbigentElement.firstNonBlank(keys: List<String>): String? =
      keys.firstNotNullOfOrNull { key ->
        treeNode.dfs { node -> node.attributes[key].nonBlankOrNull() != null }
          ?.attributes?.get(key).nonBlankOrNull()
      }

    private fun TreeNode.dfs(condition: (TreeNode) -> Boolean): TreeNode? {
      if (condition(this)) return this
      return children.firstNotNullOfOrNull { child -> child.dfs(condition) }
    }

    private fun String?.nonBlankOrNull(): String? = this?.takeIf(String::isNotBlank)
  }
}

internal fun ArbigentAgentAction.withTargetIdentity(
  elements: ArbigentElementList,
): ArbigentElementIdentity? {
  val target = targetElement(elements) ?: return null
  return ArbigentElementIdentity.from(target, elements.elements)
}

private fun ArbigentAgentAction.targetElement(elements: ArbigentElementList): ArbigentElement? = when (this) {
  is ClickWithIndex -> elements.elements.getOrNull(index)
  is DpadAutoFocusWithIndexAgentAction -> elements.elements.getOrNull(index)
  is ClickWithTextAgentAction -> elements.elementMatchingText(textRegex)
  is ClickWithIdAgentAction -> elements.elementMatchingResourceId(textRegex)
  is DpadAutoFocusWithTextAgentAction -> elements.elementMatchingText(text)
  is DpadAutoFocusWithIdAgentAction -> elements.elementMatchingResourceId(id)
  // Coordinate actions explicitly target content outside the UI hierarchy and therefore cannot
  // be replayed safely in replay mode.
  is ClickAtCoordinates -> null
  else -> null
}

private fun ArbigentAgentAction.recordedElementIndex(): Int? = when (this) {
  is ClickWithIndex -> index
  is DpadAutoFocusWithIndexAgentAction -> index
  else -> null
}

private fun ArbigentAgentAction.rebindTo(
  target: ArbigentElement,
  elements: ArbigentElementList,
): ArbigentAgentAction {
  val currentIndex = elements.elements.indexOf(target)
  return when (this) {
    is ClickWithIndex -> copy(index = currentIndex)
    is DpadAutoFocusWithIndexAgentAction -> copy(index = currentIndex)
    else -> this
  }
}

private fun ArbigentElementList.elementMatchingText(selector: String): ArbigentElement? {
  val (pattern, occurrence) = selectorAndOccurrence(selector)
  return elements.filter { element ->
    val attributes = element.treeNode.attributes
    listOf("text", "value", "accessibilityText", "content-desc", "contentDescription", "label", "name")
      .mapNotNull(attributes::get)
      .any(pattern::matches)
  }.getOrNull(occurrence)
}

private fun ArbigentElementList.elementMatchingResourceId(selector: String): ArbigentElement? {
  val (pattern, occurrence) = selectorAndOccurrence(selector)
  return elements.filter { element ->
    val attributes = element.treeNode.attributes
    listOf("resource-id", "resourceId", "id")
      .mapNotNull(attributes::get)
      .any(pattern::matches)
  }.getOrNull(occurrence)
}

private fun selectorAndOccurrence(selector: String): Pair<Regex, Int> {
  val match = Regex("""(.*)\[(\d+)]$""").matchEntire(selector)
  val patternText = match?.groupValues?.get(1) ?: selector
  val occurrence = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
  val pattern = try {
    Regex(patternText)
  } catch (_: IllegalArgumentException) {
    Regex(Regex.escape(patternText))
  }
  return pattern to occurrence
}

@Serializable
internal data class ArbigentReplayTraceStep(
  val decisionOutput: ArbigentAi.DecisionOutput,
)

@Serializable
internal data class ArbigentReplayTrace(
  val version: String,
  val scenarioId: String,
  val taskIndex: Int,
  val taskIdentity: String,
  val goalHash: String,
  val steps: List<ArbigentReplayTraceStep>,
) {
  fun isValidFor(key: ArbigentReplayTraceKey): Boolean = invalidReasonFor(key) == null

  /**
   * Why this trace cannot be replayed, or null when it can. A trace is rejected as a whole, so
   * without a reason a scenario silently never replays and the cause is invisible.
   */
  fun invalidReasonFor(key: ArbigentReplayTraceKey): String? {
    if (version != key.version) return "it was recorded by arbigent $version, not ${key.version}"
    if (scenarioId != key.scenarioId) return "it was recorded for scenario $scenarioId"
    if (taskIndex != key.taskIndex) return "it was recorded for task ${taskIndex + 1}"
    if (taskIdentity != key.taskIdentity) return "it was recorded for a different task"
    if (goalHash != key.goalHash) return "the goal changed since it was recorded"
    if (steps.isEmpty()) return "it has no steps"
    steps.forEachIndexed { index, traceStep ->
      traceStep.decisionOutput.agentActions.singleOrNull()
        ?: return "step ${index + 1} does not contain exactly one action"
    }
    if (steps.last().decisionOutput.agentActions.none { it is GoalAchievedAgentAction }) {
      return "it does not end by reaching the goal"
    }
    return null
  }

  companion object {
    /**
     * The trace this run would produce. Ask [invalidReasonFor] whether it can actually be stored;
     * building it unconditionally is what lets the caller report why it was rejected.
     */
    fun candidateFrom(
      key: ArbigentReplayTraceKey,
      contextHolder: ArbigentContextHolder,
      precedingSteps: List<ArbigentContextHolder.Step> = emptyList(),
      recordedTrace: ArbigentReplayTrace? = null,
    ): ArbigentReplayTrace {
      val prefix = precedingSteps.replayable()
      val steps = prefix + contextHolder.steps().replayable()
      // Replayed steps carry the time the replay reached them, which is faster than the AI was. Writing
      // that back would make the next replay's budget the pace of this one instead of the AI's, and
      // every clean replay would tighten it further. The recorded intervals are kept instead, anchored
      // on the last replayed step so the gap to the replacement agent's first AI decision stays the
      // real one; anchoring on the first could leave that gap non-positive.
      val recordedTimestamps = recordedTrace?.steps?.map { it.decisionOutput.step.timestamp }.orEmpty()
      val replayedCount = if (recordedTrace == null) 0 else {
        prefix.size + steps.drop(prefix.size).takeWhile { it.stepSource == ArbigentStepSource.Replay }.size
      }
      require(steps.take(replayedCount).all { it.stepSource == ArbigentStepSource.Replay }) {
        "Every step in the replayed prefix must come from replay"
      }
      require(replayedCount <= recordedTimestamps.size) {
        "The replayed prefix cannot be longer than its recorded trace"
      }
      val shift = if (replayedCount == 0) 0L else {
        steps[replayedCount - 1].timestamp - recordedTimestamps[replayedCount - 1]
      }
      return ArbigentReplayTrace(
        version = key.version,
        scenarioId = key.scenarioId,
        taskIndex = key.taskIndex,
        taskIdentity = key.taskIdentity,
        goalHash = key.goalHash,
        steps = steps.mapIndexed { index, step ->
          ArbigentReplayTraceStep(
            decisionOutput = ArbigentAi.DecisionOutput(
              agentActions = listOf(requireNotNull(step.agentAction)),
              step = if (index < replayedCount) {
                step.copy(timestamp = recordedTimestamps[index] + shift)
              } else {
                step
              },
            ),
          )
        },
      )
    }

    /**
     * The actions of these steps, in order. Steps carrying no action are feedback the agent left
     * for itself, and a step id repeats when several were recorded for one decision.
     */
    private fun List<ArbigentContextHolder.Step>.replayable(): List<ArbigentContextHolder.Step> =
      asSequence()
        .filter { step -> step.agentAction != null }
        .distinctBy { step -> step.stepId }
        .toList()
  }
}

internal data class ArbigentReplayTraceKey(
  val version: String,
  val scenarioId: String,
  val taskIndex: Int,
  val taskIdentity: String,
  val goal: String,
) {
  val goalHash: String = goal.sha256()

  /**
   * File name of this trace. Every part is folded into a single hash instead of being spelled out,
   * because [taskIdentity] is the resolved goal and hints of the task: encoding it produced names
   * of 300 to 900 bytes for Japanese text, well past the 255 byte limit a single file name
   * component has on ext4 and APFS, and every write failed with FileNotFoundException so no
   * scenario could ever replay. Nothing is lost by not naming the parts: the trace file itself
   * carries the scenario id, task index and goal hash, and reading one validates them again.
   */
  val storageKey: String = listOf(
    version,
    scenarioId,
    taskIndex.toString(),
    taskIdentity,
    goalHash,
  ).joinToString(separator = "\u0000").sha256()
}

internal class ArbigentReplayTraceStore(
  private val directory: () -> File = { ArbigentFiles.traceDir },
) {
  private val json = Json {
    ignoreUnknownKeys = true
    useArrayPolymorphism = true
    prettyPrint = true
  }

  fun read(key: ArbigentReplayTraceKey): ArbigentReplayTrace? {
    val file = fileFor(key)
    if (!file.isFile) return null
    return try {
      json.decodeFromString<ArbigentReplayTrace>(file.readText())
        .takeIf { it.isValidFor(key) }
    } catch (exception: Exception) {
      arbigentErrorLog("Failed to read replay trace ${key.storageKey}: $exception")
      null
    }
  }

  fun write(key: ArbigentReplayTraceKey, trace: ArbigentReplayTrace) {
    require(trace.isValidFor(key)) { "Cannot store an invalid replay trace" }
    // A trace is an optimization for the next run, so failing to store one must not turn the
    // scenario that just passed into a failure.
    try {
      val file = fileFor(key)
      file.parentFile.mkdirs()
      file.writeText(json.encodeToString(trace))
    } catch (exception: Exception) {
      arbigentErrorLog("Failed to store replay trace ${key.storageKey}: $exception")
    }
  }

  fun delete(key: ArbigentReplayTraceKey) {
    val file = fileFor(key)
    if (file.exists() && !file.delete()) {
      arbigentErrorLog("Failed to delete replay trace ${key.storageKey}")
    }
  }

  private fun fileFor(key: ArbigentReplayTraceKey): File =
    File(directory(), "${key.storageKey}.json")
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
  .digest(toByteArray())
  .joinToString("") { byte -> "%02x".format(byte) }
