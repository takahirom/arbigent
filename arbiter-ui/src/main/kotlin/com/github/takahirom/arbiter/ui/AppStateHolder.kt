package com.github.takahirom.arbiter.ui

import com.github.takahirom.arbiter.ArbiterAi
import com.github.takahirom.arbiter.ArbiterScenarioExecutor
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.ArbiterDevice
import com.github.takahirom.arbiter.ArbiterProjectSerializer
import com.github.takahirom.arbiter.AvailableDevice
import com.github.takahirom.arbiter.agentConfigBuilder
import com.github.takahirom.arbiter.connectToDevice
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

class AppStateHolder(
  val aiFactory: () -> ArbiterAi,
  val deviceFactory: (AvailableDevice) -> ArbiterDevice = { avaiableDevice ->
    connectToDevice(
      availableDevice = avaiableDevice
    )
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
        println("sortedScenariosAndDepthsStateFlow: ${it.map { it.first.goal }} -> ${result.map { it.first.goal }}")
        result
      }
      .stateIn(
        scope = CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob()),
        started = SharingStarted.WhileSubscribed(),
        initialValue = emptyList()
      )
  val selectedAgentIndex: MutableStateFlow<Int> = MutableStateFlow(0)
  private val coroutineScope =
    CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob())

  fun addSubScenario(parent: ArbiterScenarioStateHolder) {
    val scenarioStateHolder = ArbiterScenarioStateHolder(
      initialDevice = (deviceConnectionState.value as DeviceConnectionState.Connected).device,
      ai = aiFactory()
    ).apply {
      dependencyScenarioStateHolderStateFlow.value = parent
      initializeMethodsStateFlow.value = ArbiterProjectSerializer.InitializeMethods.Noop
    }
    allScenarioStateHoldersStateFlow.value += scenarioStateHolder
    selectedAgentIndex.value =
      sortedScenariosAndDepthsStateFlow.value.indexOfFirst { it.first == scenarioStateHolder }
  }

  fun addScenario() {
    val scenarioStateHolder = ArbiterScenarioStateHolder(
      initialDevice = (deviceConnectionState.value as DeviceConnectionState.Connected).device,
      ai = aiFactory()
    )
    allScenarioStateHoldersStateFlow.value += scenarioStateHolder
    selectedAgentIndex.value =
      sortedScenariosAndDepthsStateFlow.value.indexOfFirst { it.first == scenarioStateHolder }
  }

  var job: Job? = null

  fun runAll() {
    job?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    job = coroutineScope.launch {
      sortedScenariosAndDepthsStateFlow.value.map { it.first }.forEachIndexed { index, scenario ->
        selectedAgentIndex.value =
          sortedScenariosAndDepthsStateFlow.value.indexOfFirst { it.first == scenario }
        executeWithDependencies(scenario)
        delay(10)
      }
    }
  }

  fun run(scenario: ArbiterScenarioStateHolder) {
    job?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    job = coroutineScope.launch {
      selectedAgentIndex.value =
        sortedScenariosAndDepthsStateFlow.value.indexOfFirst { it.first == scenario }
      executeWithDependencies(scenario)
    }
  }

  private suspend fun executeWithDependencies(scenarioStateHolder: ArbiterScenarioStateHolder) {
    val allScenarios = allScenarioStateHoldersStateFlow.value
      .map { it.createArbiterScenario() }
    val projectConfig = ArbiterProjectSerializer.ArbiterProjectConfig(allScenarios)
    scenarioStateHolder.onExecute(arbiterExecutorScenario = projectConfig
      .scenarioDependencyList(
        scenario = scenarioStateHolder.createArbiterScenario(),
        aiFactory = aiFactory,
        deviceFactory = {
          deviceFactory(devicesStateHolder.selectedDevice.value!!)
        }
      ))
  }

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

    println("Sorted scenarios and depths: $result")
    return result
  }

  fun runAllFailed() {
    job?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    job = coroutineScope.launch {
      sortedScenariosAndDepthsStateFlow.value.map { it.first }.filter { scenario ->
        !scenario.isGoalAchieved()
      }.forEach { scenario: ArbiterScenarioStateHolder ->
        selectedAgentIndex.value =
          sortedScenariosAndDepthsStateFlow.value.indexOfFirst { it.first == scenario }
        executeWithDependencies(scenario)
        delay(10)
        scenario.waitUntilFinished()
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
      ArbiterProjectSerializer.ArbiterProjectConfig(sortedScenarios.map {
        it.createArbiterScenario()
      }
      ), file)
  }

  fun loadGoals(file: File?) {
    if (file == null) {
      return
    }
    val project = arbiterProjectSerializer.load(file)
    val scenarioContents = project.scenarios
    val arbiterScenarioStateHolders = scenarioContents.map { scenarioContent ->
      ArbiterScenarioStateHolder(
        (deviceConnectionState.value as DeviceConnectionState.Connected).device,
        ai = aiFactory()
      ).apply {
        onGoalChanged(scenarioContent.goal)
        initializeMethodsStateFlow.value = scenarioContent.initializeMethods
        maxRetryState.edit {
          replace(0, length, scenarioContent.maxRetry.toString())
        }
        maxTurnState.edit {
          replace(0, length, scenarioContent.maxStep.toString())
        }
        deviceFormFactorStateFlow.value = scenarioContent.deviceFormFactor
        cleanupDataStateFlow.value = scenarioContent.cleanupData
      }
    }
    scenarioContents.forEachIndexed { index, scenarioContent ->
      arbiterScenarioStateHolders[index].dependencyScenarioStateHolderStateFlow.value =
        arbiterScenarioStateHolders.firstOrNull { it.goal == scenarioContent.goalDependency }
    }
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

    allScenarioStateHoldersStateFlow.value.forEach {
      it.onDeviceChanged(device)
    }
  }

  fun removeScenario(scenario: ArbiterScenarioStateHolder) {
    scenario.arbiterScenarioExecutorStateFlow.value?.cancel()
    allScenarioStateHoldersStateFlow.value =
      allScenarioStateHoldersStateFlow.value.filter { it != scenario }
  }
}
