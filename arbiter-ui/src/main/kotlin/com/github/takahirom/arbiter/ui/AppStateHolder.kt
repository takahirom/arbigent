package com.github.takahirom.arbiter.ui

import com.github.takahirom.arbiter.ArbiterAi
import com.github.takahirom.arbiter.ArbiterScenarioExecutor
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.ArbiterDevice
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
  val deviceFactory: (DevicesStateHolder) -> ArbiterDevice = { devicesStateHolder ->
    connectToDevice(
      availableDevice = devicesStateHolder.selectedDevice.value!!
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
  val scenariosStateFlow: MutableStateFlow<List<ArbiterScenarioStateHolder>> = MutableStateFlow(listOf())
  val sortedScenariosAndDepthsStateFlow: StateFlow<List<Pair<ArbiterScenarioStateHolder, Int>>> =
    scenariosStateFlow
      .flatMapLatest { scenarios: List<ArbiterScenarioStateHolder> ->
        combine(scenarios.map { scenario ->
          scenario.dependencyScenarioStateFlow
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
      dependencyScenarioStateFlow.value = parent
      initializeMethodsStateFlow.value = InitializeMethods.Noop
    }
    scenariosStateFlow.value += scenarioStateHolder
    selectedAgentIndex.value =
      sortedScenariosAndDepthsStateFlow.value.indexOfFirst { it.first == scenarioStateHolder }
  }

  fun addScenario() {
    val scenarioStateHolder = ArbiterScenarioStateHolder(
      initialDevice = (deviceConnectionState.value as DeviceConnectionState.Connected).device,
      ai = aiFactory()
    )
    scenariosStateFlow.value += scenarioStateHolder
    selectedAgentIndex.value =
      sortedScenariosAndDepthsStateFlow.value.indexOfFirst { it.first == scenarioStateHolder }
  }

  var job: Job? = null

  fun runAll() {
    job?.cancel()
    scenariosStateFlow.value.forEach { it.cancel() }
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
    scenariosStateFlow.value.forEach { it.cancel() }
    job = coroutineScope.launch {
      selectedAgentIndex.value =
        sortedScenariosAndDepthsStateFlow.value.indexOfFirst { it.first == scenario }
      executeWithDependencies(scenario)
    }
  }

  suspend fun executeWithDependencies(scenario: ArbiterScenarioStateHolder) {
    scenario.onExecute(scenarioDependencyList(scenario))
  }

  fun scenarioDependencyList(
    scenario: ArbiterScenarioStateHolder,
  ): ArbiterScenarioExecutor.Scenario {
    val visited = mutableSetOf<ArbiterScenarioStateHolder>()
    val result = mutableListOf<ArbiterScenarioExecutor.ArbiterAgentTask>()
    fun dfs(scenario: ArbiterScenarioStateHolder) {
      if (visited.contains(scenario)) {
        return
      }
      visited.add(scenario)
      scenario.dependencyScenarioStateFlow.value?.let { dependency ->
        scenariosStateFlow.value.find { it == dependency }?.let {
          dfs(it)
        }
      }
      result.add(
        ArbiterScenarioExecutor.ArbiterAgentTask(
          goal = scenario.goal,
          agentConfig = scenario.createAgentConfig(),
        )
      )
    }
    dfs(scenario)
    println("executing:" + result)
    return ArbiterScenarioExecutor.Scenario(
      arbiterAgentTasks = result,
      maxRetry = scenario.maxRetryState.text.toString().toIntOrNull() ?: 3,
      maxStepCount = scenario.maxTurnState.text.toString().toIntOrNull() ?: 10,
      deviceFormFactor = scenario.deviceFormFactorStateFlow.value
    )
  }

  private fun sortedScenarioAndDepth(allScenarios: List<ArbiterScenarioStateHolder>): List<Pair<ArbiterScenarioStateHolder, Int>> {
    // Build dependency map using goals as keys
    val dependentMap = mutableMapOf<ArbiterScenarioStateHolder, MutableList<ArbiterScenarioStateHolder>>()
    val rootScenarios = mutableListOf<ArbiterScenarioStateHolder>()

    allScenarios.forEach { scenario ->
      allScenarios.firstOrNull { it == scenario.dependencyScenarioStateFlow.value }?.let {
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
    scenariosStateFlow.value.forEach { it.cancel() }
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

  private val scenarioSerializer = ScenarioSerializer()
  fun saveGoals(file: File?) {
    if (file == null) {
      return
    }
    val sortedScenarios = sortedScenariosAndDepthsStateFlow.value.map { it.first }
    scenarioSerializer.save(sortedScenarios, file)
  }

  fun loadGoals(file: File?) {
    if (file == null) {
      return
    }
    val scenarioContents = scenarioSerializer.load(file)
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
          replace(0, length, scenarioContent.maxTurn.toString())
        }
        deviceFormFactorStateFlow.value = scenarioContent.deviceFormFactor
        cleanupDataStateFlow.value = scenarioContent.cleanupData
      }
    }
    scenarioContents.forEachIndexed { index, scenarioContent ->
      arbiterScenarioStateHolders[index].dependencyScenarioStateFlow.value =
        arbiterScenarioStateHolders.firstOrNull { it.goal == scenarioContent.dependency }
    }
    scenariosStateFlow.value = arbiterScenarioStateHolders
  }

  fun close() {
    job?.cancel()
  }

  fun onClickConnect(devicesStateHolder: DevicesStateHolder) {
    val currentConnection = deviceConnectionState.value
    if(currentConnection is DeviceConnectionState.Connected) {
      currentConnection.device.close()
    }
    if (devicesStateHolder.selectedDevice.value == null) {
      devicesStateHolder.onSelectedDeviceChanged(devicesStateHolder.devices.value.firstOrNull())
    }
    val device = deviceFactory(devicesStateHolder)
    deviceConnectionState.value = DeviceConnectionState.Connected(device)

    scenariosStateFlow.value.forEach {
      it.onDeviceChanged(device)
    }
  }

  fun removeScenario(scenario: ArbiterScenarioStateHolder) {
    scenario.arbiterStateFlow.value?.cancel()
    scenariosStateFlow.value = scenariosStateFlow.value.filter { it != scenario }
  }
}
