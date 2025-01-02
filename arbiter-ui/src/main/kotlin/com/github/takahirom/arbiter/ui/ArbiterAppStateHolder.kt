package com.github.takahirom.arbiter.ui

import com.github.takahirom.arbiter.ArbiterAi
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.ArbiterDevice
import com.github.takahirom.arbiter.ArbiterProject
import com.github.takahirom.arbiter.ArbiterProjectFileContent
import com.github.takahirom.arbiter.ArbiterProjectSerializer
import com.github.takahirom.arbiter.ArbiterScenarioContent
import com.github.takahirom.arbiter.AvailableDevice
import com.github.takahirom.arbiter.arbiterDebugLog
import com.github.takahirom.arbiter.createArbiterScenario
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

class ArbiterAppStateHolder(
  val aiFactory: () -> ArbiterAi,
  val deviceFactory: (AvailableDevice) -> ArbiterDevice = { avaiableDevice ->
    avaiableDevice.connectToDevice()
  }
) {
  val devicesStateHolder = DevicesStateHolder()

  sealed interface DeviceConnectionState {
    data object NotConnected : DeviceConnectionState
    data class Connected(val device: ArbiterDevice) : DeviceConnectionState

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
  private val projectStateFlow = MutableStateFlow<ArbiterProject?>(null)
  private val allScenarioStateHoldersStateFlow: MutableStateFlow<List<ArbiterScenarioStateHolder>> =
    MutableStateFlow(listOf())
  val sortedScenariosAndDepthsStateFlow: StateFlow<List<Pair<ArbiterScenarioStateHolder, Int>>> =
    allScenarioStateHoldersStateFlow
      .flatMapLatest { scenarios: List<ArbiterScenarioStateHolder> ->
        combine(scenarios.map { scenario ->
          scenario.dependencyScenarioStateHolderStateFlow
            .map { scenario to it }
        }) { list ->
          list
        }
      }
      .map {
        val result = sortedScenarioAndDepth(it.map { it.first })
        arbiterDebugLog("sortedScenariosAndDepthsStateFlow: ${it.map { it.first.goal }} -> ${result.map { it.first.goal }}")
        result
      }
      .stateIn(
        scope = CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob()),
        started = SharingStarted.WhileSubscribed(),
        initialValue = emptyList()
      )

  fun sortedScenariosAndDepths() = sortedScenarioAndDepth(allScenarioStateHoldersStateFlow.value)
  val selectedScenarioIndex: MutableStateFlow<Int> = MutableStateFlow(0)
  private val coroutineScope =
    CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob())

  fun addSubScenario(parent: ArbiterScenarioStateHolder) {
    val scenarioStateHolder = ArbiterScenarioStateHolder().apply {
      dependencyScenarioStateHolderStateFlow.value = parent
      initializeMethodsStateFlow.value = ArbiterScenarioContent.InitializeMethods.Noop
    }
    allScenarioStateHoldersStateFlow.value += scenarioStateHolder
    selectedScenarioIndex.value =
      sortedScenariosAndDepths().indexOfFirst { it.first.idStateFlow.value == scenarioStateHolder.id }
  }

  fun addScenario() {
    val scenarioStateHolder = ArbiterScenarioStateHolder()
    allScenarioStateHoldersStateFlow.value += scenarioStateHolder
    selectedScenarioIndex.value =
      sortedScenariosAndDepths().indexOfFirst { it.first.idStateFlow.value == scenarioStateHolder.id }
  }

  var job: Job? = null

  fun runAll() {
    job?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    recreateProject()
    job = coroutineScope.launch {
      projectStateFlow.value?.scenarios?.forEach { scenario ->
        selectedScenarioIndex.value =
          sortedScenariosAndDepths().indexOfFirst { it.first.idStateFlow.value == scenario.id }
        projectStateFlow.value?.execute(scenario)
        delay(10)
      }
    }
  }

  fun run(scenarioStateHolder: ArbiterScenarioStateHolder) {
    job?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    recreateProject()
    job = coroutineScope.launch {
      projectStateFlow.value?.execute(scenarioStateHolder.createScenario(allScenarioStateHoldersStateFlow.value))
      selectedScenarioIndex.value =
        sortedScenariosAndDepths().indexOfFirst { it.first.idStateFlow.value == scenarioStateHolder.id }
    }
  }


  private fun recreateProject() {
    projectStateFlow.value?.cancel()
    val arbiterProject = ArbiterProject(
      initialArbiterScenarios = allScenarioStateHoldersStateFlow.value.map { scenario ->
        scenario.createScenario(allScenarioStateHoldersStateFlow.value)
      }
    )
    projectStateFlow.value = arbiterProject
    allScenarioStateHoldersStateFlow.value.forEach { scenarioStateHolder ->
      arbiterProject.scenarioAndExecutorsStateFlow.value.firstOrNull { (scenario, _) ->
        scenario.id == scenarioStateHolder.id
      }
        ?.let {
          scenarioStateHolder.onExecute(it.second)
        }
    }
  }

  private fun ArbiterScenarioStateHolder.createScenario(allScenarioStateHolder: List<ArbiterScenarioStateHolder>) =
    allScenarioStateHolder.map { it.createArbiterScenarioContent() }
      .createArbiterScenario(
        scenario = createArbiterScenarioContent(),
        aiFactory = aiFactory,
        deviceFactory = {
          this@ArbiterAppStateHolder.deviceFactory(devicesStateHolder.selectedDevice.value!!)
        }
      )

  private fun sortedScenarioAndDepth(allScenarios: List<ArbiterScenarioStateHolder>): List<Pair<ArbiterScenarioStateHolder, Int>> {
    // Build dependency map using goals as keys
    val dependentMap =
      mutableMapOf<ArbiterScenarioStateHolder, MutableList<ArbiterScenarioStateHolder>>()
    val rootScenarios = mutableListOf<ArbiterScenarioStateHolder>()

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
    val result = mutableListOf<Pair<ArbiterScenarioStateHolder, Int>>()
    fun dfs(scenario: ArbiterScenarioStateHolder, depth: Int) {
      result.add(scenario to depth)
      dependentMap[scenario]?.forEach {
        dfs(it, depth + 1)
      }
    }
    rootScenarios.forEach {
      dfs(it, 0)
    }

    arbiterDebugLog("Sorted scenarios and depths: $result")
    return result
  }

  fun runAllFailed() {
    job?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    recreateProject()
    job = coroutineScope.launch {
      sortedScenariosAndDepthsStateFlow.value.map { it.first }.filter { scenario ->
        !scenario.isGoalAchieved()
      }.forEach { scenarioStateHolder: ArbiterScenarioStateHolder ->
        selectedScenarioIndex.value =
          sortedScenariosAndDepths().indexOfFirst { it.first.idStateFlow.value == scenarioStateHolder.id }
        projectStateFlow.value?.execute(scenarioStateHolder.createScenario(allScenarioStateHoldersStateFlow.value))
      }
    }
  }

  private val arbiterProjectSerializer = ArbiterProjectSerializer()
  fun saveGoals(file: File?) {
    if (file == null) {
      return
    }
    val sortedScenarios = sortedScenariosAndDepthsStateFlow.value.map { it.first }
    arbiterProjectSerializer.save(
      projectFileContent = ArbiterProjectFileContent(
        scenarioContents = sortedScenarios.map {
          it.createArbiterScenarioContent()
        }
      ),
      file = file)
  }

  fun loadGoals(file: File?) {
    if (file == null) {
      return
    }
    val projectFile = ArbiterProjectSerializer().load(file)
    val scenarios = projectFile.scenarioContents
    val arbiterScenarioStateHolders = scenarios.map { scenarioContent ->
      ArbiterScenarioStateHolder().apply {
        onGoalChanged(scenarioContent.goal)
        idStateFlow.value = scenarioContent.id
        initializeMethodsStateFlow.value = scenarioContent.initializeMethods
        maxRetryState.edit {
          replace(0, length, scenarioContent.maxRetry.toString())
        }
        maxStepState.edit {
          replace(0, length, scenarioContent.maxStep.toString())
        }
        deviceFormFactorStateFlow.value = scenarioContent.deviceFormFactor
        cleanupDataStateFlow.value = scenarioContent.cleanupData
      }
    }
    scenarios.forEachIndexed { index, scenario ->
      arbiterScenarioStateHolders[index].dependencyScenarioStateHolderStateFlow.value =
        arbiterScenarioStateHolders.firstOrNull {
          it.id == scenario.dependencyId
        }
    }
    projectStateFlow.value = ArbiterProject(
      initialArbiterScenarios = arbiterScenarioStateHolders.map { it.createScenario(arbiterScenarioStateHolders) }
    )
    allScenarioStateHoldersStateFlow.value = arbiterScenarioStateHolders
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

  fun removeScenario(scenario: ArbiterScenarioStateHolder) {
    scenario.arbiterScenarioExecutorStateFlow.value?.cancel()
    allScenarioStateHoldersStateFlow.value =
      allScenarioStateHoldersStateFlow.value.filter { it != scenario }
  }
}
