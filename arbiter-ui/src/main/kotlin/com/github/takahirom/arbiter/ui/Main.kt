package com.github.takahirom.arbiter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import com.github.takahirom.arbiter.Agent
import com.github.takahirom.arbiter.Ai
import com.github.takahirom.arbiter.Arbiter
import com.github.takahirom.arbiter.ArbiterContextHolder
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.Device
import com.github.takahirom.arbiter.MaestroDevice
import com.github.takahirom.arbiter.OpenAIAi
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import maestro.Maestro
import maestro.drivers.AndroidDriver
import java.awt.Window
import java.io.File
import java.io.FileInputStream

sealed interface DeviceConnectionState {
  object NotConnected : DeviceConnectionState
  data class Connected(val device: Device) : DeviceConnectionState
}

sealed interface FileSelectionState {
  object NotSelected : FileSelectionState
  object Loading : FileSelectionState
  object Saving : FileSelectionState
}

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


class DevicesStateHolder {
  val isAndroid: MutableStateFlow<Boolean> = MutableStateFlow(true)
  val devices: MutableStateFlow<List<Dadb>> = MutableStateFlow(listOf())
  val selectedDevice: MutableStateFlow<Dadb?> = MutableStateFlow(null)

  init {
    isAndroid.onEach { isAndroid ->
      devices.value = if (isAndroid) {
        Dadb.list()
      } else {
        throw NotImplementedError("iOS is not supported yet")
      }
    }.launchIn(CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob()))
  }
}

@Composable
fun ConnectionSettingScreen(
  appStateHolder: AppStateHolder
) {
  val devicesStateHolder = remember { DevicesStateHolder() }
  Column {
    Row {
      val isAndroid by devicesStateHolder.isAndroid.collectAsState()
      RadioButton(
        selected = isAndroid,
        onClick = { devicesStateHolder.isAndroid.value = true }
      )
      Text("Android")
      RadioButton(
        selected = !isAndroid,
        onClick = { devicesStateHolder.isAndroid.value = false }
      )
      Text("iOS")
    }
    val devices by devicesStateHolder.devices.collectAsState()
    Row {
      devices.forEachIndexed { index, dadb ->
        val selectedDevice by devicesStateHolder.selectedDevice.collectAsState()
        RadioButton(
          selected = dadb == selectedDevice || (selectedDevice == null && index == 0),
          onClick = { devicesStateHolder.selectedDevice.value = dadb }
        )
        Text(dadb.toString())
      }
    }
    Button(onClick = {
      appStateHolder.onClickConnect(devicesStateHolder)
    }) {
      Text("Connect to device")
    }
  }
}

@Composable
fun App(
  appStateHolder: AppStateHolder
) {
  MaterialTheme {
    Surface {
      val deviceConnectionState by appStateHolder.deviceConnectionState.collectAsState()
      if (deviceConnectionState is DeviceConnectionState.NotConnected) {
        ConnectionSettingScreen(
          appStateHolder = appStateHolder
        )
        return@Surface
      }
      val fileSelectionState by appStateHolder.fileSelectionState.collectAsState()
      if (fileSelectionState is FileSelectionState.Loading) {
        FileLoadDialog(
          title = "Choose a file",
          onCloseRequest = { file ->
            appStateHolder.loadGoals(file)
            appStateHolder.fileSelectionState.value = FileSelectionState.NotSelected
          }
        )
      } else if (fileSelectionState is FileSelectionState.Saving) {
        FileSaveDialog(
          title = "Save a file",
          onCloseRequest = { file ->
            appStateHolder.saveGoals(file)
            appStateHolder.fileSelectionState.value = FileSelectionState.NotSelected
          }
        )
      }
      Row {
        val schenarioAndDepths by appStateHolder.sortedScenariosAndDepthsStateFlow.collectAsState()
        val coroutineScope = rememberCoroutineScope()
        Column(
          Modifier
            .weight(1f),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Button(onClick = {
            appStateHolder.addScenario()
          }) {
            Row(
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add scenario",
                tint = Color.White
              )
              Text("Add scenario")
            }
          }
          Button(onClick = {
            appStateHolder.runAll()
          }) {
            Row(
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Run all scenarios",
                tint = Color.Green
              )
              Text("Run all scenarios")
            }
          }

          Button(onClick = {
            coroutineScope.launch {
              appStateHolder.runAllFailed()
            }
          }) {
            Row(
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Run all failed scenarios",
                tint = Color.Green
              )
              Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Run all failed scenario",
                tint = Color.Yellow
              )
              Text("Run failed scenarios")
            }
          }
          Button(onClick = {
            appStateHolder.fileSelectionState.value = FileSelectionState.Saving
          }) {
            Row(
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Save all scenarios",
                tint = Color.White
              )
              Text("Save all scenarios")
            }
          }
          Button(onClick = {
            appStateHolder.fileSelectionState.value = FileSelectionState.Loading
          }) {
            Row(
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Load all scenarios",
                tint = Color.White
              )
              Text("Load all scenarios")
            }
          }
          LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(schenarioAndDepths) { index, (scenarioStateHolder, depth) ->
              val goal by scenarioStateHolder.goalStateFlow.collectAsState()
              Card(
                modifier = Modifier.fillMaxWidth()
                  .padding(
                    start = 8.dp + 12.dp * depth,
                    top = if (depth == 0) 8.dp else 0.dp,
                    end = 8.dp,
                    bottom = 4.dp
                  )
                  .clickable { appStateHolder.selectedAgentIndex.value = index },
              ) {
                Row(
                  modifier = Modifier.padding(8.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    modifier = Modifier.weight(1f),
                    text = "Goal:" + goal
                  )
                  val isArchived by scenarioStateHolder.isArchived.collectAsState()
                  if (isArchived) {
                    Icon(
                      imageVector = Icons.Default.Check,
                      contentDescription = "Archived",
                      tint = Color.White,
                      modifier = Modifier.padding(8.dp)
                        .size(40.dp)
                        .clip(
                          CircleShape
                        )
                        .background(Color.Green)
                    )
                  }
                  val isRunning by scenarioStateHolder.isRunning.collectAsState()
                  if (isRunning) {
                    CircularProgressIndicator(
                      modifier = Modifier.padding(8.dp)
                    )
                  }
                }
              }
            }
          }
        }
        val index by appStateHolder.selectedAgentIndex.collectAsState()
        val scenarioStateHolder = schenarioAndDepths.getOrNull(index)
        if (scenarioStateHolder != null) {
          Column(Modifier.weight(3f)) {
            Agent(
              scenarioStateHolder = scenarioStateHolder.first,
              onExecute = {
                appStateHolder.run(it)
              },
              onCancel = {
                it.cancel()
              }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun Agent(
  scenarioStateHolder: ScenarioStateHolder,
  onExecute: (ScenarioStateHolder) -> Unit,
  onCancel: (ScenarioStateHolder) -> Unit
) {
  val isAndroid by remember { mutableStateOf(true) }
  val arbiter: Arbiter? by scenarioStateHolder.arbiterStateFlow.collectAsState()
  val goal by scenarioStateHolder.goalStateFlow.collectAsState()
  var editingGoalTextState by remember(goal) { mutableStateOf(goal) }
  Column {
    Row(
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text("Goal:")
      TextField(
        modifier = Modifier.weight(1f).testTag("goal"),
        value = editingGoalTextState,
        onValueChange = {
          editingGoalTextState = it
          scenarioStateHolder.onGoalChanged(it)
        },
      )
      Button(onClick = {
        onExecute(scenarioStateHolder)
      }) {
        Text("Run")
      }
      Button(onClick = {
        onCancel(scenarioStateHolder)
      }) {
        Text("Cancel")
      }
    }
    Row(modifier = Modifier.padding(8.dp)) {
      Column(
        modifier = Modifier.padding(8.dp)
      ) {
        Text("Device inputs:")
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButton(
            selected = isAndroid,
            onClick = { }
          )
          Text("Mobile")
        }
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButton(
            selected = !isAndroid,
            onClick = { }
          )
          Text("TV")
        }
      }
      Column(
        modifier = Modifier.padding(8.dp)
      ) {
        val initializeMethods by scenarioStateHolder.initializeMethodsStateFlow.collectAsState()
        Text("Initialize method:")
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButton(
            selected = initializeMethods == InitializeMethods.Back,
            onClick = {
              scenarioStateHolder.initializeMethodsStateFlow.value = InitializeMethods.Back
            }
          )
          Text("Back")
        }
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButton(
            selected = initializeMethods is InitializeMethods.Noop,
            onClick = {
              scenarioStateHolder.initializeMethodsStateFlow.value = InitializeMethods.Noop
            }
          )
          Text("Do nothing")
        }
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          var editingText by remember { mutableStateOf("") }
          RadioButton(
            selected = initializeMethods is InitializeMethods.OpenApp,
            onClick = {
              scenarioStateHolder.initializeMethodsStateFlow.value =
                InitializeMethods.OpenApp(editingText)
            }
          )
          Column {
            Text("Launch app")
            BasicTextField(
              modifier = Modifier
                .background(
                  color = MaterialTheme.colors.surface,
                  shape = RoundedCornerShape(6.dp)
                )
                .padding(4.dp),
              enabled = initializeMethods is InitializeMethods.OpenApp,
              textStyle = MaterialTheme.typography.body2,
              value = editingText,
              onValueChange = {
                editingText = it
                scenarioStateHolder.initializeMethodsStateFlow.value = InitializeMethods.OpenApp(it)
              },
            )
          }
        }
      }
      Column {
        Text("Scenario dependency")
        val dependency by scenarioStateHolder.dependencyScenarioStateFlow.collectAsState()
        BasicTextField(
          modifier = Modifier
            .background(
              color = MaterialTheme.colors.surface,
              shape = RoundedCornerShape(6.dp)
            )
            .padding(4.dp),
          textStyle = MaterialTheme.typography.body2,
          value = dependency ?: "",
          onValueChange = {
            if (it.isEmpty()) {
              scenarioStateHolder.dependencyScenarioStateFlow.value = null
            } else {
              scenarioStateHolder.dependencyScenarioStateFlow.value = it
            }
          },
        )
      }
    }
    if (arbiter == null) {
      return
    }
    val taskToAgents: List<Pair<Arbiter.Task, Agent>> by arbiter!!.taskToAgentStateFlow.collectAsState()
    if (taskToAgents.isEmpty()) {
      return
    }
    ArbiterContextHistories(taskToAgents)
  }
}

@Composable
private fun ArbiterContextHistories(
  taskToAgents: List<Pair<Arbiter.Task, Agent>>
) {
  // History Tabs
  var selectedTabIndex by remember { mutableStateOf(taskToAgents.lastIndex) }
  TabRow(
    selectedTabIndex = minOf(selectedTabIndex, taskToAgents.lastIndex),
    backgroundColor = MaterialTheme.colors.primary,
    contentColor = Color.White
  ) {
    taskToAgents.forEachIndexed { index, taskToAgent ->
      val (task, agent) = taskToAgent
      Tab(
        text = {
          Row {
            val isRunning by agent!!.isRunningStateFlow.collectAsState()
            val text = task.goal + ":" + if (isRunning) {
              val latestContext by agent.latestArbiterContextStateFlow.collectAsState()
              latestContext?.let {
                val turns: List<ArbiterContextHolder.Turn>? by it.turns.collectAsState()
                if (turns.isNullOrEmpty()) {
                  "Initializing"
                } else {
                  "Running"
                }
              } ?: {
                ""
              }
            } else {
              "History"
            }
            Text(
              modifier = Modifier.weight(1f),
              textAlign = TextAlign.Center,
              text = text
            )
            val isArchived by agent.isArchivedStateFlow.collectAsState()
            if (isRunning) {
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp).testTag("scenario_running"),
                color = Color.White
              )
            } else if (!isArchived) {
              Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Goal not achieved",
                tint = Color.White
              )
            } else {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Goal achieved",
                tint = Color.White
              )
            }
          }
        },
        selected = selectedTabIndex == index,
        onClick = { selectedTabIndex = index }
      )
    }
  }
  val taskToAgent = taskToAgents.getOrNull(selectedTabIndex)
  taskToAgent?.let { (task, agent) ->
    val latestContext by agent!!.latestArbiterContextStateFlow.collectAsState()
    latestContext?.let {
      ArbiterContext(it)
    }
  }
}

@Composable
private fun ArbiterContext(agentContext: ArbiterContextHolder) {
  var selectedIndex: Int? by remember(agentContext.turns.value.size) { mutableStateOf(null) }
  val turns by agentContext.turns.collectAsState()
  Row(Modifier) {
    LazyColumn(modifier = Modifier.weight(1f)) {
      items(turns.size) { index ->
        val turn = turns[index]
        Card(
          modifier = Modifier.fillMaxWidth().padding(8.dp)
            .clickable { selectedIndex = index },
          backgroundColor = if (turn.memo.contains("Failed")) {
            MaterialTheme.colors.error
          } else {
            MaterialTheme.colors.surface
          }
        ) {
          Text(
            modifier = Modifier.padding(8.dp),
            text = turn.text()
          )
        }
      }
    }
    selectedIndex?.let { selectedIndex ->
      val turn = turns[selectedIndex]
      turn.message?.let { message: String ->
        val scrollableState = rememberScrollState()
        Card(
          Modifier.weight(2F)
            .padding(8.dp),
        ) {
          Text(
            modifier = Modifier.verticalScroll(scrollableState),
            text = message
          )
        }
      }
      turn.screenshotFileName.let {
        Image(
          bitmap = loadImageBitmap(FileInputStream("screenshots/" + it + ".png")),
          contentDescription = "screenshot",
          modifier = Modifier.weight(2F)
        )
      }
    }
  }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() = application {
  val appStateHolder = remember {
    AppStateHolder(
      aiFacotry = { OpenAIAi(System.getenv("API_KEY")!!) },
    )
  }
  AppWindow(appStateHolder, LocalWindowExceptionHandlerFactory)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ApplicationScope.AppWindow(
  appStateHolder: AppStateHolder,
  LocalWindowExceptionHandlerFactory: ProvidableCompositionLocal<WindowExceptionHandlerFactory>
) {
  Window(
    title = "App Test AI Agent",
    onCloseRequest = {
      appStateHolder.close()
      exitApplication()
    }) {
    CompositionLocalProvider(
      LocalWindowExceptionHandlerFactory provides object : WindowExceptionHandlerFactory {
        override fun exceptionHandler(window: Window): WindowExceptionHandler {
          return WindowExceptionHandler { throwable ->
            throwable.printStackTrace()
          }
        }
      }
    ) {

      MenuBar {
        Menu("Scenarios") {
          if (!(appStateHolder.deviceConnectionState.value is DeviceConnectionState.Connected)) {
            return@Menu
          }
          Item("Add") {
            appStateHolder.addScenario()
          }
          Item("Run all") {
            appStateHolder.runAll()
          }
          Item("Run all failed") {
            appStateHolder.runAllFailed()
          }
          Item("Save scenarios") {
            appStateHolder.fileSelectionState.value = FileSelectionState.Saving
          }
          Item("Load scenarios") {
            appStateHolder.fileSelectionState.value = FileSelectionState.Loading
          }
        }
      }
      App(
        appStateHolder = appStateHolder,
      )
    }
  }
}

