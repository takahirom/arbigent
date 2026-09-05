package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.ArbigentAgent.ExecuteActionsInput
import io.github.takahirom.arbigent.ArbigentAgent.ExecuteActionsOutput
import java.io.File

/**
 * Collects, for one scenario execution, what was actually sent to the device and which agent step
 * sent it, so a replay script can be written from the run.
 *
 * The device layer knows what was sent but not why; the agent knows why but not what. This joins
 * the two: it listens for device events and, as an execute-actions interceptor, marks which step is
 * running while they arrive. Events that arrive outside any step (initializers, which run before
 * the first step) are attributed to the task's `init` phase.
 *
 * One instance per scenario execution. It is written from the agent's coroutine and read from the
 * executor's, so every mutation is guarded.
 */
internal class ArbigentReplayScriptRecorder : ArbigentExecuteActionsInterceptor, ArbigentDeviceEventListener {

  /**
   * Where the step's target was on screen, in the coordinate space Arbigent's own element bounds
   * use.
   *
   * Maestro reads the hierarchy through its own instrumentation and sees attributes, notably
   * Compose test tags surfaced as resource ids, that `android layout` and `uiautomator dump` do
   * not. Recording the geometry as well gives a replay a way to reach the element by coordinates
   * when its identity is invisible from the adb side.
   */
  internal data class RecordedBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  ) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
  }

  internal data class RecordedStep(
    val isInit: Boolean,
    val actionName: String? = null,
    val actionLog: String? = null,
    val memo: String? = null,
    val screenshot: String? = null,
    val target: ArbigentElementIdentity? = null,
    val targetBounds: RecordedBounds? = null,
    /**
     * A few identities from the screen the decision was made on, labelled elements first. A step
     * with no target (a bare key press) has nothing else that says which screen it belongs to, so a
     * replay waits for one of these before sending it rather than pressing into a splash screen.
     */
    val screen: List<ArbigentElementIdentity> = emptyList(),
    val events: MutableList<ArbigentDeviceEvent> = mutableListOf(),
    val timestamp: Long = TimeProvider.get().currentTimeMillis(),
  )

  internal data class RecordedTask(
    val taskIndex: Int,
    val goal: String,
    val steps: MutableList<RecordedStep> = mutableListOf(),
  )

  private val lock = Any()
  private val tasks = linkedMapOf<Int, RecordedTask>()
  private var currentTaskIndex: Int? = null
  private var currentStep: RecordedStep? = null
  private var screenWidth: Int = 0
  private var screenHeight: Int = 0

  /**
   * The screen size the steps' coordinates are in, or `0 to 0` when no step reported one. Read
   * from the element lists the decisions saw rather than from the device, so it is always the same
   * space the recorded bounds are in.
   */
  fun screenSize(): Pair<Int, Int> = synchronized(lock) { screenWidth to screenHeight }

  /** Everything recorded so far, in task order. */
  fun recordedTasks(): List<RecordedTask> = synchronized(lock) {
    tasks.values.map { task -> task.copy(steps = task.steps.map { it.copy(events = it.events.toMutableList()) }.toMutableList()) }
  }

  /** Forgets the whole scenario. Called when the executor restarts every task from the top. */
  fun reset(): Unit = synchronized(lock) {
    tasks.clear()
    currentTaskIndex = null
    currentStep = null
    screenWidth = 0
    screenHeight = 0
  }

  /**
   * Marks [taskIndex] as the task now running.
   *
   * [discardPrevious] is for the executor's per-task fallback, which replaces one task's agent and
   * runs it again. A task that resets the device state starts from the same screen it did before,
   * so its earlier recording is superseded and must go; a task that carries on from the previous
   * one needs what it already did kept in front of what it is about to do, or the script would
   * replay from a screen that never existed.
   */
  fun beginTask(taskIndex: Int, goal: String, discardPrevious: Boolean): Unit = synchronized(lock) {
    val task = tasks.getOrPut(taskIndex) { RecordedTask(taskIndex, goal) }
    if (discardPrevious) task.steps.clear()
    currentTaskIndex = taskIndex
    currentStep = null
  }

  override fun onDeviceEvent(event: ArbigentDeviceEvent) {
    synchronized(lock) {
      val step = currentStep ?: initStep() ?: return
      step.events += event
    }
  }

  override suspend fun intercept(
    executeActionsInput: ExecuteActionsInput,
    chain: ArbigentExecuteActionsInterceptor.Chain,
  ): ExecuteActionsOutput {
    val step = executeActionsInput.decisionOutput.step
    val elements = executeActionsInput.elements
    val recorded = RecordedStep(
      isInit = false,
      actionName = step.agentAction?.let { it::class.simpleName },
      actionLog = step.agentAction?.stepLogText(),
      memo = step.memo,
      screenshot = File(executeActionsInput.screenshotFilePath).name,
      // Already resolved against the elements the decision saw; recomputing it here would match
      // against a hierarchy that the action is about to change.
      target = step.targetElement,
      targetBounds = step.targetElement?.findMatch(elements)?.rect?.let {
        RecordedBounds(it.left, it.top, it.right, it.bottom)
      },
      screen = screenIdentities(elements.elements),
    )
    synchronized(lock) {
      if (elements.screenWidth > 0) screenWidth = elements.screenWidth
      if (elements.screenHeight > 0) screenHeight = elements.screenHeight
      currentTask()?.steps?.add(recorded)
      currentStep = recorded
    }
    return try {
      chain.proceed(executeActionsInput)
    } finally {
      synchronized(lock) { currentStep = null }
    }
  }

  /**
   * The `init` bucket events outside any step go to. Appended rather than kept at the head, because
   * a task that falls back to normal execution runs its initializers again after the steps it had
   * already replayed, and a replay has to perform them in that same order.
   */
  private fun initStep(): RecordedStep? {
    val task = currentTask() ?: return null
    val last = task.steps.lastOrNull()
    if (last != null && last.isInit) return last
    val created = RecordedStep(isInit = true)
    task.steps.add(created)
    return created
  }

  private fun currentTask(): RecordedTask? = currentTaskIndex?.let { tasks[it] }

  internal companion object {
    const val MaxScreenIdentities: Int = 5

    /**
     * Elements with visible text come first because container ids (`content`, `root_layout`) are on
     * every screen of an app and would say nothing about which one this is.
     */
    fun screenIdentities(elements: List<ArbigentElement>): List<ArbigentElementIdentity> =
      elements
        .mapNotNull { element -> ArbigentElementIdentity.from(element, elements) }
        .map { it.copy(occurrence = 0) }
        .distinct()
        .sortedBy { if (it.text != null) 0 else 1 }
        .take(MaxScreenIdentities)
  }
}
