package io.github.takahirom.arbigent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.yield
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

@Serializable
public sealed interface ArbigentScenarioDeviceFormFactor {
  @Serializable
  @SerialName("Mobile")
  public data object Mobile : ArbigentScenarioDeviceFormFactor

  @Serializable
  @SerialName("Tv")
  public data object Tv : ArbigentScenarioDeviceFormFactor

  public fun isMobile(): Boolean = this == Mobile
  public fun isTv(): Boolean = this is Tv
}

public data class ArbigentScenarioRunningInfo(
  val allTasks: Int,
  val runningTasks: Int,
  val retriedTasks: Int,
  val maxRetry: Int,
) {
  override fun toString(): String {
    return """
        task: $runningTasks/$allTasks
        retry: $retriedTasks/$maxRetry
    """.trimIndent()
  }
}

@Serializable
public class ArbigentImageAssertion(
  public val assertionPrompt: String,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
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
  public fun runningInfo(): ArbigentScenarioRunningInfo? = _arbigentScenarioRunningInfoStateFlow.value
  public val isSuccessFlow: Flow<Boolean> = taskAssignmentsFlow.flatMapLatest { taskToAgents ->
    val flows: List<Flow<Boolean>> = taskToAgents.map { taskToAgent ->
      taskToAgent.agent.isGoalArchivedFlow
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
  public fun isSuccess(): Boolean {
    if (taskAssignments().isEmpty()) {
      return false
    }
    return taskAssignments().all { it.agent.isGoalArchived() }
  }

  private val _isFailedToArchiveFlow = MutableStateFlow(false)
  public val isFailedToArchiveFlow: Flow<Boolean> = _isFailedToArchiveFlow.asSharedFlow()
  public fun isFailedToArchive(): Boolean = _isFailedToArchiveFlow.value

  // isArchivedStateFlow is WhileSubscribed so we can't use it in waitUntilFinished
  public fun isGoalArchived(): Boolean {
    if (taskAssignments().isEmpty()) {
      return false
    }
    return taskAssignments().all { it.agent.isGoalArchived() }
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
    val isArchived = isSuccess()
    val isFailedToArchive = isFailedToArchive()
    return when {
      isFailedToArchive -> ArbigentScenarioExecutorState.Failed
      isRunning -> ArbigentScenarioExecutorState.Running
      isArchived -> ArbigentScenarioExecutorState.Success
      else -> ArbigentScenarioExecutorState.Idle
    }
  }

  public suspend fun waitUntilFinished() {
    arbigentDebugLog("Arbigent.waitUntilFinished start")
    isRunningFlow.debounce(100).first { !it }
    arbigentDebugLog("Arbigent.waitUntilFinished end")
  }

  public suspend fun execute(arbigentScenario: ArbigentScenario) {
    _isFailedToArchiveFlow.value = false
    arbigentDebugLog("Arbigent.execute start")

    var finishedSuccessfully = false
    var retryRemain = arbigentScenario.maxRetry
    try {
      do {
        yield()
        _taskAssignmentsStateFlow.value.forEach {
          it.agent.cancel()
        }
        _taskAssignmentsStateFlow.value = arbigentScenario.agentTasks.map { task ->
          ArbigentTaskAssignment(task, ArbigentAgent(task.agentConfig))
        }
        for ((index, taskAgent) in taskAssignments().withIndex()) {
          val (task, agent) = taskAgent
          _arbigentScenarioRunningInfoStateFlow.value = ArbigentScenarioRunningInfo(
            allTasks = taskAssignments().size,
            runningTasks = index + 1,
            retriedTasks = arbigentScenario.maxRetry - retryRemain,
            maxRetry = arbigentScenario.maxRetry,
          )
          agent.execute(
            agentTask = task,
          )
          if (!agent.isGoalArchived()) {
            arbigentDebugLog("Arbigent.execute break because agent is not archived")
            break
          }
          if (index == taskAssignments().size - 1) {
            arbigentDebugLog("Arbigent.execute all agents are archived")
            finishedSuccessfully = true
          }
          yield()
        }
      } while (!finishedSuccessfully && retryRemain-- > 0)
    } catch (e: CancellationException) {
    } catch (e: Exception) {
      errorHandler(e)
    } finally {
      _arbigentScenarioRunningInfoStateFlow.value = null
      _taskAssignmentsStateFlow.value.forEach {
        it.agent.cancel()
      }
    }
    if (!isGoalArchived()) {
      _isFailedToArchiveFlow.value = true
      throw FailedToArchiveException(
        "Failed to archive scenario:" + statusText()
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
          appendLine("  isArchived:" + agent.isGoalArchived())
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