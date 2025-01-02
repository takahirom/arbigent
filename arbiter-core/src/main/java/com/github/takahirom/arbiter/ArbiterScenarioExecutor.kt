package com.github.takahirom.arbiter

import com.github.takahirom.arbiter.ArbiterScenarioDeviceFormFactor.Tv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ArbiterScenarioDeviceFormFactor {
  @Serializable
  @SerialName("Mobile")
  object Mobile : ArbiterScenarioDeviceFormFactor

  @Serializable
  @SerialName("Tv")
  object Tv : ArbiterScenarioDeviceFormFactor

  fun isMobile(): Boolean = this == Mobile
  fun isTv(): Boolean = this is Tv
}

class ArbiterScenarioExecutor {
  data class RunningInfo(
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


  private val _taskToAgentsStateFlow =
    MutableStateFlow<List<Pair<ArbiterAgentTask, ArbiterAgent>>>(listOf())
  val agentTaskToAgentsStateFlow: StateFlow<List<Pair<ArbiterAgentTask, ArbiterAgent>>> =
    _taskToAgentsStateFlow.asStateFlow()
  private var executeJob: Job? = null
  private val coroutineScope =
    CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob())
  private val _runningInfoStateFlow: MutableStateFlow<RunningInfo?> = MutableStateFlow(null)
  val runningInfoStateFlow: StateFlow<RunningInfo?> = _runningInfoStateFlow.asStateFlow()
  val isArchivedStateFlow = agentTaskToAgentsStateFlow.flatMapLatest { taskToAgents ->
    val flows: List<Flow<Boolean>> = taskToAgents.map { taskToAgent ->
      taskToAgent.second.isArchivedStateFlow
    }
    combine(flows) { booleans ->
      booleans.all { it }
    }
  }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = false
    )

  // isArchivedStateFlow is WhileSubscribed so we can't use it in waitUntilFinished
  fun isGoalArchived() =
    agentTaskToAgentsStateFlow.value.all { it.second.isArchivedStateFlow.value }

  val isRunningStateFlow = agentTaskToAgentsStateFlow.flatMapLatest { taskToAgents ->
    val flows: List<Flow<Boolean>> = taskToAgents.map { taskToAgent ->
      taskToAgent.second.isRunningStateFlow
    }
    combine(flows) { booleans ->
      booleans.any { it as Boolean }
    }
  }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = false
    )

  suspend fun waitUntilFinished() {
    arbiterDebugLog("Arbiter.waitUntilFinished start")
    isRunningStateFlow.debounce(100).first { !it }
    arbiterDebugLog("Arbiter.waitUntilFinished end")
  }

  fun executeAsync(
    arbiterScenario: ArbiterScenario,
  ) {
    executeJob?.cancel()
    executeJob = coroutineScope.launch {
      execute(arbiterScenario)
    }
  }

  suspend fun execute(arbiterScenario: ArbiterScenario) {
    arbiterDebugLog("Arbiter.execute start")

    var finishedSuccessfully = false
    var retryRemain = arbiterScenario.maxRetry
    try {
      do {
        yield()
        _taskToAgentsStateFlow.value.forEach {
          it.second.cancel()
        }
        _taskToAgentsStateFlow.value = arbiterScenario.agentTasks.map { task ->
          task to ArbiterAgent(task.agentConfig)
        }
        for ((index, taskAgent) in agentTaskToAgentsStateFlow.value.withIndex()) {
          val (task, agent) = taskAgent
          _runningInfoStateFlow.value = RunningInfo(
            allTasks = agentTaskToAgentsStateFlow.value.size,
            runningTasks = index + 1,
            retriedTasks = arbiterScenario.maxRetry - retryRemain,
            maxRetry = arbiterScenario.maxRetry,
          )
          agent.execute(
            agentTask = task,
          )
          if (!agent.isArchivedStateFlow.value) {
            arbiterDebugLog("Arbiter.execute break because agent is not archived")
            break
          }
          if (index == agentTaskToAgentsStateFlow.value.size - 1) {
            arbiterDebugLog("Arbiter.execute all agents are archived")
            finishedSuccessfully = true
          }
          yield()
        }
      } while (!finishedSuccessfully && retryRemain-- > 0)
    } finally {
      _runningInfoStateFlow.value = null
      _taskToAgentsStateFlow.value.forEach {
        it.second.cancel()
      }
    }
    arbiterDebugLog("Arbiter.execute end")
  }

  fun cancel() {
    executeJob?.cancel()
    _taskToAgentsStateFlow.value.forEach {
      it.second.cancel()
    }
  }

  fun statusText(): String {
    return "Goal:${agentTaskToAgentsStateFlow.value.last().first.goal}\n${
      agentTaskToAgentsStateFlow.value.map { (task, agent) ->
        buildString {
          append(task.goal)
          appendLine(":")
          appendLine("  isArchived:" + agent.isArchivedStateFlow.value)
          agent.latestArbiterContext()?.let {
            appendLine("  context:")
            it.steps.value.forEachIndexed { index, step ->
              appendLine("    step ${index + 1}.")
              appendLine(step.text().lines().joinToString("\n") { "      $it" })
              appendLine("      screenshots:${step.screenshotFilePath}")
            }
          }
        }
      }.joinToString("\n")
    }"
  }

  class Builder {
    fun build(): ArbiterScenarioExecutor {
      return ArbiterScenarioExecutor()
    }
  }
}

fun ArbiterScenarioExecutor(block: ArbiterScenarioExecutor.Builder.() -> Unit = {}): ArbiterScenarioExecutor {
  val builder = ArbiterScenarioExecutor.Builder()
  builder.block()
  return builder.build()
}