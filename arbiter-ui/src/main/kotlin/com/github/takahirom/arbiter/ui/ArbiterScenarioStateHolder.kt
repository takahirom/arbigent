package com.github.takahirom.arbiter.ui

import androidx.compose.foundation.text.input.TextFieldState
import com.github.takahirom.arbiter.ArbiterCoroutinesDispatcher
import com.github.takahirom.arbiter.ArbiterScenarioContent
import com.github.takahirom.arbiter.ArbiterScenarioDeviceFormFactor
import com.github.takahirom.arbiter.ArbiterScenarioExecutor
import com.github.takahirom.arbiter.ArbiterScenarioRunningInfo
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


class ArbiterScenarioStateHolder
@OptIn(ExperimentalUuidApi::class)
constructor(
  id: String = Uuid.random().toString()
) {
  private val _id = MutableStateFlow(id)
  val id:String = _id.value
  val goalState = TextFieldState("")
  val goal get() = goalState.text.toString()
  val maxRetryState: TextFieldState = TextFieldState("3")
  val maxStepState: TextFieldState = TextFieldState("10")
  val cleanupDataStateFlow: MutableStateFlow<ArbiterScenarioContent.CleanupData> =
    MutableStateFlow(
      ArbiterScenarioContent.CleanupData.Noop
    )
  val initializeMethodsStateFlow: MutableStateFlow<ArbiterScenarioContent.InitializeMethods> =
    MutableStateFlow(ArbiterScenarioContent.InitializeMethods.Back())
  val deviceFormFactorStateFlow: MutableStateFlow<ArbiterScenarioDeviceFormFactor> =
    MutableStateFlow(ArbiterScenarioDeviceFormFactor.Mobile)
  val dependencyScenarioStateHolderStateFlow = MutableStateFlow<ArbiterScenarioStateHolder?>(null)
  val arbiterScenarioExecutorStateFlow = MutableStateFlow<ArbiterScenarioExecutor?>(null)
  private val coroutineScope = CoroutineScope(
    ArbiterCoroutinesDispatcher.dispatcher + SupervisorJob()
  )
  val isArchived = arbiterScenarioExecutorStateFlow
    .flatMapLatest { it?.isArchivedFlow ?: flowOf() }
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

  val arbiterScenarioRunningInfo: StateFlow<ArbiterScenarioRunningInfo?> =
    arbiterScenarioExecutorStateFlow
      .flatMapLatest { it?.arbiterScenarioRunningInfoFlow ?: flowOf() }
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
    return arbiterScenarioExecutorStateFlow.value?.isArchived() ?: false
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
      id = id,
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
