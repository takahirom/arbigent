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

data class RunningInfo(
  val allTasks: Int,
  val runningTasks: Int,
  val retriedTasks: Int,
  val maxRetry: Int,
) {
  override fun toString(): String {
    return """
        task:$runningTasks/$allTasks
        retry:$retriedTasks/$maxRetry
    """.trimIndent()
  }
}


@Serializable
sealed interface InputCommandType {
  fun isMobile(): Boolean = this is Mobile
 fun isTv(): Boolean = this is Tv

  object Mobile : InputCommandType
  object Tv : InputCommandType
}

class Arbiter {
  data class Task(
    val goal: String,
    val agentConfig: AgentConfig,
  )

  data class Scenario(
    val tasks: List<Task>,
    val maxRetry: Int = 0,
    val maxTurnCount: Int = 10,
    val inputCommandType: InputCommandType = InputCommandType.Mobile,
  )

  private val _taskToAgentStateFlow = MutableStateFlow<List<Pair<Task, Agent>>>(listOf())
  val taskToAgentStateFlow: StateFlow<List<Pair<Task, Agent>>> = _taskToAgentStateFlow.asStateFlow()
  private var executeJob: Job? = null
  private val coroutineScope =
    CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob())
  private val _runningInfoStateFlow: MutableStateFlow<RunningInfo?> = MutableStateFlow(null)
  val runningInfoStateFlow: StateFlow<RunningInfo?> = _runningInfoStateFlow.asStateFlow()
  val isArchivedStateFlow = taskToAgentStateFlow.flatMapLatest { taskToAgents ->
    val flows: List<Flow<Boolean>> = taskToAgents.map { taskToAgent ->
      taskToAgent.second.isArchivedStateFlow
    }
    combine(flows) { booleans ->
      booleans.all { it as Boolean }
    }
  }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = false
    )
  val isRunningStateFlow = taskToAgentStateFlow.flatMapLatest { taskToAgents ->
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
        _taskToAgentStateFlow.value = scenario.tasks.map { task ->
          task to Agent(task.agentConfig)
        }
        for ((index, taskAgent) in taskToAgentStateFlow.value.withIndex()) {
          val (task, agent) = taskAgent
          _runningInfoStateFlow.value = RunningInfo(
            allTasks = taskToAgentStateFlow.value.size,
            runningTasks = index + 1,
            retriedTasks = scenario.maxRetry - retryRemain,
            maxRetry = scenario.maxRetry,
          )
          agent.execute(
            task.goal,
            maxTurn = scenario.maxTurnCount,
            agentCommandTypes = when(scenario.inputCommandType) {
              is InputCommandType.Mobile -> defaultAgentCommandTypes()
              is InputCommandType.Tv -> defaultAgentCommandTypesForTv()
            }
          )
          if (!agent.isArchivedStateFlow.value) {
            println("Arbiter.execute break because agent is not archived")
            break
          }
          if (index == taskToAgentStateFlow.value.size - 1) {
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
    fun build(): Arbiter {
      return Arbiter()
    }
  }
}

fun Arbiter(block: Arbiter.Builder.() -> Unit): Arbiter {
  val builder = Arbiter.Builder()
  builder.block()
  return builder.build()
}
