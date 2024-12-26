package com.github.takahirom.arbiter.ui

import com.github.takahirom.arbiter.Arbiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

class ScenarioStateHolder(initialArbiter: Arbiter) {
    // (var goal: String?, var arbiter: Arbiter?)
    val goalStateFlow: MutableStateFlow<String> = MutableStateFlow("")
    val goal get() = goalStateFlow.value
    val arbiterStateFlow: MutableStateFlow<Arbiter> = MutableStateFlow(initialArbiter)
    val isArchived = arbiterStateFlow
      .flatMapLatest { it.isArchivedStateFlow }
      .stateIn(
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        started = SharingStarted.WhileSubscribed(),
        initialValue = false
      )
    val isRunning = arbiterStateFlow
      .flatMapLatest { it.isRunningStateFlow }
      .stateIn(
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        started = SharingStarted.WhileSubscribed(),
        initialValue = false
      )
    val contextStateFlow = arbiterStateFlow.flatMapLatest { it.arbiterContextHolderStateFlow }
      .stateIn(
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        started = SharingStarted.WhileSubscribed(),
        initialValue = null
      )

    fun execute() {
      arbiterStateFlow.value!!.execute(goal)
    }

    suspend fun waitUntilFinished() {
      arbiterStateFlow.value!!.waitUntilFinished()
    }

    fun isGoalAchieved(): Boolean {
      return arbiterStateFlow.value?.isArchivedStateFlow?.value ?: false
    }

    fun cancel() {
      arbiterStateFlow.value?.cancel()
    }

    fun onGoalChanged(goal: String) {
      goalStateFlow.value = goal
    }
  }