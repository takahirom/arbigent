package io.github.takahirom.arbigent.ui

import io.github.takahirom.arbigent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

@OptIn(ArbigentInternalApi::class)
class ArbigentAppStateHolder(
  val aiFactory: () -> ArbigentAi,
  val deviceFactory: (ArbigentAvailableDevice) -> ArbigentDevice = { avaiableDevice ->
    ArbigentGlobalStatus.onConnect {
      avaiableDevice.connectToDevice()
    }
  },
  val availableDeviceListFactory: (ArbigentDeviceOs) -> List<ArbigentAvailableDevice> = { os ->
    fetchAvailableDevicesByOs(os)
  }
) {
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

  fun sortedScenariosAndDepths() = sortedScenarioAndDepth(allScenarioStateHoldersStateFlow.value)

  val selectedScenarioIndex: MutableStateFlow<Int> = MutableStateFlow(0)
  private val coroutineScope =
    CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob())

  fun addSubScenario(parent: ArbigentScenarioStateHolder) {
    val scenarioStateHolder = ArbigentScenarioStateHolder().apply {
      dependencyScenarioStateHolderStateFlow.value = parent
      initializationMethodStateFlow.value = listOf()
      deviceFormFactorStateFlow.value = parent.deviceFormFactor()
    }
    allScenarioStateHoldersStateFlow.value += scenarioStateHolder
    selectedScenarioIndex.value =
      sortedScenariosAndDepths().indexOfFirst { it.first.id == scenarioStateHolder.id }
  }

  fun addScenario() {
    val scenarioStateHolder = ArbigentScenarioStateHolder()
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
      settings = ArbigentProjectSettings(promptFlow.value),
      initialScenarios = allScenarioStateHoldersStateFlow.value.map { scenario ->
        scenario.createScenario(allScenarioStateHoldersStateFlow.value)
      },
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
        projectSettings = ArbigentProjectSettings(promptFlow.value),
        scenario = createArbigentScenarioContent(),
        aiFactory = aiFactory,
        deviceFactory = {
          val selectedDevice = devicesStateHolder.selectedDevice.value!!
          deviceCache.getOrPut(selectedDevice) {
            this@ArbigentAppStateHolder.deviceFactory(selectedDevice)
          }
        }
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

    arbigentDebugLog("Sorted scenarios and depths: $result")
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
        settings = ArbigentProjectSettings(promptFlow.value),
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
      ArbigentScenarioStateHolder(id = scenarioContent.id).apply {
        onGoalChanged(scenarioContent.goal)
        initializationMethodStateFlow.value =
          scenarioContent.initializationMethods.ifEmpty { listOf(scenarioContent.initializeMethods) }
        maxRetryState.edit {
          replace(0, length, scenarioContent.maxRetry.toString())
        }
        maxStepState.edit {
          replace(0, length, scenarioContent.maxStep.toString())
        }
        deviceFormFactorStateFlow.value = scenarioContent.deviceFormFactor
        cleanupDataStateFlow.value = scenarioContent.cleanupData
        imageAssertionsStateFlow.value = scenarioContent.imageAssertions
      }
    }
    scenarios.forEachIndexed { index, scenario ->
      arbigentScenarioStateHolders[index].dependencyScenarioStateHolderStateFlow.value =
        arbigentScenarioStateHolders.firstOrNull {
          it.id == scenario.dependencyId
        }
    }
    promptFlow.value = projectFile.settings.prompt
    projectStateFlow.value = ArbigentProject(
      settings = projectFile.settings,
      initialScenarios = arbigentScenarioStateHolders.map {
        it.createScenario(
          arbigentScenarioStateHolders
        )
      },
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
      val device = deviceFactory(devicesStateHolder.selectedDevice.value!!)
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
    projectStateFlow.value?.getResult()?.let {
      file.mkdirs()
      arbigentProjectSerializer.save(it, File(file, "result.yml"))
      ArbigentHtmlReport().saveReportHtml(file.absolutePath, it)
    }
  }

  fun onPromptChanged(prompt: ArbigentPrompt) {
    promptFlow.value = prompt
  }
}
