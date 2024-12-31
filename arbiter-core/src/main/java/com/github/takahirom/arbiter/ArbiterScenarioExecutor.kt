package com.github.takahirom.arbiter

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

  data class ArbiterExecutorScenario(
    val arbiterAgentTasks: List<ArbiterAgentTask>,
    val maxRetry: Int = 0,
    val maxStepCount: Int = 10,
    val deviceFormFactor: ArbiterScenarioDeviceFormFactor = ArbiterScenarioDeviceFormFactor.Mobile,
  )

  private val _taskToAgentStateFlow = MutableStateFlow<List<Pair<ArbiterAgentTask, ArbiterAgent>>>(listOf())
  val agentTaskToAgentStateFlow: StateFlow<List<Pair<ArbiterAgentTask, ArbiterAgent>>> = _taskToAgentStateFlow.asStateFlow()
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
    arbiterExecutorScenario: ArbiterExecutorScenario,
  ) {
    executeJob?.cancel()
    executeJob = coroutineScope.launch {
      execute(arbiterExecutorScenario)
    }
  }

  suspend fun execute(arbiterExecutorScenario: ArbiterExecutorScenario) {
    println("Arbiter.execute start")

    var finishedSuccessfully = false
    var retryRemain = arbiterExecutorScenario.maxRetry
    try {
      do {
        yield()
        _taskToAgentStateFlow.value.forEach {
          it.second.cancel()
        }
        _taskToAgentStateFlow.value = arbiterExecutorScenario.arbiterAgentTasks.map { task ->
          task to ArbiterAgent(task.agentConfig)
        }
        for ((index, taskAgent) in agentTaskToAgentStateFlow.value.withIndex()) {
          val (task, agent) = taskAgent
          _runningInfoStateFlow.value = RunningInfo(
            allTasks = agentTaskToAgentStateFlow.value.size,
            runningTasks = index + 1,
            retriedTasks = arbiterExecutorScenario.maxRetry - retryRemain,
            maxRetry = arbiterExecutorScenario.maxRetry,
          )
          agent.execute(
            task.goal,
            maxStep = arbiterExecutorScenario.maxStepCount,
            agentCommandTypes = when(arbiterExecutorScenario.deviceFormFactor) {
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
