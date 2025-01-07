package io.github.takahirom.arbigent.ui

import io.github.takahirom.arbigent.ArbigentAi
import io.github.takahirom.arbigent.ArbigentAvailableDevice
import io.github.takahirom.arbigent.ArbigentCoroutinesDispatcher
import io.github.takahirom.arbigent.ArbigentDevice
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.ArbigentProject
import io.github.takahirom.arbigent.ArbigentProjectFileContent
import io.github.takahirom.arbigent.ArbigentProjectSerializer
import io.github.takahirom.arbigent.ArbigentScenario
import io.github.takahirom.arbigent.ArbigentScenarioContent
import io.github.takahirom.arbigent.ArbigentDeviceOs
import io.github.takahirom.arbigent.FailedToArchiveException
import io.github.takahirom.arbigent.arbigentDebugLog
import io.github.takahirom.arbigent.createArbigentScenario
import io.github.takahirom.arbigent.fetchAvailableDevicesByOs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ArbigentInternalApi::class)
class ArbigentAppStateHolder(
  val aiFactory: () -> ArbigentAi,
  val deviceFactory: (ArbigentAvailableDevice) -> ArbigentDevice = { avaiableDevice ->
    avaiableDevice.connectToDevice()
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

  sealed interface FileSelectionState {
    data object NotSelected : FileSelectionState
    data object Loading : FileSelectionState
    data object Saving : FileSelectionState
  }

  val deviceConnectionState: MutableStateFlow<DeviceConnectionState> =
    MutableStateFlow(DeviceConnectionState.NotConnected)
  val fileSelectionState: MutableStateFlow<FileSelectionState> =
    MutableStateFlow(FileSelectionState.NotSelected)
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

  fun sortedScenariosAndDepths() = sortedScenarioAndDepth(allScenarioStateHoldersStateFlow.value)

  val selectedScenarioIndex: MutableStateFlow<Int> = MutableStateFlow(0)
  private val coroutineScope =
    CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob())

  fun addSubScenario(parent: ArbigentScenarioStateHolder) {
    val scenarioStateHolder = ArbigentScenarioStateHolder().apply {
      dependencyScenarioStateHolderStateFlow.value = parent
      initializeMethodsStateFlow.value = ArbigentScenarioContent.InitializeMethods.Noop
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

  var job: Job? = null

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
        .map{ scenarioStateHolder ->
          scenarioStateHolder.createScenario(allScenarioStateHoldersStateFlow.value)
        }
        .filter { it.isLeaf }
        .forEach{ scenario ->
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
  fun saveGoals(file: File?) {
    if (file == null) {
      return
    }
    val sortedScenarios = sortedScenariosAndDepthsStateFlow.value.map { it.first }
    arbigentProjectSerializer.save(
      projectFileContent = ArbigentProjectFileContent(
        scenarioContents = sortedScenarios.map {
          it.createArbigentScenarioContent()
        },
      ),
      file = file
    )
  }

  fun loadGoals(file: File?) {
    if (file == null) {
      return
    }
    val projectFile = ArbigentProjectSerializer().load(file)
    val scenarios = projectFile.scenarioContents
    val arbigentScenarioStateHolders = scenarios.map { scenarioContent ->
      ArbigentScenarioStateHolder(id = scenarioContent.id).apply {
        onGoalChanged(scenarioContent.goal)
        initializeMethodsStateFlow.value = scenarioContent.initializeMethods
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
    projectStateFlow.value = ArbigentProject(
      initialScenarios = arbigentScenarioStateHolders.map {
        it.createScenario(
          arbigentScenarioStateHolders
        )
      },
    )
    allScenarioStateHoldersStateFlow.value = arbigentScenarioStateHolders
  }

  fun close() {
    job?.cancel()
  }

  fun onClickConnect(devicesStateHolder: DevicesStateHolder) {
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

  fun removeScenario(scenario: ArbigentScenarioStateHolder) {
    scenario.arbigentScenarioExecutorStateFlow.value?.cancel()
    allScenarioStateHoldersStateFlow.value =
      allScenarioStateHoldersStateFlow.value.filter { it != scenario }
  }
}
