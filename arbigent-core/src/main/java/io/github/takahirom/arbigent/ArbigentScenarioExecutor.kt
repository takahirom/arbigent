package io.github.takahirom.arbigent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
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
        step: $currentStep/$maxStep
        retry: $retriedTasks/$maxRetry
    """.trimIndent()
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

public class ArbigentScenarioExecutor {
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
    CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob())
  private val _arbigentScenarioRunningInfoStateFlow: MutableStateFlow<ArbigentScenarioRunningInfo?> =
    MutableStateFlow(null)
  public val runningInfoFlow: Flow<ArbigentScenarioRunningInfo?> =
    _arbigentScenarioRunningInfoStateFlow.asSharedFlow()

  public fun runningInfo(): ArbigentScenarioRunningInfo? =
    _arbigentScenarioRunningInfoStateFlow.value

  public val isSuccessFlow: Flow<Boolean> = taskAssignmentsFlow.flatMapLatest { taskToAgents ->
    val flows: List<Flow<Boolean>> = taskToAgents.map { taskToAgent ->
      taskToAgent.agent.isGoalAchievedFlow
    }
    combine(flows) { booleans ->
      booleans.all { it }
    }
  }
    .shareIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      replay = 1
    )

  public fun isSuccessful(): Boolean {
    if (taskAssignments().isEmpty()) {
      return false
    }
    return taskAssignments().all { it.agent.isGoalAchieved() }
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

  public suspend fun execute(scenario: ArbigentScenario) {
    _isFailedToArchiveFlow.value = false
    arbigentDebugLog("Arbigent.execute start")
    _taskAssignmentsHistoryStateFlow.value = listOf()

    var finishedSuccessfully = false
    var retryRemain = scenario.maxRetry
    try {
      do {
        yield()
        _taskAssignmentsStateFlow.value.forEach {
          it.agent.cancel()
        }
        _taskAssignmentsStateFlow.value = scenario.agentTasks.map { task ->
          ArbigentTaskAssignment(task, ArbigentAgent(task.agentConfig))
        }
        _taskAssignmentsHistoryStateFlow.value += listOf(taskAssignments())
        for ((index, taskAgent) in taskAssignments().withIndex()) {
          val (task, agent) = taskAgent
          _arbigentScenarioRunningInfoStateFlow.value = ArbigentScenarioRunningInfo(
            allTasks = taskAssignments().size,
            runningTasks = index + 1,
            retriedTasks = scenario.maxRetry - retryRemain,
            maxRetry = scenario.maxRetry,
            maxStep = 0,
            currentStep = 0,
          )
          supervisorScope {
            agent.latestArbigentContextFlow
              .flatMapLatest {
                it?.stepsFlow ?: emptyFlow()
              }
              .onEach { steps ->
                _arbigentScenarioRunningInfoStateFlow.value = _arbigentScenarioRunningInfoStateFlow.value?.copy(
                  maxStep = task.maxStep,
                  currentStep = steps.size
                )
              }
              .launchIn(coroutineScope)
            agent.execute(
              agentTask = task,
            )
          }
          if (!agent.isGoalAchieved()) {
            arbigentDebugLog("Arbigent.execute break because agent is not achieved")
            break
          }
          if (index == taskAssignments().size - 1) {
            arbigentDebugLog("Arbigent.execute all agents are achieved")
            finishedSuccessfully = true
          }
          yield()
        }
      } while (!finishedSuccessfully && retryRemain-- > 0)
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
      _isFailedToArchiveFlow.value = true
      throw FailedToArchiveException(
        "Failed to archive scenario:" + statusText() + " retryRemain:$retryRemain"
      )
    }
    arbigentDebugLog("Arbigent.execute end")
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

  public class Builder {
    public fun build(): ArbigentScenarioExecutor {
      return ArbigentScenarioExecutor()
    }
  }
}

public fun ArbigentScenarioExecutor(block: ArbigentScenarioExecutor.Builder.() -> Unit = {}): ArbigentScenarioExecutor {
  val builder = ArbigentScenarioExecutor.Builder()
  builder.block()
  return builder.build()
}