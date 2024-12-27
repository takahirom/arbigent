package com.github.takahirom.arbiter

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

object ArbiterCorotuinesDispatcher {
  var dispatcher: CoroutineDispatcher = Dispatchers.Default
}

class Arbiter {
  data class Task(
    val goal: String,
    val agentConfig: AgentConfig,
  )

  data class Scenario(
    val tasks: List<Task>,
  )

  private val _taskToAgentStateFlow = MutableStateFlow<List<Pair<Task, Agent>>>(listOf())
  val taskToAgentStateFlow: StateFlow<List<Pair<Task, Agent>>> = _taskToAgentStateFlow.asStateFlow()
  private var executeJob: Job? = null
  private val coroutineScope =
    CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob())
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
    _taskToAgentStateFlow.value.forEach {
      it.second.cancel()
    }
    _taskToAgentStateFlow.value = scenario.tasks.map { task ->
      task to Agent(task.agentConfig)
    }
    for ((task, agent) in taskToAgentStateFlow.value) {
      agent.execute(task.goal)
      if (!agent.isArchivedStateFlow.value) {
        println("Arbiter.execute break because agent is not archived")
        break
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

fun arbiter(block: Arbiter.Builder.() -> Unit): Arbiter {
  val builder = Arbiter.Builder()
  builder.block()
  return builder.build()
}
