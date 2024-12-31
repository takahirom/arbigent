package com.github.takahirom.arbiter.ui

import androidx.compose.foundation.text.input.TextFieldState
import com.github.takahirom.arbiter.ArbiterAi
import com.github.takahirom.arbiter.ArbiterScenarioExecutor
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.ArbiterDevice
import com.github.takahirom.arbiter.ArbiterScenarioDeviceFormFactor
import com.github.takahirom.arbiter.ArbiterProjectSerializer
import com.github.takahirom.arbiter.AgentConfigBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn


class ArbiterScenarioStateHolder(initialDevice: ArbiterDevice, private val ai: ArbiterAi) {
  private val deviceStateFlow = MutableStateFlow(initialDevice)
  private val device get() = deviceStateFlow.value
  val goalState = TextFieldState("")
  val goal get() = goalState.text.toString()
  val maxRetryState: TextFieldState = TextFieldState("3")
  val maxTurnState: TextFieldState = TextFieldState("10")
  val cleanupDataStateFlow: MutableStateFlow<ArbiterProjectSerializer.CleanupData> =
    MutableStateFlow(
      ArbiterProjectSerializer.CleanupData.Noop
    )
  val initializeMethodsStateFlow: MutableStateFlow<ArbiterProjectSerializer.InitializeMethods> =
    MutableStateFlow(ArbiterProjectSerializer.InitializeMethods.Back)
  val deviceFormFactorStateFlow: MutableStateFlow<ArbiterScenarioDeviceFormFactor> =
    MutableStateFlow(ArbiterScenarioDeviceFormFactor.Mobile)
  val dependencyScenarioStateHolderStateFlow = MutableStateFlow<ArbiterScenarioStateHolder?>(null)
  val arbiterScenarioExecutorStateFlow = MutableStateFlow<ArbiterScenarioExecutor?>(null)
  private val coroutineScope = CoroutineScope(
    ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob()
  )
  val isArchived = arbiterScenarioExecutorStateFlow
    .flatMapLatest { it?.isArchivedStateFlow ?: flowOf() }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = false
    )
  val isRunning = arbiterScenarioExecutorStateFlow
    .flatMapLatest { it?.isRunningStateFlow ?: flowOf() }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = false
    )

  val runningInfo: StateFlow<ArbiterScenarioExecutor.RunningInfo?> =
    arbiterScenarioExecutorStateFlow
      .flatMapLatest { it?.runningInfoStateFlow ?: flowOf() }
      .stateIn(
        scope = coroutineScope,
        started = SharingStarted.WhileSubscribed(),
        initialValue = null
      )

  suspend fun onExecute(arbiterExecutorScenario: ArbiterScenarioExecutor.ArbiterExecutorScenario) {
    arbiterScenarioExecutorStateFlow.value?.cancel()
    val arbiterScenarioExecutor = ArbiterScenarioExecutor {
    }
    arbiterScenarioExecutorStateFlow.value = arbiterScenarioExecutor
    arbiterScenarioExecutorStateFlow.value!!.execute(
      arbiterExecutorScenario,
    )
  }

  suspend fun waitUntilFinished() {
    arbiterScenarioExecutorStateFlow.value!!.waitUntilFinished()
  }

  fun isGoalAchieved(): Boolean {
    return arbiterScenarioExecutorStateFlow.value?.isArchivedStateFlow?.value ?: false
  }

  fun cancel() {
    arbiterScenarioExecutorStateFlow.value?.cancel()
  }

  fun onGoalChanged(goal: String) {
    goalState.edit {
      replace(0, goalState.text.length, goal)
    }
  }

  fun onDeviceChanged(device: ArbiterDevice) {
    deviceStateFlow.value = device
  }

  fun createArbiterScenario(): ArbiterProjectSerializer.ArbiterScenario {
    return ArbiterProjectSerializer.ArbiterScenario(
      goal = goal,
      dependency = dependencyScenarioStateHolderStateFlow.value?.goal?.let { "goal:$it" },
      initializeMethods = initializeMethodsStateFlow.value,
      maxRetry = maxRetryState.text.toString().toIntOrNull() ?: 3,
      maxStep = maxTurnState.text.toString().toIntOrNull() ?: 10,
      deviceFormFactor = deviceFormFactorStateFlow.value,
      cleanupData = cleanupDataStateFlow.value
    )
  }
}
