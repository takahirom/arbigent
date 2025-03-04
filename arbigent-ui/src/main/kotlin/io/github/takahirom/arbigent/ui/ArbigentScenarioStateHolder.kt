package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.text.input.TextFieldState
import io.github.takahirom.arbigent.*
import io.github.takahirom.arbigent.coroutines.buildSingleSourceStateFlow
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
  id: String = Uuid.random().toString(),
  private val tagManager: ArbigentTagManager
) {
  private val _id = MutableStateFlow(id)
  val idStateFlow: StateFlow<String> = _id
  val id: String get() = _id.value

  private val _aiOptions = MutableStateFlow<ArbigentAiOptions?>(null)
  val aiOptionsFlow: StateFlow<ArbigentAiOptions?> = _aiOptions

  private val _cacheOptions = MutableStateFlow(ArbigentScenarioCacheOptions())
  val cacheOptionsFlow: StateFlow<ArbigentScenarioCacheOptions> = _cacheOptions

  val goalState = TextFieldState("")
  val goal get() = goalState.text.toString()
  val noteForHumans = TextFieldState("")
  val userUserPromptTemplateState = TextFieldState(UserPromptTemplate.DEFAULT_TEMPLATE)
  val maxRetryState: TextFieldState = TextFieldState("3")
  val maxStepState: TextFieldState = TextFieldState("10")
  private val cleanupDataStateFlow: MutableStateFlow<ArbigentScenarioContent.CleanupData> =
    MutableStateFlow(
      ArbigentScenarioContent.CleanupData.Noop
    )
  val imageAssertionsStateFlow: MutableStateFlow<List<ArbigentImageAssertion>> =
    MutableStateFlow(emptyList())
  val imageAssertionsHistoryCountState: TextFieldState = TextFieldState("1")
  private val _initializationMethodStateFlow: MutableStateFlow<List<ArbigentScenarioContent.InitializationMethod>> =
    MutableStateFlow(listOf(ArbigentScenarioContent.InitializationMethod.Back()))
  val initializationMethodStateFlow: StateFlow<List<ArbigentScenarioContent.InitializationMethod>> =
    _initializationMethodStateFlow
  val deviceFormFactorStateFlow: MutableStateFlow<ArbigentScenarioDeviceFormFactor> =
    MutableStateFlow(ArbigentScenarioDeviceFormFactor.Mobile)
  fun deviceFormFactor() = deviceFormFactorStateFlow.value
  val scenarioTypeStateFlow: MutableStateFlow<ArbigentScenarioType> =
    MutableStateFlow(ArbigentScenarioType.Scenario)
  fun scenarioType() = scenarioTypeStateFlow.value

  val dependencyScenarioStateHolderStateFlow = MutableStateFlow<ArbigentScenarioStateHolder?>(null)
  val arbigentScenarioExecutorStateFlow = MutableStateFlow<ArbigentScenarioExecutor?>(null)
  private val coroutineScope = CoroutineScope(
    ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob()
  )
  @OptIn(ArbigentInternalApi::class)
  val tags = coroutineScope.buildSingleSourceStateFlow(tagManager.scenarioToTagsStateFlow) {
    it[this] ?: mutableSetOf()
  }
  val isAchieved = arbigentScenarioExecutorStateFlow
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

  fun onInitializationMethodChanged(
    index: Int,
    method: ArbigentScenarioContent.InitializationMethod
  ) {
    _initializationMethodStateFlow.value =
      initializationMethodStateFlow.value.toMutableList().apply {
        set(index, method)
      }
  }

  fun onAddImageAssertion() {
    imageAssertionsStateFlow.value += ArbigentImageAssertion("")
  }

  fun onAddInitializationMethod() {
    _initializationMethodStateFlow.value += ArbigentScenarioContent.InitializationMethod.Noop
  }

  fun onAddAsSubScenario(parent: ArbigentScenarioStateHolder) {
    dependencyScenarioStateHolderStateFlow.value = parent
    _initializationMethodStateFlow.value = listOf()
    deviceFormFactorStateFlow.value = parent.deviceFormFactor()
  }

  fun onScenarioIdChanged(id: String) {
    _id.value = id
  }

  fun onAiOptionsChanged(options: ArbigentAiOptions?) {
    if (options?.imageFormat == null && options?.imageDetail == null && options?.temperature == null) {
      _aiOptions.value = null
      return
    }
    _aiOptions.value = options
  }

  fun onCacheOptionsChanged(options: ArbigentScenarioCacheOptions) {
    _cacheOptions.value = options
  }

  fun createArbigentScenarioContent(): ArbigentScenarioContent {
    return ArbigentScenarioContent(
      id = id,
      goal = goal,
      type = scenarioType(),
      dependencyId = dependencyScenarioStateHolderStateFlow.value?.id,
      initializationMethods = initializationMethodStateFlow.value
        .filter { it !is ArbigentScenarioContent.InitializationMethod.Noop },
      noteForHumans = noteForHumans.text.toString(),
      maxRetry = maxRetryState.text.toString().toIntOrNull() ?: 3,
      maxStep = maxStepState.text.toString().toIntOrNull() ?: 10,
      tags = tagManager.tagsForScenario(this),
      deviceFormFactor = deviceFormFactorStateFlow.value,
      // This is no longer used.
      cleanupData = ArbigentScenarioContent.CleanupData.Noop,
      imageAssertionHistoryCount = imageAssertionsHistoryCountState.text.toString().toIntOrNull()
        ?: 1,
      imageAssertions = imageAssertionsStateFlow.value.filter { it.assertionPrompt.isNotBlank() },
      userPromptTemplate = userUserPromptTemplateState.text.toString(),
      aiOptions = aiOptionsFlow.value,
      cacheOptions = cacheOptionsFlow.value
    )
  }

  fun load(scenarioContent: ArbigentScenarioContent) {
    _id.value = scenarioContent.id
    onGoalChanged(scenarioContent.goal)
    maxRetryState.edit {
      replace(0, length, scenarioContent.maxRetry.toString())
    }
    maxStepState.edit {
      replace(0, length, scenarioContent.maxStep.toString())
    }
    noteForHumans.edit {
      replace(0, length, scenarioContent.noteForHumans)
    }
    userUserPromptTemplateState.edit {
      replace(0, length, scenarioContent.userPromptTemplate)
    }
    tagManager.loadTagsForScenario(this, scenarioContent.tags.map { it.name }.toSet())
    // This is no longer used.
    cleanupDataStateFlow.value = ArbigentScenarioContent.CleanupData.Noop
    imageAssertionsStateFlow.value = scenarioContent.imageAssertions.toMutableList()
    imageAssertionsHistoryCountState.edit {
      replace(0, length, scenarioContent.imageAssertionHistoryCount.toString())
    }
    _initializationMethodStateFlow.value = scenarioContent.initializationMethods.toMutableList()
    deviceFormFactorStateFlow.value = scenarioContent.deviceFormFactor
    scenarioTypeStateFlow.value = scenarioContent.type
    _aiOptions.value = scenarioContent.aiOptions
    _cacheOptions.value = scenarioContent.cacheOptions ?: ArbigentScenarioCacheOptions()
  }

  fun onRemoveInitializationMethod(index: Int) {
    _initializationMethodStateFlow.value =
      initializationMethodStateFlow.value.toMutableList().apply {
        removeAt(index)
      }
  }

  fun addTag() {
    tagManager.addTagForScenario(this, "New Tag")
  }

  fun removeTag(tagName: String) {
    tagManager.removeTagForScenario(this, tagName)
  }

  fun onTagChanged(oldName: String, newTagName: String) {
    tagManager.changeTagForScenario(this, oldName, newTagName)
  }
}
