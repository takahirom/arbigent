package io.github.takahirom.arbigent.ui

import io.github.takahirom.arbigent.*
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.github.takahirom.arbigent.result.StepFeedback
import io.github.takahirom.arbigent.result.StepFeedbackEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.collections.firstOrNull

@OptIn(ArbigentInternalApi::class)
class ArbigentAppStateHolder(
  private val aiFactory: () -> ArbigentAi,
  val deviceFactory: (ArbigentAvailableDevice) -> ArbigentDevice = { avaiableDevice ->
    ArbigentGlobalStatus.onConnect {
      avaiableDevice.connectToDevice()
    }
  },
  val availableDeviceListFactory: (ArbigentDeviceOs) -> List<ArbigentAvailableDevice> = { os ->
    fetchAvailableDevicesByOs(os)
  }
) {
  // AppSettings for working directory
  private val appSettingsStateHolder = AppSettingsStateHolder()
  val appSettings get() = appSettingsStateHolder.appSettings
  val devicesStateHolder = DevicesStateHolder(availableDeviceListFactory)

  sealed interface DeviceConnectionState {
    data object NotConnected : DeviceConnectionState
    data class Connected(val device: ArbigentDevice) : DeviceConnectionState

    fun isConnected(): Boolean = this is Connected
  }

  sealed interface ProjectDialogState {
    data object NotSelected : ProjectDialogState
    data object LoadProjectContent : ProjectDialogState
    data object SaveProjectContent : ProjectDialogState
    data object SaveProjectResult : ProjectDialogState
    data object ShowProjectSettings : ProjectDialogState
    data object ShowGenerateScenarioDialog : ProjectDialogState
  }

  val deviceConnectionState: MutableStateFlow<DeviceConnectionState> =
    MutableStateFlow(DeviceConnectionState.NotConnected)
  val projectDialogState: MutableStateFlow<ProjectDialogState> =
    MutableStateFlow(ProjectDialogState.NotSelected)
  private val projectStateFlow = MutableStateFlow<ArbigentProject?>(null)
  private val allScenarioStateHoldersStateFlow: MutableStateFlow<List<ArbigentScenarioStateHolder>> =
    MutableStateFlow(listOf())
  val sortedScenariosAndDepthsStateFlow: StateFlow<List<Pair<ArbigentScenarioStateHolder, Int>>> =
    allScenarioStateHoldersStateFlow
      .flatMapLatest { scenarios: List<ArbigentScenarioStateHolder> ->
        combine(scenarios.map { scenario ->
          scenario.dependencyScenarioStateHolderStateFlow
            .map { scenario to it }
        }) { list ->
          list
        }
      }
      .map {
        val result = sortedScenarioAndDepth(it.map { it.first })
        arbigentDebugLog("sortedScenariosAndDepthsStateFlow: ${it.map { it.first.goal }} -> ${result.map { it.first.goal }}")
        result
      }
      .stateIn(
        scope = CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob()),
        started = SharingStarted.WhileSubscribed(),
        initialValue = emptyList()
      )
  val promptFlow = MutableStateFlow(ArbigentPrompt())
  val tagManager = ArbigentTagManager()
  val cacheStrategyFlow = MutableStateFlow(CacheStrategy())
  val aiOptionsFlow = MutableStateFlow<ArbigentAiOptions?>(null)
  val mcpJsonFlow = MutableStateFlow("{}")
  val appUiStructureFlow = MutableStateFlow("")
  val scenarioGenerationCustomInstructionFlow = MutableStateFlow("")
  val defaultDeviceFormFactorFlow =
    MutableStateFlow<ArbigentScenarioDeviceFormFactor>(ArbigentScenarioDeviceFormFactor.Unspecified)
  val decisionCache = cacheStrategyFlow
    .map {
      val decisionCacheStrategy = it.aiDecisionCacheStrategy
      decisionCacheStrategy.toCache()
    }
    .stateIn(
      scope = CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob()),
      started = SharingStarted.Eagerly,
      initialValue = ArbigentAiDecisionCache.Disabled
    )

  fun sortedScenariosAndDepths() = sortedScenarioAndDepth(allScenarioStateHoldersStateFlow.value)

  val selectedScenarioIndex: MutableStateFlow<Int> = MutableStateFlow(0)
  private val coroutineScope =
    CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob())

  val stepFeedbacks: MutableStateFlow<Set<StepFeedback>> = MutableStateFlow(setOf())

  fun addSubScenario(parent: ArbigentScenarioStateHolder) {
    val scenarioStateHolder = ArbigentScenarioStateHolder(tagManager = tagManager).apply {
      onAddAsSubScenario(parent)
    }
    allScenarioStateHoldersStateFlow.value += scenarioStateHolder
    selectedScenarioIndex.value =
      sortedScenariosAndDepths().indexOfFirst { it.first.id == scenarioStateHolder.id }
  }

  fun addScenario() {
    val scenarioStateHolder = ArbigentScenarioStateHolder(tagManager = tagManager)
    allScenarioStateHoldersStateFlow.value += scenarioStateHolder
    selectedScenarioIndex.value =
      sortedScenariosAndDepths().indexOfFirst { it.first.id == scenarioStateHolder.id }
  }

  private var job: Job? = null

  fun runAll() {
    job?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    recreateProject()
    job = coroutineScope.launch {
      projectStateFlow.value?.scenarios
        ?.filter { it.isLeaf }
        ?.forEach { scenario ->
          selectedScenarioIndex.value =
            sortedScenariosAndDepths().indexOfFirst { it.first.id == scenario.id }
          executeScenario(scenario)
          delay(10)
        }
    }
  }

  fun run(scenarioStateHolder: ArbigentScenarioStateHolder) {
    job?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    recreateProject()
    job = coroutineScope.launch {
      executeScenario(scenarioStateHolder.createScenario(allScenarioStateHoldersStateFlow.value))
      selectedScenarioIndex.value =
        sortedScenariosAndDepths().indexOfFirst { it.first.id == scenarioStateHolder.id }
    }
  }


  private fun recreateProject() {
    projectStateFlow.value?.cancel()
    val arbigentProject = ArbigentProject(
      settings = ArbigentProjectSettings(
        prompt = promptFlow.value,
        cacheStrategy = cacheStrategyFlow.value,
        aiOptions = aiOptionsFlow.value,
        mcpJson = mcpJsonFlow.value,
        appUiStructure = appUiStructureFlow.value,
        defaultDeviceFormFactor = defaultDeviceFormFactorFlow.value
      ),
      initialScenarios = allScenarioStateHoldersStateFlow.value.map { scenario ->
        scenario.createScenario(allScenarioStateHoldersStateFlow.value)
      },
      appSettings = appSettings
    )
    projectStateFlow.value = arbigentProject
    allScenarioStateHoldersStateFlow.value.forEach { scenarioStateHolder ->
      arbigentProject.scenarioAssignments().firstOrNull { (scenario, _) ->
        scenario.id == scenarioStateHolder.id
      }
        ?.let {
          scenarioStateHolder.onExecute(it.scenarioExecutor)
        }
    }
  }

  private val deviceCache = mutableMapOf<ArbigentAvailableDevice, ArbigentDevice>()

  private fun ArbigentScenarioStateHolder.createScenario(allScenarioStateHolder: List<ArbigentScenarioStateHolder>) =
    allScenarioStateHolder.map { it.createArbigentScenarioContent() }
      .createArbigentScenario(
        projectSettings = ArbigentProjectSettings(
          this@ArbigentAppStateHolder.promptFlow.value,
          this@ArbigentAppStateHolder.cacheStrategyFlow.value,
          this@ArbigentAppStateHolder.aiOptionsFlow.value,
          this@ArbigentAppStateHolder.mcpJsonFlow.value,
          this@ArbigentAppStateHolder.appUiStructureFlow.value,
          this@ArbigentAppStateHolder.scenarioGenerationCustomInstructionFlow.value,
          this@ArbigentAppStateHolder.defaultDeviceFormFactorFlow.value
        ),
        scenario = createArbigentScenarioContent(),
        aiFactory = aiFactory,
        deviceFactory = {
          val selectedDevice = devicesStateHolder.selectedDevice.value!!
          deviceCache.getOrPut(selectedDevice) {
            this@ArbigentAppStateHolder.deviceFactory(selectedDevice)
          }
        },
        aiDecisionCache = decisionCache.value,
        appSettings = appSettings
      )

  private fun sortedScenarioAndDepth(allScenarios: List<ArbigentScenarioStateHolder>): List<Pair<ArbigentScenarioStateHolder, Int>> {
    // Build dependency map using goals as keys
    val dependentMap =
      mutableMapOf<ArbigentScenarioStateHolder, MutableList<ArbigentScenarioStateHolder>>()
    val rootScenarios = mutableListOf<ArbigentScenarioStateHolder>()

    allScenarios.forEach { scenario ->
      allScenarios.firstOrNull { it == scenario.dependencyScenarioStateHolderStateFlow.value }
        ?.let {
          if (it == scenario) {
            rootScenarios.add(scenario)
            return@forEach
          }
          dependentMap.getOrPut(it) { mutableListOf() }.add(scenario)
        } ?: run {
        rootScenarios.add(scenario)
      }
    }
    dependentMap.forEach { (k, v) ->
      if (v.isEmpty()) {
        rootScenarios.add(k)
      }
    }

    // Assign depths using BFS
    val result = mutableListOf<Pair<ArbigentScenarioStateHolder, Int>>()
    fun dfs(scenario: ArbigentScenarioStateHolder, depth: Int) {
      result.add(scenario to depth)
      dependentMap[scenario]?.forEach {
        dfs(it, depth + 1)
      }
    }
    rootScenarios.forEach {
      dfs(it, 0)
    }
    return result
  }

  fun runAllFailed() {
    job?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    recreateProject()
    job = coroutineScope.launch {
      sortedScenariosAndDepthsStateFlow.value.map { it.first }.filter { scenario ->
        !scenario.isGoalAchieved()
      }
        .map { scenarioStateHolder ->
          scenarioStateHolder.createScenario(allScenarioStateHoldersStateFlow.value)
        }
        .filter { it.isLeaf }
        .forEach { scenario ->
          selectedScenarioIndex.value =
            sortedScenariosAndDepths().indexOfFirst { it.first.id == scenario.id }
          executeScenario(scenario)
        }
    }
  }

  private suspend fun executeScenario(scenario: ArbigentScenario) {
    try {
      projectStateFlow.value?.execute(scenario)
    } catch (e: FailedToArchiveException) {
      arbigentDebugLog("Failed to archive scenario: ${e.message}")
    }
  }

  private val arbigentProjectSerializer = ArbigentProjectSerializer()
  fun saveProjectContents(file: File?) {
    if (file == null) {
      return
    }
    val sortedScenarios = sortedScenariosAndDepthsStateFlow.value.map { it.first }
    arbigentProjectSerializer.save(
      projectFileContent = ArbigentProjectFileContent(
        settings = ArbigentProjectSettings(
          promptFlow.value,
          cacheStrategyFlow.value,
          aiOptionsFlow.value,
          mcpJsonFlow.value,
          appUiStructureFlow.value,
          scenarioGenerationCustomInstructionFlow.value,
          defaultDeviceFormFactorFlow.value
        ),
        scenarioContents = sortedScenarios.map {
          it.createArbigentScenarioContent()
        },
      ),
      file = file
    )
  }

  fun loadProjectContents(file: File?) {
    if (file == null) {
      return
    }
    val projectFile = ArbigentProjectSerializer().load(file)
    val scenarios = projectFile.scenarioContents
    val arbigentScenarioStateHolders = scenarios.map { scenarioContent ->
      ArbigentScenarioStateHolder(
        id = scenarioContent.id,
        tagManager = tagManager,
      ).apply {
        load(scenarioContent)
      }
    }
    scenarios.forEachIndexed { index, scenario ->
      arbigentScenarioStateHolders[index].dependencyScenarioStateHolderStateFlow.value =
        arbigentScenarioStateHolders.firstOrNull {
          it.id == scenario.dependencyId
        }
    }
    promptFlow.value = projectFile.settings.prompt
    cacheStrategyFlow.value = projectFile.settings.cacheStrategy
    aiOptionsFlow.value = projectFile.settings.aiOptions
    mcpJsonFlow.value = projectFile.settings.mcpJson
    appUiStructureFlow.value = projectFile.settings.appUiStructure
    scenarioGenerationCustomInstructionFlow.value = projectFile.settings.scenarioGenerationCustomInstruction
    projectStateFlow.value = ArbigentProject(
      settings = projectFile.settings,
      initialScenarios = arbigentScenarioStateHolders.map {
        it.createScenario(
          arbigentScenarioStateHolders
        )
      },
      appSettings = appSettings
    )
    allScenarioStateHoldersStateFlow.value = arbigentScenarioStateHolders
  }

  fun cancel() {
    job?.cancel()
  }

  fun close() {
    job?.cancel()
    projectStateFlow.value?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    deviceConnectionState.value = DeviceConnectionState.NotConnected
    ArbigentGlobalStatus.onDisconnect {
      deviceCache.values.forEach { it.close() }
    }
  }

  fun onClickConnect(devicesStateHolder: DevicesStateHolder) {
    coroutineScope.launch {
      val currentConnection = deviceConnectionState.value
      if (currentConnection is DeviceConnectionState.Connected) {
        currentConnection.device.close()
      }
      if (devicesStateHolder.selectedDevice.value == null) {
        devicesStateHolder.onSelectedDeviceChanged(devicesStateHolder.devices.value.firstOrNull())
      }
      val selectedDevice = devicesStateHolder.selectedDevice.value!!
      var device = deviceCache.getOrPut(selectedDevice) {
        deviceFactory(devicesStateHolder.selectedDevice.value!!)
      }
      if (device.isClosed()) {
        device = deviceFactory(devicesStateHolder.selectedDevice.value!!)
        deviceCache[selectedDevice] = device
      }
      deviceConnectionState.value = DeviceConnectionState.Connected(device)
    }
  }

  fun removeScenario(scenario: ArbigentScenarioStateHolder) {
    scenario.arbigentScenarioExecutorStateFlow.value?.cancel()
    allScenarioStateHoldersStateFlow.value =
      allScenarioStateHoldersStateFlow.value.filter { it != scenario }
    val removedScenarioDependency = scenario.dependencyScenarioStateHolderStateFlow.value
    allScenarioStateHoldersStateFlow.value.forEach {
      if (it.dependencyScenarioStateHolderStateFlow.value == scenario) {
        it.dependencyScenarioStateHolderStateFlow.value = removedScenarioDependency
      }
    }
  }

  fun saveProjectResult(file: File?) {
    if (file == null) {
      return
    }
    projectStateFlow.value?.getResult()?.copy(stepFeedbacks = stepFeedbacks.value.toList())?.let {
      file.mkdirs()
      arbigentProjectSerializer.save(it, File(file, "result.yml"))
      ArbigentHtmlReport().saveReportHtml(file.absolutePath, it)
    }
  }

  fun onPromptChanged(prompt: ArbigentPrompt) {
    promptFlow.value = prompt
  }

  fun onCacheStrategyChanged(strategy: CacheStrategy) {
    cacheStrategyFlow.value = strategy
  }

  fun onAiOptionsChanged(options: ArbigentAiOptions?) {
    if (options == ArbigentAiOptions()) {
      aiOptionsFlow.value = null
      return
    }
    aiOptionsFlow.value = options
  }

  fun onMcpJsonChanged(json: String) {
    mcpJsonFlow.value = json
  }

  fun onAppUiStructureChanged(structure: String) {
    appUiStructureFlow.value = structure
  }

  fun onScenarioGenerationCustomInstructionChanged(instruction: String) {
    scenarioGenerationCustomInstructionFlow.value = instruction
  }

  fun onDefaultDeviceFormFactorChanged(formFactor: ArbigentScenarioDeviceFormFactor) {
    defaultDeviceFormFactorFlow.value = formFactor
  }

  fun scenarioCountById(newScenarioId: String): Int {
    return allScenarioStateHoldersStateFlow.value.count { it.id == newScenarioId }
  }

  fun onStepFeedback(feedback: StepFeedbackEvent) {
    when (feedback) {
      is StepFeedback -> {
        stepFeedbacks.value += feedback
      }

      is StepFeedbackEvent.RemoveBad -> {
        stepFeedbacks.value = stepFeedbacks.value.filter {
          !(it is StepFeedback.Bad && it.stepId == feedback.stepId)
        }.toSet()
      }

      is StepFeedbackEvent.RemoveGood -> {
        stepFeedbacks.value = stepFeedbacks.value.filter {
          !(it is StepFeedback.Good && it.stepId == feedback.stepId)
        }.toSet()
      }
    }
  }

  fun onGenerateScenarios(
    scenariosToGenerate: String,
    appUiStructure: String,
    customInstruction: String,
    useExistingScenarios: Boolean
  ) {
    val ai = getAi()
    val generatedScenarios = ai.generateScenarios(
      ArbigentAi.ScenarioGenerationInput(
        scenariosToGenerate,
        appUiStructure,
        customInstruction,
        if (useExistingScenarios) {
          allScenarioStateHoldersStateFlow.value.map {
            it.createArbigentScenarioContent()
          }
        } else {
          emptyList()
        }
      )
    )
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    val scenarios = allScenarioStateHoldersStateFlow.value + generatedScenarios.scenarios.map {
      ArbigentScenarioStateHolder(tagManager = tagManager).apply {
        load(it)
        isNewlyGenerated.value = true
      }
    }
    generatedScenarios.scenarios.forEach { generatedScenario ->
      val scenario = scenarios.firstOrNull { it.id == generatedScenario.id }
      val dependencyScenario = scenarios.firstOrNull { it.id == generatedScenario.dependencyId }
      scenario?.dependencyScenarioStateHolderStateFlow?.value = dependencyScenario
    }
    allScenarioStateHoldersStateFlow.value = scenarios
  }

  fun getAi(): ArbigentAi {
    return aiFactory()
  }
}
