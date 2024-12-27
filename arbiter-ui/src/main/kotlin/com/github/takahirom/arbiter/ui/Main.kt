package com.github.takahirom.arbiter.ui


import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import com.github.takahirom.arbiter.Agent
import com.github.takahirom.arbiter.Arbiter
import com.github.takahirom.arbiter.ArbiterContextHolder
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.OpenAIAi
import com.github.takahirom.arbiter.ui.AppStateHolder.DeviceConnectionState
import com.github.takahirom.arbiter.ui.AppStateHolder.FileSelectionState
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.styling.Default
import org.jetbrains.jewel.intui.standalone.styling.light
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.light
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButton
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabState
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.styling.GroupHeaderStyle
import org.jetbrains.jewel.ui.component.styling.TabStyle
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.styling.TitleBarStyle
import java.awt.Window
import java.io.FileInputStream


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
    OutlinedButton(onClick = {
      appStateHolder.onClickConnect(devicesStateHolder)
    }) {
      Text("Connect to device")
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(
  appStateHolder: AppStateHolder
) {
  IntUiTheme {
    val deviceConnectionState by appStateHolder.deviceConnectionState.collectAsState()
    if (deviceConnectionState is DeviceConnectionState.NotConnected) {
      ConnectionSettingScreen(
        appStateHolder = appStateHolder
      )
      return@IntUiTheme
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
    val scenarioIndex by appStateHolder.selectedAgentIndex.collectAsState()
    Row {
      val schenarioAndDepths by appStateHolder.sortedScenariosAndDepthsStateFlow.collectAsState()
      val coroutineScope = rememberCoroutineScope()
      Column(
        Modifier
          .weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text("Scenarios")
        FlowRow {
          IconButton(onClick = {
            appStateHolder.addScenario()
          }) {
            Icon(
              key = AllIconsKeys.FileTypes.AddAny,
              contentDescription = "Add",
              hint = Size(28)
            )
          }
          IconButton(onClick = {
            appStateHolder.runAll()
          }) {
            Icon(
              key = AllIconsKeys.Actions.RunAll,
              contentDescription = "Run all",
              hint = Size(28)
            )
          }
          IconButton(onClick = {
            coroutineScope.launch {
              appStateHolder.runAllFailed()
            }
          }) {
            Icon(
              key = AllIconsKeys.Actions.Rerun,
              contentDescription = "Run all failed",
              hint = Size(28)
            )
          }
          IconButton(onClick = {
            appStateHolder.fileSelectionState.value = FileSelectionState.Saving
          }) {
            Icon(
              key = AllIconsKeys.Actions.MenuSaveall,
              contentDescription = "Save",
              hint = Size(28)
            )
          }
          IconButton(onClick = {
            appStateHolder.fileSelectionState.value = FileSelectionState.Loading
          }) {
            Icon(
              key = AllIconsKeys.Actions.MenuOpen,
              contentDescription = "Load",
              hint = Size(28)
            )
          }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
          itemsIndexed(schenarioAndDepths) { index, (scenarioStateHolder, depth) ->
            val goal by scenarioStateHolder.goalStateFlow.collectAsState()
            Box(
              modifier = Modifier.fillMaxWidth()
                .padding(
                  start = 8.dp + 12.dp * depth,
                  top = if (depth == 0) 8.dp else 0.dp,
                  end = 8.dp,
                  bottom = 4.dp
                )
                .background(
                  if (index == scenarioIndex) {
                    Color.LightGray
                  } else {
                    Color.White
                  }
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
                    key = AllIconsKeys.Actions.Checked,
                    contentDescription = "Archived",
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
      val scenarioStateHolder = schenarioAndDepths.getOrNull(scenarioIndex)
      if (scenarioStateHolder != null) {
        Column(Modifier.weight(3f)) {
          Scenario(
            scenarioStateHolder = scenarioStateHolder.first,
            onExecute = {
              appStateHolder.run(it)
            },
            onCancel = {
              it.cancel()
            },
            onRemove = {
              appStateHolder.removeScenario(it)
            }
          )
        }
      }
    }
  }
}

@Composable
private fun Scenario(
  scenarioStateHolder: ScenarioStateHolder,
  onExecute: (ScenarioStateHolder) -> Unit,
  onCancel: (ScenarioStateHolder) -> Unit,
  onRemove: (ScenarioStateHolder) -> Unit
) {
  val isAndroid by remember { mutableStateOf(true) }
  val arbiter: Arbiter? by scenarioStateHolder.arbiterStateFlow.collectAsState()
  val goal by scenarioStateHolder.goalStateFlow.collectAsState()
  var editingGoalTextState by remember(goal) { mutableStateOf(goal) }
  Column {
    Row(
      verticalAlignment = Alignment.CenterVertically
    ) {
      TextField(
        modifier = Modifier.weight(1f).testTag("goal"),
        value = editingGoalTextState,
        placeholder = { Text("Goal") },
        onValueChange = {
          editingGoalTextState = it
          scenarioStateHolder.onGoalChanged(it)
        },
      )
      OutlinedButton(onClick = {
        onExecute(scenarioStateHolder)
      }) {
        Icon(
          key = AllIconsKeys.RunConfigurations.TestState.Run,
          contentDescription = "Run",
        )
      }
      OutlinedButton(onClick = {
        onCancel(scenarioStateHolder)
      }) {
        Text("Cancel")
      }
      OutlinedButton(onClick = {
        onRemove(scenarioStateHolder)
      }) {
        Text("Remove")
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
          var editingText by remember(initializeMethods) {
            mutableStateOf(
              (initializeMethods as? InitializeMethods.OpenApp)?.packageName ?: ""
            )
          }
          RadioButton(
            selected = initializeMethods is InitializeMethods.OpenApp,
            onClick = {
              scenarioStateHolder.initializeMethodsStateFlow.value =
                InitializeMethods.OpenApp(editingText)
            }
          )
          Column {
            Text("Launch app")
            TextField(
              modifier = Modifier
                .padding(4.dp),
              enabled = initializeMethods is InitializeMethods.OpenApp,
              value = editingText,
              onValueChange = {
                editingText = it
                scenarioStateHolder.initializeMethodsStateFlow.value = InitializeMethods.OpenApp(it)
              },
            )
          }
        }
      }
      Column(Modifier.width(200.dp)) {
        Text("Scenario dependency")
        val dependency by scenarioStateHolder.dependencyScenarioStateFlow.collectAsState()

        TextField(
          modifier = Modifier
            .testTag("dependency_text_field")
            .padding(4.dp),
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
  TabStrip(
    style = TabStyle.Default.light(),
    modifier = Modifier.fillMaxWidth(),
    tabs = taskToAgents.mapIndexed { index, taskToAgent ->
      val (task, agent) = taskToAgent
      val isRunning by agent!!.isRunningStateFlow.collectAsState()
      val isArchived by agent.isArchivedStateFlow.collectAsState()
      TabData.Default(
        selected = selectedTabIndex == index,
        onClick = { selectedTabIndex = index },
        closable = false,
        content = {
          SimpleTabContent(
            state = TabState.of(selected = selectedTabIndex == index),
            label = run {
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
                ""
              }
              text
            },
            iconKey = if (isRunning) {
              AllIconsKeys.Nodes.WarningMark
            } else if (!isArchived) {
              AllIconsKeys.Nodes.WarningMark
            } else {
              AllIconsKeys.Actions.Checked
            },
          )
        },
      )
    }
  )
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
  var selectedTurn: Int? by remember(agentContext.turns.value.size) { mutableStateOf(null) }
  val turns by agentContext.turns.collectAsState()
  Row(Modifier) {
    LazyColumn(modifier = Modifier.weight(1f)) {
      items(turns.size) { index ->
        val turn = turns[index]
        Column(
          Modifier.padding(8.dp)
            .clickable { selectedTurn = index },
        ) {
          GroupHeader(
            modifier = Modifier.fillMaxWidth(),
            style = if (turn.memo.contains("Failed")) {
              GroupHeaderStyle.light()
            } else {
              GroupHeaderStyle.light()
            },
            text = "Turn ${index + 1}",
          )
          Text(
            modifier = Modifier.padding(8.dp),
            text = turn.text()
          )
        }
      }
    }
    selectedTurn?.let { selectedIndex ->
      val turn = turns[selectedIndex]
      turn.message?.let { message: String ->
        val scrollableState = rememberScrollState()
        Text(
          modifier = Modifier.verticalScroll(scrollableState).weight(2F),
          text = message
        )
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
  IntUiTheme(
    theme = JewelTheme.lightThemeDefinition(),
    styling = ComponentStyling.default().decoratedWindow(
      titleBarStyle = TitleBarStyle.light()
    ),
    swingCompatMode = true,
  ) {
    DecoratedWindow(
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
            Item("Save") {
              appStateHolder.fileSelectionState.value = FileSelectionState.Saving
            }
            Item("Load") {
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
}

