package com.github.takahirom.arbiter.ui

import androidx.compose.foundation.text.input.TextFieldState
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.ArbiterScenarioContent
import com.github.takahirom.arbiter.ArbiterScenarioDeviceFormFactor
import com.github.takahirom.arbiter.ArbiterScenarioExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


class ArbiterScenarioStateHolder {
  @OptIn(ExperimentalUuidApi::class)
  val idStateFlow = MutableStateFlow(Uuid.random().toString())
  val id get() = idStateFlow.value
  val goalState = TextFieldState("")
  val goal get() = goalState.text.toString()
  val maxRetryState: TextFieldState = TextFieldState("3")
  val maxStepState: TextFieldState = TextFieldState("10")
  val cleanupDataStateFlow: MutableStateFlow<ArbiterScenarioContent.CleanupData> =
    MutableStateFlow(
      ArbiterScenarioContent.CleanupData.Noop
    )
  val initializeMethodsStateFlow: MutableStateFlow<ArbiterScenarioContent.InitializeMethods> =
    MutableStateFlow(ArbiterScenarioContent.InitializeMethods.Back)
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
    .flatMapLatest { it?.isRunningStateFlow ?: flowOf(false) }
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

  fun onExecute(scenarioExecutor: ArbiterScenarioExecutor) {
    arbiterScenarioExecutorStateFlow.value = scenarioExecutor
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

  fun createArbiterScenarioContent(): ArbiterScenarioContent {
    return ArbiterScenarioContent(
      id = idStateFlow.value,
      goal = goal,
      dependencyId = dependencyScenarioStateHolderStateFlow.value?.id,
      initializeMethods = initializeMethodsStateFlow.value,
      maxRetry = maxRetryState.text.toString().toIntOrNull() ?: 3,
      maxStep = maxStepState.text.toString().toIntOrNull() ?: 10,
      deviceFormFactor = deviceFormFactorStateFlow.value,
      cleanupData = cleanupDataStateFlow.value
    )
  }
}
