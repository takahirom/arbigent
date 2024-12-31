package com.github.takahirom.arbiter

import com.google.gson.annotations.SerializedName
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
import kotlinx.serialization.Serializable

@Serializable
sealed interface ArbiterScenarioDeviceFormFactor {
  fun isMobile(): Boolean = this is Mobile
 fun isTv(): Boolean = this is Tv

  @Serializable
  object Mobile : ArbiterScenarioDeviceFormFactor
  @Serializable
  object Tv : ArbiterScenarioDeviceFormFactor
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

  data class ArbiterAgentTask(
    val goal: String,
    val agentConfig: AgentConfig,
  )

  data class Scenario(
    val arbiterAgentTasks: List<ArbiterAgentTask>,
    val maxRetry: Int = 0,
    val maxStepCount: Int = 10,
    val deviceFormFactor: ArbiterScenarioDeviceFormFactor = ArbiterScenarioDeviceFormFactor.Mobile,
  )

  private val _taskToAgentStateFlow = MutableStateFlow<List<Pair<ArbiterAgentTask, Agent>>>(listOf())
  val agentTaskToAgentStateFlow: StateFlow<List<Pair<ArbiterAgentTask, Agent>>> = _taskToAgentStateFlow.asStateFlow()
  private var executeJob: Job? = null
  private val coroutineScope =
    CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob())
  private val _runningInfoStateFlow: MutableStateFlow<RunningInfo?> = MutableStateFlow(null)
  val runningInfoStateFlow: StateFlow<RunningInfo?> = _runningInfoStateFlow.asStateFlow()
  val isArchivedStateFlow = agentTaskToAgentStateFlow.flatMapLatest { taskToAgents ->
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
  val isRunningStateFlow = agentTaskToAgentStateFlow.flatMapLatest { taskToAgents ->
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
    println("Arbiter.waitUntilFinished start")
    isRunningStateFlow.debounce(100).first { !it }
    println("Arbiter.waitUntilFinished end")
  }

  fun executeAsync(
    scenario: Scenario,
  ) {
    executeJob?.cancel()
    executeJob = coroutineScope.launch {
      execute(scenario)
    }
  }

  suspend fun execute(scenario: Scenario) {
    println("Arbiter.execute start")

    var finishedSuccessfully = false
    var retryRemain = scenario.maxRetry
    try {
      do {
        yield()
        _taskToAgentStateFlow.value.forEach {
          it.second.cancel()
        }
        _taskToAgentStateFlow.value = scenario.arbiterAgentTasks.map { task ->
          task to Agent(task.agentConfig)
        }
        for ((index, taskAgent) in agentTaskToAgentStateFlow.value.withIndex()) {
          val (task, agent) = taskAgent
          _runningInfoStateFlow.value = RunningInfo(
            allTasks = agentTaskToAgentStateFlow.value.size,
            runningTasks = index + 1,
            retriedTasks = scenario.maxRetry - retryRemain,
            maxRetry = scenario.maxRetry,
          )
          agent.execute(
            task.goal,
            maxStep = scenario.maxStepCount,
            agentCommandTypes = when(scenario.deviceFormFactor) {
              is ArbiterScenarioDeviceFormFactor.Mobile -> defaultAgentCommandTypes()
              is ArbiterScenarioDeviceFormFactor.Tv -> defaultAgentCommandTypesForTv()
            }
          )
          if (!agent.isArchivedStateFlow.value) {
            println("Arbiter.execute break because agent is not archived")
            break
          }
          if (index == agentTaskToAgentStateFlow.value.size - 1) {
            println("Arbiter.execute all agents are archived")
            finishedSuccessfully = true
          }
          yield()
        }
      } while (!finishedSuccessfully && retryRemain-- > 0)
    } finally {
      _runningInfoStateFlow.value = null
      _taskToAgentStateFlow.value.forEach {
        it.second.cancel()
      }
    }
    println("Arbiter.execute end")
  }

  fun cancel() {
    executeJob?.cancel()
    _taskToAgentStateFlow.value.forEach {
      it.second.cancel()
    }
  }

  class Builder {
    fun build(): ArbiterScenarioExecutor {
      return ArbiterScenarioExecutor()
    }
  }
}

fun ArbiterScenarioExecutor(block: ArbiterScenarioExecutor.Builder.() -> Unit): ArbiterScenarioExecutor {
  val builder = ArbiterScenarioExecutor.Builder()
  builder.block()
  return builder.build()
}
