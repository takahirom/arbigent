package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.coroutines.buildSingleSourceStateFlow
import io.github.takahirom.arbigent.coroutines.buildFlatMapLatestSingleSourceStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

public data class ArbigentScenarioRunningInfo(
  val allTasks: Int,
  val runningTasks: Int,
  val maxStep: Int,
  val currentStep: Int,
  val retriedTasks: Int,
  val maxRetry: Int,
) {
  override fun toString(): String {
    return """
        task: $runningTasks/$allTasks
        step: $currentStep (limit: $maxStep)
        retry: $retriedTasks/$maxRetry
    """.trimIndent()
  }
}

public data class ArbigentImageAssertions(
  val assertions: List<ArbigentImageAssertion> = listOf(),
  val historyCount: Int = 1,
) {
  public operator fun plus(assertions: ArbigentImageAssertions): ArbigentImageAssertions {
    return ArbigentImageAssertions(
      assertions = this.assertions + assertions.assertions,
      historyCount = maxOf(this.historyCount, assertions.historyCount)
    )
  }

  public fun isEmpty(): Boolean {
    return assertions.isEmpty()
  }

  public fun assertionPromptSummary(): String {
    return assertions.joinToString("\n") { it.assertionPrompt }
  }
}

@Serializable
public data class ArbigentImageAssertion(
  public val assertionPrompt: String,
  public val requiredFulfillmentPercent: Int = 80,
)

public sealed interface ArbigentScenarioExecutorState {
  public object Idle : ArbigentScenarioExecutorState
  public object Running : ArbigentScenarioExecutorState
  public object Success : ArbigentScenarioExecutorState
  public object Failed : ArbigentScenarioExecutorState

  public fun name(): String = when (this) {
    Idle -> "Idle"
    Running -> "Running"
    Success -> "Success"
    Failed -> "Failed"
  }
}

public class ArbigentScenarioExecutor internal constructor(
  // Required: threaded into every ArbigentAgent this executor creates, originating at the
  // application composition root. No default so the compiler rejects any path that forgets it.
  private val dispatcher: CoroutineDispatcher,
) {
  private val replayTraceStore = ArbigentReplayTraceStore()
  private val _taskAssignmentsStateFlow =
    MutableStateFlow<List<ArbigentTaskAssignment>>(listOf())
  private val _taskAssignmentsHistoryStateFlow =
    MutableStateFlow<List<List<ArbigentTaskAssignment>>>(listOf())
  public val taskAssignmentsHistoryFlow: Flow<List<List<ArbigentTaskAssignment>>> =
    _taskAssignmentsHistoryStateFlow.asSharedFlow()

  public fun taskAssignmentsHistory(): List<List<ArbigentTaskAssignment>> =
    _taskAssignmentsHistoryStateFlow.value

  public val taskAssignmentsFlow: Flow<List<ArbigentTaskAssignment>> =
    _taskAssignmentsStateFlow.asSharedFlow()

  public fun taskAssignments(): List<ArbigentTaskAssignment> = _taskAssignmentsStateFlow.value
  private var executeJob: Job? = null
  private val coroutineScope =
    CoroutineScope(dispatcher + SupervisorJob())
  private val _arbigentScenarioRunningInfoStateFlow: MutableStateFlow<ArbigentScenarioRunningInfo?> =
    MutableStateFlow(null)
  public val runningInfoFlow: StateFlow<ArbigentScenarioRunningInfo?> =
    coroutineScope.buildSingleSourceStateFlow(_arbigentScenarioRunningInfoStateFlow) {
      it
    }

  public fun runningInfo(): ArbigentScenarioRunningInfo? =
    runningInfoFlow.value

  public val isSuccessFlow: StateFlow<Boolean> = coroutineScope.buildFlatMapLatestSingleSourceStateFlow(
    _taskAssignmentsStateFlow,
    transformForFlow = { taskToAgents ->
      if (taskToAgents.isEmpty()) {
        return@buildFlatMapLatestSingleSourceStateFlow flowOf(false)
      }
      combine(taskToAgents.map { it.agent.isGoalAchievedFlow }) { booleans ->
        booleans.all { it }
      }
    },
    transformForValue = { taskToAgents: List<ArbigentTaskAssignment> ->
      if (taskToAgents.isEmpty()) {
        return@buildFlatMapLatestSingleSourceStateFlow false
      }
      taskToAgents.all { it.agent.isGoalAchieved() }
    }
  )

  public fun isSuccessful(): Boolean {
    return isSuccessFlow.value
  }

  private val _isFailedToArchiveFlow = MutableStateFlow(false)
  public val isFailedToArchiveFlow: Flow<Boolean> = _isFailedToArchiveFlow.asSharedFlow()
  public fun isFailedToArchive(): Boolean = _isFailedToArchiveFlow.value

  // isAchievedStateFlow is WhileSubscribed so we can't use it in waitUntilFinished
  public fun isGoalAchieved(): Boolean {
    if (taskAssignments().isEmpty()) {
      return false
    }
    return taskAssignments().all { it.agent.isGoalAchieved() }
  }

  public val isRunningFlow: Flow<Boolean> = taskAssignmentsFlow.flatMapLatest { taskToAgents ->
    val flows: List<Flow<Boolean>> = taskToAgents.map { taskToAgent ->
      taskToAgent.agent.isRunningFlow
    }
    combine(flows) { booleans ->
      booleans.any { it as Boolean }
    }
  }
    .shareIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      replay = 1
    )

  public fun isRunning(): Boolean = _taskAssignmentsStateFlow.value.any { it.agent.isRunning() }

  private val _stateFlow: StateFlow<ArbigentScenarioExecutorState> = combine(
    isRunningFlow,
    isSuccessFlow,
    isFailedToArchiveFlow,
  ) { isRunning, success, isFailedToArchive ->
    when {
      isFailedToArchive -> ArbigentScenarioExecutorState.Failed
      isRunning -> ArbigentScenarioExecutorState.Running
      success -> ArbigentScenarioExecutorState.Success
      else -> ArbigentScenarioExecutorState.Idle
    }
  }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = ArbigentScenarioExecutorState.Idle
    )
  public val scenarioStateFlow: Flow<ArbigentScenarioExecutorState> = _stateFlow
  public fun scenarioState(): ArbigentScenarioExecutorState {
    val isRunning = isRunning()
    val isAchieved = isSuccessful()
    val isFailedToArchive = isFailedToArchive()
    return when {
      isFailedToArchive -> ArbigentScenarioExecutorState.Failed
      isRunning -> ArbigentScenarioExecutorState.Running
      isAchieved -> ArbigentScenarioExecutorState.Success
      else -> ArbigentScenarioExecutorState.Idle
    }
  }

  public suspend fun waitUntilFinished() {
    arbigentDebugLog("Arbigent.waitUntilFinished start")
    isRunningFlow.debounce(100).first { !it }
    arbigentDebugLog("Arbigent.waitUntilFinished end")
  }

  public suspend fun execute(scenario: ArbigentScenario, mcpClient: MCPClient) {
    _isFailedToArchiveFlow.value = false
    arbigentDebugLog("Arbigent.execute start")
    _taskAssignmentsHistoryStateFlow.value = listOf()

    val replayTraceKeys = scenario.replayTraceKeys()
    val replayTraces = if (scenario.replayWithFallback) {
      replayTraceKeys.map(replayTraceStore::read)
        .takeIf { traces -> traces.isNotEmpty() && traces.all { it != null } }
        ?.map { trace -> requireNotNull(trace) }
    } else {
      null
    }
    var attemptMode = if (replayTraces != null) {
      ArbigentAttemptMode.ReplayWithFallback
    } else {
      ArbigentAttemptMode.Normal
    }
    if (attemptMode == ArbigentAttemptMode.ReplayWithFallback) {
      arbigentInfoLog("Starting replay for scenario ${scenario.id}")
    }

    // One recorder for the whole execution: the device is shared by every task, and the script it
    // writes has to describe the run end to end, not one task at a time.
    val replayScriptRecorder = scenario.replayScripts
      ?.takeIf { it.enabled }
      ?.let { ArbigentReplayScriptRecorder() }

    var finishedSuccessfully = false
    var retryRemain = scenario.maxRetry
    var normalRetriesExhausted = false
    // What a task replayed before it fell back, by task index. A replacement agent starts from the
    // device state those actions left behind and records only what it did from there, so a trace
    // written from its steps alone would not be replayable from the start of the task.
    val replayedPrefixes = mutableMapOf<Int, List<ArbigentContextHolder.Step>>()
    try {
      do {
        yield()
        replayedPrefixes.clear()
        // Every task starts again from the top on a retry, so anything recorded from the abandoned
        // attempt would replay actions the successful run never performed.
        replayScriptRecorder?.reset()
        _taskAssignmentsStateFlow.value.forEach {
          it.agent.cancel()
        }
        _taskAssignmentsStateFlow.value = scenario.agentTasks.mapIndexed { index, task ->
          ArbigentTaskAssignment(
            task,
            ArbigentAgent(
              agentConfig = task.agentConfig,
              dispatcher = dispatcher,
              replayTrace = replayTraces?.get(index)
                .takeIf { attemptMode == ArbigentAttemptMode.ReplayWithFallback },
              additionalInterceptors = listOfNotNull(replayScriptRecorder),
            ),
          )
        }
        _taskAssignmentsHistoryStateFlow.value += listOf(taskAssignments())
        // Tasks that already fell back to normal execution in this attempt. A task gets one
        // fallback: failing again is an ordinary failure and restarts the whole scenario.
        val fellBackTaskIndexes = mutableSetOf<Int>()
        // The task whose already-recorded replay-script steps must survive its re-run, set by the
        // fallback below for a task that carries on from the screen the previous one left.
        var keepRecordedStepsOf: Int? = null
        var index = 0
        while (index < taskAssignments().size) {
          val (task, agent) = taskAssignments()[index]
          _arbigentScenarioRunningInfoStateFlow.value = ArbigentScenarioRunningInfo(
            allTasks = taskAssignments().size,
            runningTasks = index + 1,
            retriedTasks = scenario.maxRetry - retryRemain,
            maxRetry = scenario.maxRetry,
            maxStep = 0,
            currentStep = 0,
          )
          replayScriptRecorder?.beginTask(
            taskIndex = index,
            goal = task.goal,
            discardPrevious = keepRecordedStepsOf != index,
          )
          keepRecordedStepsOf = null
          // The recorder must never decide a scenario's fate: a device that cannot take a listener
          // just loses its replay script for this task.
          val listening = replayScriptRecorder?.let { recorder ->
            runCatching { agent.device.addDeviceEventListener(recorder) }.isSuccess
          } ?: false
          try {
            supervisorScope {
              agent.latestArbigentContextFlow
                .flatMapLatest {
                  it?.stepsFlow ?: emptyFlow()
                }
                .onEach { steps ->
                  val context = agent.latestArbigentContext()
                  _arbigentScenarioRunningInfoStateFlow.value = _arbigentScenarioRunningInfoStateFlow.value?.copy(
                    maxStep = task.maxStep,
                    currentStep = context?.countMeaningfulActions() ?: 0
                  )
                }
                .launchIn(coroutineScope)
              agent.execute(
                agentTask = task,
                mcpClient = mcpClient,
              )
            }
          } finally {
            if (listening) {
              runCatching { agent.device.removeDeviceEventListener(replayScriptRecorder!!) }
            }
          }
          if (!agent.isGoalAchieved()) {
            // A replayed task that fails is not evidence that the tasks before it are wrong: it is
            // this task's recorded actions, or the assertion judging them, that did not hold. Only
            // this task is re-run under the AI, and the tasks after it replay again — a trace that
            // no longer starts from the screen the AI left behind diverges on its first step and
            // falls back the same way. Restarting the whole scenario instead would throw away
            // every task already replayed, which is the entire saving the mode exists for.
            if (attemptMode == ArbigentAttemptMode.ReplayWithFallback &&
              fellBackTaskIndexes.add(index)
            ) {
              arbigentInfoLog(
                "Replay fallback for scenario ${scenario.id}, task ${index + 1}: " +
                  "${replayFailureReason(agent)}. Re-running this task in normal mode and keeping the " +
                  "tasks before it.",
              )
              // A task that starts by resetting the device resets again when it is re-run here, so
              // the AI ran from the same place a replay of this task would start from and its steps
              // stand alone as a trace. Only a task that carries on from the previous one needs the
              // actions it had already replayed put back in front of them.
              if (!task.resetsDeviceState()) {
                replayedPrefixes[index] = agent.latestArbigentContext()?.steps().orEmpty()
              }
              // Same reasoning for the replay script: a task that resets the device runs again from
              // the screen it started from, so what it recorded before is superseded; a task that
              // carries on keeps it, or the script would jump over actions the device has had.
              if (!task.resetsDeviceState()) keepRecordedStepsOf = index
              agent.cancel()
              _taskAssignmentsStateFlow.value = taskAssignments().toMutableList().also {
                it[index] = ArbigentTaskAssignment(
                  task,
                  ArbigentAgent(
                    agentConfig = task.agentConfig,
                    dispatcher = dispatcher,
                    replayTrace = null,
                    additionalInterceptors = listOfNotNull(replayScriptRecorder),
                  ),
                )
              }
              _taskAssignmentsHistoryStateFlow.value += listOf(taskAssignments())
              yield()
              continue
            }
            arbigentDebugLog("Arbigent.execute break because agent is not achieved")
            break
          }
          if (index == taskAssignments().size - 1) {
            arbigentDebugLog("Arbigent.execute all agents are achieved")
            finishedSuccessfully = true
          }
          index++
          yield()
        }
        if (!finishedSuccessfully) {
          if (attemptMode == ArbigentAttemptMode.ReplayWithFallback) {
            // A task failed even after its own fallback to normal execution. The tasks before it
            // are now the suspect: a replayed one may have ended somewhere close enough that its
            // assertions passed but wrong enough that this task cannot proceed. Every retry from
            // here is fully AI-driven, so replaying a drifting prefix cannot burn them all.
            arbigentInfoLog(
              "Replay fallback for scenario ${scenario.id} did not recover. " +
                "Restarting all tasks and initializers in normal mode.",
            )
            attemptMode = ArbigentAttemptMode.Normal
          } else if (retryRemain > 0) {
            retryRemain--
          } else {
            normalRetriesExhausted = true
          }
        }
      } while (!finishedSuccessfully && !normalRetriesExhausted)
    } catch (e: CancellationException) {
      arbigentDebugLog("Arbigent.execute canceled")
    } catch (e: Exception) {
      errorHandler(e)
    } finally {
      // To see after tests
//      _arbigentScenarioRunningInfoStateFlow.value = null
      _taskAssignmentsStateFlow.value.forEach {
        it.agent.cancel()
      }
    }
    if (!isGoalAchieved()) {
      if (normalRetriesExhausted && scenario.replayWithFallback) {
        replayTraceKeys.forEach(replayTraceStore::delete)
      }
      _isFailedToArchiveFlow.value = true
      arbigentErrorLog("🔴 ${scenario.id} scenario failed")
      throw FailedToArchiveException(
        "Failed to archive scenario:" + statusText() + " retryRemain:$retryRemain"
      )
    } else {
      if (scenario.replayWithFallback) {
        taskAssignments().forEachIndexed { index, assignment ->
          val key = replayTraceKeys[index]
          val candidate = assignment.agent.latestArbigentContext()
            ?.let {
              ArbigentReplayTrace.candidateFrom(
                key = key,
                contextHolder = it,
                precedingSteps = replayedPrefixes[index].orEmpty(),
              )
            }
          val reason = if (candidate == null) {
            "the run produced no steps to record"
          } else {
            candidate.invalidReasonFor(key)
          }
          if (reason == null) {
            replayTraceStore.write(key, requireNotNull(candidate))
          } else {
            replayTraceStore.delete(key)
            arbigentInfoLog(
              "Not recording a replay trace for scenario ${scenario.id}, task ${index + 1}: $reason",
            )
          }
        }
      }
      if (replayScriptRecorder != null) {
        writeReplayScripts(scenario, replayScriptRecorder)
      }
      arbigentInfoLog("🟢 ${scenario.id} scenario completed successfully")
    }
    arbigentDebugLog("Arbigent.execute end")
  }

  /**
   * Writes the replay script for a scenario that has just succeeded.
   *
   * Wrapped so a filesystem problem cannot turn a green scenario red: the script is a by-product of
   * the run, and failing to write it says nothing about whether the goal was reached.
   */
  private fun writeReplayScripts(
    scenario: ArbigentScenario,
    recorder: ArbigentReplayScriptRecorder,
  ) {
    val settings = scenario.replayScripts ?: return
    runCatching {
      // The runner drives the device with adb, so a script recorded on anything else could not be
      // replayed by it. iOS and Web would need their own event mapping and runner.
      val device = taskAssignments().last().agent.device
      val os = device.os()
      if (os != ArbigentDeviceOs.Android) {
        arbigentInfoLog(
          "Not writing a replay script for scenario ${scenario.id}: replay scripts are Android-only and this device is $os",
        )
        return
      }
      val outputDir = settings.outputDir
        ?.let { File(it) }
        ?: File(ArbigentFiles.parentDir, DefaultReplayScriptsDirName)
      // The signature is what the last task left on screen. It is advisory: a replay that reaches a
      // screen with none of these ids has almost certainly gone somewhere else.
      val signature = runCatching {
        val elements = device.elements().elements
        elements
          .mapNotNull { element -> ArbigentElementIdentity.from(element, elements)?.resourceId }
          .distinct()
          .take(MaxSignatureElements)
      }.getOrElse { emptyList() }
      val (screenWidth, screenHeight) = recorder.screenSize()
      ArbigentReplayScriptWriter(outputDir).write(
        scenarioId = scenario.id,
        goals = scenario.agentTasks.map { it.goal },
        tasks = recorder.recordedTasks(),
        signature = signature,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
      )
    }.onFailure {
      arbigentInfoLog("Failed to write a replay script for scenario ${scenario.id}: ${it.message}")
    }
  }

  /**
   * Why the replayed [agent] gave up, taken from its own steps. Reading every task's steps instead
   * would report a divergence an earlier task recovered from, in preference to the one that
   * actually stopped this task.
   */
  private fun replayFailureReason(agent: ArbigentAgent): String {
    return listOfNotNull(agent.latestArbigentContext()).asSequence()
      .flatMap { contextHolder -> contextHolder.steps().asReversed().asSequence() }
      .mapNotNull { step -> step.feedback }
      .firstOrNull { feedback ->
        feedback.startsWith("Replay diverged") ||
          feedback.startsWith("Failed replay image assertion")
      }
      ?: "replay task execution failed"
  }

  public fun cancel() {
    executeJob?.cancel()
    _taskAssignmentsStateFlow.value.forEach {
      it.agent.cancel()
    }
  }

  public fun statusText(): String {
    return "Goal:${taskAssignments().lastOrNull()?.task?.goal}\n${
      taskAssignments().map { (task, agent) ->
        buildString {
          append(task.goal)
          appendLine(":")
          appendLine("  isAchieved:" + agent.isGoalAchieved())
          agent.latestArbigentContext()?.let {
            appendLine("  context:")
            it.steps().forEachIndexed { index, step ->
              appendLine("    step ${index + 1}.")
              appendLine(step.text().lines().joinToString("\n") { "      $it" })
              appendLine("      screenshots:${step.screenshotFilePath}")
            }
          }
        }
      }.joinToString("\n")
    }"
  }

  public class Builder(
    // Required — supplied by the ArbigentScenarioExecutor(dispatcher) factory.
    public val dispatcher: CoroutineDispatcher,
  ) {
    public fun build(): ArbigentScenarioExecutor {
      return ArbigentScenarioExecutor(dispatcher)
    }
  }
}

private fun ArbigentScenario.replayTraceKeys(): List<ArbigentReplayTraceKey> =
  agentTasks.mapIndexed { index, task ->
    ArbigentReplayTraceKey(
      version = BuildConfig.VERSION_NAME,
      scenarioId = id,
      taskIndex = index,
      taskIdentity = buildString {
        append(task.scenarioId)
        task.callBreadcrumb?.let { breadcrumb ->
          append(":")
          append(breadcrumb)
        }
      },
      goal = task.agentConfig.resolveGoal(task.goal),
    )
  }

public fun ArbigentScenarioExecutor(
  dispatcher: CoroutineDispatcher,
  block: ArbigentScenarioExecutor.Builder.() -> Unit = {},
): ArbigentScenarioExecutor {
  val builder = ArbigentScenarioExecutor.Builder(dispatcher)
  builder.block()
  return builder.build()
}

/** Where replay scripts go when the project does not say. */
private const val DefaultReplayScriptsDirName = "replay-scripts"

/** How many resource ids describe the end screen. Enough to recognise it, few enough to read. */
private const val MaxSignatureElements = 5
