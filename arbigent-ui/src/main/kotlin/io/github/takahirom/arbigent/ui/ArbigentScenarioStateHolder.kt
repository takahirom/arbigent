package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.text.input.TextFieldState
import io.github.takahirom.arbigent.*
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
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


class ArbigentScenarioStateHolder
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
  val cleanupDataStateFlow: MutableStateFlow<ArbigentScenarioContent.CleanupData> =
    MutableStateFlow(
      ArbigentScenarioContent.CleanupData.Noop
    )
  val imageAssertionsStateFlow: MutableStateFlow<List<ArbigentImageAssertion>> =
    MutableStateFlow(emptyList())
  val initializationMethodStateFlow: MutableStateFlow<List<ArbigentScenarioContent.InitializationMethod>> =
    MutableStateFlow(listOf(ArbigentScenarioContent.InitializationMethod.Back()))
  val deviceFormFactorStateFlow: MutableStateFlow<ArbigentScenarioDeviceFormFactor> =
    MutableStateFlow(ArbigentScenarioDeviceFormFactor.Mobile)
  fun deviceFormFactor() = deviceFormFactorStateFlow.value

  val dependencyScenarioStateHolderStateFlow = MutableStateFlow<ArbigentScenarioStateHolder?>(null)
  val arbigentScenarioExecutorStateFlow = MutableStateFlow<ArbigentScenarioExecutor?>(null)
  private val coroutineScope = CoroutineScope(
    ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob()
  )
  val isArchived = arbigentScenarioExecutorStateFlow
    .flatMapLatest { it?.isSuccessFlow ?: flowOf() }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = false
    )
  val isRunning = arbigentScenarioExecutorStateFlow
    .flatMapLatest { it?.isRunningFlow ?: flowOf(false) }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = false
    )

  val arbigentScenarioRunningInfo: StateFlow<ArbigentScenarioRunningInfo?> =
    arbigentScenarioExecutorStateFlow
      .flatMapLatest { it?.runningInfoFlow ?: flowOf() }
      .stateIn(
        scope = coroutineScope,
        started = SharingStarted.WhileSubscribed(),
        initialValue = null
      )

  fun onExecute(scenarioExecutor: ArbigentScenarioExecutor) {
    arbigentScenarioExecutorStateFlow.value = scenarioExecutor
  }

  suspend fun waitUntilFinished() {
    arbigentScenarioExecutorStateFlow.value!!.waitUntilFinished()
  }

  fun isGoalAchieved(): Boolean {
    return arbigentScenarioExecutorStateFlow.value?.isSuccessful() ?: false
  }

  fun cancel() {
    arbigentScenarioExecutorStateFlow.value?.cancel()
  }

  fun onGoalChanged(goal: String) {
    goalState.edit {
      replace(0, goalState.text.length, goal)
    }
  }

  fun createArbigentScenarioContent(): ArbigentScenarioContent {
    return ArbigentScenarioContent(
      id = id,
      goal = goal,
      dependencyId = dependencyScenarioStateHolderStateFlow.value?.id,
      initializationMethods = initializationMethodStateFlow.value
        .filter { it !is ArbigentScenarioContent.InitializationMethod.Noop },
      maxRetry = maxRetryState.text.toString().toIntOrNull() ?: 3,
      maxStep = maxStepState.text.toString().toIntOrNull() ?: 10,
      deviceFormFactor = deviceFormFactorStateFlow.value,
      cleanupData = cleanupDataStateFlow.value,
      imageAssertions = imageAssertionsStateFlow.value.filter { it.assertionPrompt.isNotBlank() }
    )
  }

  fun onAddImageAssertion() {
    imageAssertionsStateFlow.value += ArbigentImageAssertion("")
  }

  fun onAddInitializationMethod() {
    initializationMethodStateFlow.value += ArbigentScenarioContent.InitializationMethod.Noop
  }
}
