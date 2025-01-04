package com.github.takahirom.arbiter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public sealed interface ArbiterScenarioDeviceFormFactor {
  @Serializable
  @SerialName("Mobile")
  public data object Mobile : ArbiterScenarioDeviceFormFactor

  @Serializable
  @SerialName("Tv")
  public data object Tv : ArbiterScenarioDeviceFormFactor

  public fun isMobile(): Boolean = this == Mobile
  public fun isTv(): Boolean = this is Tv
}

public data class ArbiterScenarioRunningInfo(
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

public class ArbiterScenarioExecutor {
  private val _taskAssignmentsStateFlow =
    MutableStateFlow<List<ArbiterTaskAssignment>>(listOf())
  public val taskAssignmentsFlow: Flow<List<ArbiterTaskAssignment>> =
    _taskAssignmentsStateFlow.asSharedFlow()
  public fun taskAssignments(): List<ArbiterTaskAssignment> = _taskAssignmentsStateFlow.value
  private var executeJob: Job? = null
  private val coroutineScope =
    CoroutineScope(ArbiterCoroutinesDispatcher.dispatcher + SupervisorJob())
  private val _arbiterScenarioRunningInfoStateFlow: MutableStateFlow<ArbiterScenarioRunningInfo?> =
    MutableStateFlow(null)
  public val arbiterScenarioRunningInfoFlow: Flow<ArbiterScenarioRunningInfo?> =
    _arbiterScenarioRunningInfoStateFlow.asSharedFlow()
  public val isArchivedFlow: Flow<Boolean> = taskAssignmentsFlow.flatMapLatest { taskToAgents ->
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
  public fun isArchived(): Boolean = taskAssignments().all { it.agent.isGoalArchived() }

  // isArchivedStateFlow is WhileSubscribed so we can't use it in waitUntilFinished
  public fun isGoalArchived(): Boolean =
    taskAssignments().all { it.agent.isGoalArchived() }

  public val isRunningStateFlow: Flow<Boolean> = taskAssignmentsFlow.flatMapLatest { taskToAgents ->
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
  public val isRunning: Boolean = _taskAssignmentsStateFlow.value.any { it.agent.isRunning() }

  public suspend fun waitUntilFinished() {
    arbiterDebugLog("Arbiter.waitUntilFinished start")
    isRunningStateFlow.debounce(100).first { !it }
    arbiterDebugLog("Arbiter.waitUntilFinished end")
  }

  public suspend fun execute(arbiterScenario: ArbiterScenario) {
    arbiterDebugLog("Arbiter.execute start")

    var finishedSuccessfully = false
    var retryRemain = arbiterScenario.maxRetry
    try {
      do {
        yield()
        _taskAssignmentsStateFlow.value.forEach {
          it.agent.cancel()
        }
        _taskAssignmentsStateFlow.value = arbiterScenario.agentTasks.map { task ->
          ArbiterTaskAssignment(task, ArbiterAgent(task.agentConfig))
        }
        for ((index, taskAgent) in taskAssignments().withIndex()) {
          val (task, agent) = taskAgent
          _arbiterScenarioRunningInfoStateFlow.value = ArbiterScenarioRunningInfo(
            allTasks = taskAssignments().size,
            runningTasks = index + 1,
            retriedTasks = arbiterScenario.maxRetry - retryRemain,
            maxRetry = arbiterScenario.maxRetry,
          )
          agent.execute(
            agentTask = task,
          )
          if (!agent.isGoalArchived()) {
            arbiterDebugLog("Arbiter.execute break because agent is not archived")
            break
          }
          if (index == taskAssignments().size - 1) {
            arbiterDebugLog("Arbiter.execute all agents are archived")
            finishedSuccessfully = true
          }
          yield()
        }
      } while (!finishedSuccessfully && retryRemain-- > 0)
    } catch (e: Exception) {
      errorHandler(e)
    } finally {
      _arbiterScenarioRunningInfoStateFlow.value = null
      _taskAssignmentsStateFlow.value.forEach {
        it.agent.cancel()
      }
    }
    arbiterDebugLog("Arbiter.execute end")
  }

  public fun cancel() {
    executeJob?.cancel()
    _taskAssignmentsStateFlow.value.forEach {
      it.agent.cancel()
    }
  }

  public fun statusText(): String {
    return "Goal:${taskAssignments().last().task.goal}\n${
      taskAssignments().map { (task, agent) ->
        buildString {
          append(task.goal)
          appendLine(":")
          appendLine("  isArchived:" + agent.isGoalArchived())
          agent.latestArbiterContext()?.let {
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
    public fun build(): ArbiterScenarioExecutor {
      return ArbiterScenarioExecutor()
    }
  }
}

public fun ArbiterScenarioExecutor(block: ArbiterScenarioExecutor.Builder.() -> Unit = {}): ArbiterScenarioExecutor {
  val builder = ArbiterScenarioExecutor.Builder()
  builder.block()
  return builder.build()
}