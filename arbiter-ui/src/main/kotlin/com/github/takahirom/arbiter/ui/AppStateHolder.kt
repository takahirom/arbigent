package com.github.takahirom.arbiter.ui

import com.github.takahirom.arbiter.Ai
import com.github.takahirom.arbiter.Arbiter
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.Device
import com.github.takahirom.arbiter.MaestroDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import maestro.Maestro
import maestro.drivers.AndroidDriver
import java.io.File

class AppStateHolder(
  val aiFacotry: () -> Ai,
  val deviceFactory: (DevicesStateHolder) -> Device = { devicesStateHolder ->
    if (!devicesStateHolder.isAndroid.value) {
      throw NotImplementedError("iOS is not supported yet")
    }
    var dadb = devicesStateHolder.selectedDevice.value
      ?: devicesStateHolder.devices.value.firstOrNull()
      ?: throw IllegalStateException("No device selected")
    val driver = AndroidDriver(
      dadb
    )
    val maestro = try {
      Maestro.android(
        driver
      )
    } catch (e: Exception) {
      driver.close()
      dadb.close()
      throw e
    }
    MaestroDevice(maestro)
  }
) {
  sealed interface DeviceConnectionState {
    object NotConnected : DeviceConnectionState
    data class Connected(val device: Device) : DeviceConnectionState
  }

  sealed interface FileSelectionState {
    object NotSelected : FileSelectionState
    object Loading : FileSelectionState
    object Saving : FileSelectionState
  }

  val deviceConnectionState: MutableStateFlow<DeviceConnectionState> =
    MutableStateFlow(DeviceConnectionState.NotConnected)
  val fileSelectionState: MutableStateFlow<FileSelectionState> =
    MutableStateFlow(FileSelectionState.NotSelected)
  val scenariosStateFlow: MutableStateFlow<List<ScenarioStateHolder>> = MutableStateFlow(listOf())
  val sortedScenariosAndDepthsStateFlow = scenariosStateFlow
    .flatMapLatest { scenarios: List<ScenarioStateHolder> ->
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
  val coroutineScope = CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob())

  fun addScenario() {
    scenariosStateFlow.value += ScenarioStateHolder(
      device = (deviceConnectionState.value as DeviceConnectionState.Connected).device,
      ai = aiFacotry()
    )
    selectedAgentIndex.value = scenariosStateFlow.value.size - 1
  }

  var job: Job? = null

  fun runAll() {
    job?.cancel()
    scenariosStateFlow.value.forEach { it.cancel() }
    job = coroutineScope.launch {
      scenariosStateFlow.value.forEachIndexed { index, scenario ->
        selectedAgentIndex.value = index
        executeWithDependencies(scenario)
        delay(10)
      }
    }
  }

  fun run(scenario: ScenarioStateHolder) {
    job?.cancel()
    scenariosStateFlow.value.forEach { it.cancel() }
    job = coroutineScope.launch {
      selectedAgentIndex.value = scenariosStateFlow.value.indexOf(scenario)
      executeWithDependencies(scenario)
    }
  }

  suspend fun executeWithDependencies(schenario: ScenarioStateHolder) {
    schenario.execute(scenarioDependencyList(schenario))
  }

  fun scenarioDependencyList(
    scenario: ScenarioStateHolder,
  ): Arbiter.Scenario {
    val visited = mutableSetOf<ScenarioStateHolder>()
    val result = mutableListOf<Arbiter.Task>()
    fun dfs(scenario: ScenarioStateHolder) {
      if (visited.contains(scenario)) {
        return
      }
      visited.add(scenario)
      scenario.dependencyScenarioStateFlow.value?.let { dependency ->
        scenariosStateFlow.value.find { it.goal == dependency }?.let {
          dfs(it)
        }
      }
      result.add(
        Arbiter.Task(
          goal = scenario.goal,
          agentConfig = scenario.createAgentConfig(
            scenario.device,
            scenario.ai
          ),
        )
      )
    }
    dfs(scenario)
    println("executing:" + result)
    return Arbiter.Scenario(result)
  }

  private fun sortedScenarioAndDepth(allScenarios: List<ScenarioStateHolder>): List<Pair<ScenarioStateHolder, Int>> {
    // Build dependency map using goals as keys
    val dependentMap = mutableMapOf<ScenarioStateHolder, MutableList<ScenarioStateHolder>>()
    val rootScenarios = mutableListOf<ScenarioStateHolder>()

    allScenarios.forEach { scenario ->
      allScenarios.firstOrNull { it.goal == scenario.dependencyScenarioStateFlow.value }?.let {
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
    val result = mutableListOf<Pair<ScenarioStateHolder, Int>>()
    fun dfs(scenario: ScenarioStateHolder, depth: Int) {
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
      scenariosStateFlow.value.withIndex().filter { scenario ->
        !scenario.value.isGoalAchieved()
      }.forEach { (index, scenario: ScenarioStateHolder) ->
        selectedAgentIndex.value = index
        executeWithDependencies(scenario)
        delay(10)
        scenario.waitUntilFinished()
      }
    }
  }

  fun saveGoals(file: File?) {
    if (file == null) {
      return
    }
    file.writeText(scenariosStateFlow.value.map { it.goal }.joinToString("\n") { it })
  }

  fun loadGoals(file: File?) {
    if (file == null) {
      return
    }
    scenariosStateFlow.value = file.readLines().map {
      ScenarioStateHolder(
        (deviceConnectionState.value as DeviceConnectionState.Connected).device,
        ai = aiFacotry()
      ).apply {
        onGoalChanged(it)
      }
    }
  }

  fun close() {
    coroutineScope.cancel()
  }

  fun onClickConnect(devicesStateHolder: DevicesStateHolder) {
    deviceConnectionState.value = DeviceConnectionState.Connected(deviceFactory(devicesStateHolder))
  }
}
