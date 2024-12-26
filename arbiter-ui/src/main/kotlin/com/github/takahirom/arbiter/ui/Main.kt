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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.github.takahirom.arbiter.Arbiter
import com.github.takahirom.arbiter.ArbiterContextHolder
import com.github.takahirom.arbiter.GoalAchievedAgentCommand
import com.github.takahirom.arbiter.MaestroDevice
import com.github.takahirom.arbiter.OpenAIAi
import com.github.takahirom.arbiter.arbiter
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import maestro.Maestro
import maestro.drivers.AndroidDriver
import java.io.File
import java.io.FileInputStream

sealed interface DeviceConnectionState {
  object NotConnected : DeviceConnectionState
  data class Connected(val maestro: Maestro) : DeviceConnectionState
}

sealed interface FileSelectionState {
  object NotSelected : FileSelectionState
  object Loading : FileSelectionState
  object Saving : FileSelectionState
}

class AppStateHolder {
  val deviceConnectionState: MutableStateFlow<DeviceConnectionState> =
    MutableStateFlow(DeviceConnectionState.NotConnected)
  val fileSelectionState: MutableStateFlow<FileSelectionState> =
    MutableStateFlow(FileSelectionState.NotSelected)
  val scenarios: MutableStateFlow<List<ScenarioStateHolder>> = MutableStateFlow(listOf())
  val selectedAgentIndex: MutableStateFlow<Int> = MutableStateFlow(0)
  val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  fun addScenario() {
    scenarios.value += ScenarioStateHolder(
      createArbiter((deviceConnectionState.value as DeviceConnectionState.Connected).maestro)
    )
    selectedAgentIndex.value = scenarios.value.size - 1
  }

  var job: Job? = null

  fun runAll() {
    job?.cancel()
    scenarios.value.forEach { it.cancel() }
    job = coroutineScope.launch {
      scenarios.value.forEachIndexed { index, schenario ->
        selectedAgentIndex.value = index
        schenario.execute()
        delay(10)
        schenario.waitUntilFinished()
      }
    }
  }

  fun runAllFailed() {
    job?.cancel()
    scenarios.value.forEach { it.cancel() }
    job = coroutineScope.launch {
      scenarios.value.withIndex().filter { scenario ->
//        scenario.contextStateFlow.value?.turns?.value?. {
//          scenario.agentCommand is GoalAchievedAgentCommand
//        } ?: true
        !scenario.value.isGoalAchieved()
      }.forEach { (index, scenario: ScenarioStateHolder) ->
        selectedAgentIndex.value = index
        scenario.execute()
        delay(10)
        scenario.waitUntilFinished()
      }
    }
  }

  fun saveGoals(file: File?) {
    if (file == null) {
      return
    }
    file.writeText(scenarios.value.map { it.goal }.joinToString("\n") { it })
  }

  fun loadGoals(file: File?) {
    if (file == null) {
      return
    }
    scenarios.value = file.readLines().map {
      ScenarioStateHolder(
        createArbiter((deviceConnectionState.value as DeviceConnectionState.Connected).maestro)
      ).apply {
        onGoalChanged(it)
      }
    }
  }

  fun close() {
    coroutineScope.cancel()
  }
}

private fun createArbiter(maestro: Maestro) = arbiter {
  ai(OpenAIAi(System.getenv("API_KEY")!!))
  device(MaestroDevice(maestro))
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
    }.launchIn(CoroutineScope(Dispatchers.Default + SupervisorJob()))
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
      appStateHolder.deviceConnectionState.value = DeviceConnectionState.Connected(maestro)
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
    Row {
      val schenarios by appStateHolder.scenarios.collectAsState()
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
          itemsIndexed(schenarios) { index, scenarioStateHolder ->
            val goal by scenarioStateHolder.goalStateFlow.collectAsState()
            Card(
              modifier = Modifier.fillMaxWidth().padding(8.dp)
                .clickable { appStateHolder.selectedAgentIndex.value = index },
            ) {
              Row(
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
      val scenarioStateHolder = schenarios.getOrNull(index)
      if (scenarioStateHolder != null) {
        Column(Modifier.weight(3f)) {
          Agent(scenarioStateHolder)
        }
      }
    }
  }
}

@Composable
private fun Agent(scenarioStateHolder: ScenarioStateHolder) {
  val isAndroid by remember { mutableStateOf(true) }
  val arbiter: Arbiter? by scenarioStateHolder.arbiterStateFlow.collectAsState()
  if (arbiter == null) {
    return
  }
  arbiter!!
  val goal by scenarioStateHolder.goalStateFlow.collectAsState()
  var editingGoalTextState by remember(goal) { mutableStateOf(goal) }
  Column {
    Row(
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.padding(8.dp)) {
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
      Text("Goal:")
      TextField(
        modifier = Modifier.weight(1f),
        value = editingGoalTextState,
        onValueChange = {
          editingGoalTextState = it
          scenarioStateHolder.onGoalChanged(it)
        },
      )
      Button(onClick = {
        scenarioStateHolder.execute()
      }) {
        Text("Run")
      }
      Button(onClick = {
        scenarioStateHolder.cancel()
      }) {
        Text("Cancel")
      }
    }
    val histories by arbiter!!.arbiterContextHistoryStateFlow.collectAsState()
    if (histories.isEmpty()) {
      return
    }
    // History Tabs
    var selectedTabIndex by remember { mutableStateOf(histories.lastIndex) }
    TabRow(
      selectedTabIndex = minOf(selectedTabIndex, histories.lastIndex),
      backgroundColor = MaterialTheme.colors.primary,
      contentColor = Color.White
    ) {
      histories.forEachIndexed { index, history ->
        Tab(
          text = {
            Row {
              val isRunning by arbiter!!.isRunningStateFlow.collectAsState()
              val isRunningItem = index == histories.lastIndex && isRunning
              val text = if (isRunningItem) {
                "Running"
              } else {
                "History"
              }
              Text("$text ${index + 1}")
              if (isRunningItem) {
                CircularProgressIndicator(
                  modifier = Modifier.size(16.dp),
                  color = Color.White
                )
              } else if (!history.turns.value.any { it.agentCommand is GoalAchievedAgentCommand }) {
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
    val slectedHistory = histories.getOrNull(selectedTabIndex)
    slectedHistory?.let { agentContext ->
      ArbiterContext(agentContext)
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

fun main() = application {
  val appStateHolder = remember { AppStateHolder() }
  Window(
    title = "App Test AI Agent",
    onCloseRequest = {
      appStateHolder.close()
      exitApplication()
    }) {
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
    val deviceConnectionState by appStateHolder.deviceConnectionState.collectAsState()
    if (deviceConnectionState is DeviceConnectionState.NotConnected) {
      ConnectionSettingScreen(
        appStateHolder = appStateHolder
      )
      return@Window
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
      return@Window
    } else if (fileSelectionState is FileSelectionState.Saving) {
      FileSaveDialog(
        title = "Save a file",
        onCloseRequest = { file ->
          appStateHolder.saveGoals(file)
          appStateHolder.fileSelectionState.value = FileSelectionState.NotSelected
        }
      )
      return@Window
    }
    App(
      appStateHolder = appStateHolder,
    )
  }
}

