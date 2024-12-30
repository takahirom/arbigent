package com.github.takahirom.arbiter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import com.github.takahirom.arbiter.Agent
import com.github.takahirom.arbiter.Arbiter
import com.github.takahirom.arbiter.ArbiterContextHolder
import com.github.takahirom.arbiter.DeviceFormFactor
import com.github.takahirom.arbiter.DeviceOs
import com.github.takahirom.arbiter.GoalAchievedAgentCommand
import com.github.takahirom.arbiter.OpenAIAi
import com.github.takahirom.arbiter.ui.AppStateHolder.DeviceConnectionState
import com.github.takahirom.arbiter.ui.AppStateHolder.FileSelectionState
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.ComboBox
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.ListItemState
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.SimpleListItem
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.theme.simpleListItemStyle
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.styling.TitleBarStyle
import java.awt.Window
import java.io.FileInputStream

@Composable
fun App(
  appStateHolder: AppStateHolder
) {
  Box(
    Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)
  ) {
    val deviceConnectionState by appStateHolder.deviceConnectionState.collectAsState()
    if (deviceConnectionState is DeviceConnectionState.NotConnected) {
      LauncherScreen(
        appStateHolder = appStateHolder
      )
      return@Box
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
      Column(
        Modifier
          .weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        LazyColumn(modifier = Modifier.weight(1f)) {
          itemsIndexed(schenarioAndDepths) { index, (scenarioStateHolder, depth) ->
            val goal = scenarioStateHolder.goalState.text
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
                val runningInfo by scenarioStateHolder.runningInfo.collectAsState()
                Text(
                  modifier = Modifier.weight(1f),
                  text = "Goal:" + goal + "\n" + runningInfo?.toString().orEmpty()
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
                      .testTag("scenario_running")
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
            dependencyScenarioMenu = {
              selectableItem(
                selected = scenarioStateHolder.first.dependencyScenarioStateFlow.value == null,
                onClick = {
                  scenarioStateHolder.first.dependencyScenarioStateFlow.value = null
                },
                content = {
                  Text("No dependency")
                }
              )
              appStateHolder.sortedScenariosAndDepthsStateFlow.value.map { it.first }
                .filter { it != scenarioStateHolder.first }
                .forEach {
                  selectableItem(
                    selected = scenarioStateHolder.first.dependencyScenarioStateFlow.value == it,
                    onClick = {
                      scenarioStateHolder.first.dependencyScenarioStateFlow.value = it
                    },
                    content = {
                      Text(it.goal)
                    }
                  )
                }
            },
            onExecute = {
              appStateHolder.run(it)
            },
            onCancel = {
              appStateHolder.close()
              scenarioStateHolder.first.cancel()
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScenarioFileControls(appStateHolder: AppStateHolder) {
  FlowRow {
    IconActionButton(
      key = AllIconsKeys.Actions.MenuSaveall,
      onClick = {
        appStateHolder.fileSelectionState.value = FileSelectionState.Saving
      },
      contentDescription = "Save",
      hint = Size(28)
    )
    IconActionButton(
      key = AllIconsKeys.Actions.MenuOpen,
      onClick = {
        appStateHolder.fileSelectionState.value = FileSelectionState.Loading
      },
      contentDescription = "Load",
      hint = Size(28)
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScenarioControls(appStateHolder: AppStateHolder) {
  val coroutineScope = rememberCoroutineScope()
  FlowRow {
    val devicesStateHolder = appStateHolder.devicesStateHolder
    ListComboBox(
      items = DeviceOs.entries.map { it.name },
      modifier = Modifier.width(100.dp).padding(end = 2.dp),
      isEditable = false,
      maxPopupHeight = 150.dp,
      onSelectedItemChange = { itemText ->
        devicesStateHolder.selectedDeviceOs.value = DeviceOs.valueOf(itemText)
        devicesStateHolder.fetchDevices()
        devicesStateHolder.onSelectedDeviceChanged(null)
      },
    ) { itemText, isSelected, isActive, isItemHovered, isPreviewSelection ->
      SimpleListItem(
        text = itemText,
        state = ListItemState(isSelected, isItemHovered, isPreviewSelection),
        modifier = Modifier,
        style = JewelTheme.simpleListItemStyle,
        contentDescription = itemText,
      )
    }
    val selectedDevice by devicesStateHolder.selectedDevice.collectAsState()
    val items = devicesStateHolder.devices.collectAsState().value.map { it.name }
    println("selectedDevice: $selectedDevice")
    ComboBox(
      modifier = Modifier.width(170.dp).padding(end = 2.dp),
      labelText = selectedDevice?.name ?: "Select device",
      maxPopupHeight = 150.dp,
    ) {
      Column {
        items.forEach { itemText ->
          val isSelected = itemText == selectedDevice?.name
          val isItemHovered = false
          val isPreviewSelection = false
          SimpleListItem(
            text = itemText,
            state = ListItemState(isSelected, isItemHovered, isPreviewSelection),
            modifier = Modifier
              .clickable {
                devicesStateHolder.onSelectedDeviceChanged(devicesStateHolder.devices.value.firstOrNull { it.name == itemText })
                devicesStateHolder.selectedDevice.value?.let {
                  appStateHolder.onClickConnect(devicesStateHolder)
                }
              },
            style = JewelTheme.simpleListItemStyle,
            contentDescription = itemText,
          )
        }
      }
    }
    IconActionButton(
      key = AllIconsKeys.FileTypes.AddAny,
      onClick = {
        appStateHolder.addScenario()
      },
      contentDescription = "Add",
      hint = Size(28)
    )
    IconActionButton(
      key = AllIconsKeys.Actions.RunAll,
      onClick = {
        appStateHolder.runAll()
      },
      contentDescription = "Run all",
      hint = Size(28)
    )
    IconActionButton(
      key = AllIconsKeys.Actions.Rerun,
      onClick = {
        coroutineScope.launch {
          appStateHolder.runAllFailed()
        }
      },
      contentDescription = "Run all failed",
      hint = Size(28)
    )
  }
}

@Composable
private fun Scenario(
  scenarioStateHolder: ScenarioStateHolder,
  dependencyScenarioMenu: MenuScope.() -> Unit,
  onExecute: (ScenarioStateHolder) -> Unit,
  onCancel: (ScenarioStateHolder) -> Unit,
  onRemove: (ScenarioStateHolder) -> Unit,
) {
  val arbiter: Arbiter? by scenarioStateHolder.arbiterStateFlow.collectAsState()
  val goal = scenarioStateHolder.goalState
  Column(
    modifier = Modifier.padding(8.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically
    ) {
      TextField(
        modifier = Modifier.weight(1f).padding(4.dp).testTag("goal"),
        state = goal,
        placeholder = { Text("Goal") },
      )
      IconActionButton(
        key = AllIconsKeys.RunConfigurations.TestState.Run,
        onClick = {
          onExecute(scenarioStateHolder)
        },
        contentDescription = "Run",
        hint = Size(28)
      )
      IconActionButton(
        key = AllIconsKeys.Actions.Cancel,
        onClick = {
          onCancel(scenarioStateHolder)
        },
        contentDescription = "Cancel",
        hint = Size(28)
      )
      var removeDialogShowing by remember { mutableStateOf(false) }
      IconActionButton(
        key = AllIconsKeys.General.Delete,
        onClick = {
          removeDialogShowing = true
        },
        contentDescription = "Remove",
        hint = Size(28)
      )
      if (removeDialogShowing) {
        Dialog(
          onDismissRequest = { removeDialogShowing = false }
        ) {
          Column(
            modifier = Modifier.background(JewelTheme.globalColors.panelBackground).padding(8.dp)
          ) {
            Text("Are you sure you want to remove this scenario?")
            Row {
              OutlinedButton(
                onClick = {
                  removeDialogShowing = false
                }
              ) {
                Text("Cancel")
              }
              OutlinedButton(
                onClick = {
                  removeDialogShowing = false
                  onRemove(scenarioStateHolder)
                }
              ) {
                Text("Remove")
              }
            }
          }
        }
      }
    }
    Row(modifier = Modifier.padding(4.dp)) {
      Column(
        modifier = Modifier.padding(8.dp).weight(1F)
      ) {
        GroupHeader("Scenario dependency")
        val dependency by scenarioStateHolder.dependencyScenarioStateFlow.collectAsState()

        Dropdown(
          modifier = Modifier
            .testTag("dependency_dropdown")
            .padding(4.dp),
          menuContent = dependencyScenarioMenu
        ) {
          Text(dependency?.goal ?: "Select dependency")
        }
      }
      Column(
        modifier = Modifier.padding(8.dp).weight(1F)
      ) {
        val inputCommandType by scenarioStateHolder.deviceFormFactorStateFlow.collectAsState()
        GroupHeader("Device formfactor")
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButtonRow(
            text = "Mobile",
            selected = inputCommandType.isMobile(),
            onClick = {
              scenarioStateHolder.deviceFormFactorStateFlow.value = DeviceFormFactor.Mobile
            }
          )
        }
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButtonRow(
            text = "TV",
            selected = inputCommandType.isTv(),
            onClick = {
              scenarioStateHolder.deviceFormFactorStateFlow.value = DeviceFormFactor.Tv
            }
          )
        }
      }
      Column(
        modifier = Modifier.padding(8.dp).weight(1F)
      ) {
        val initializeMethods by scenarioStateHolder.initializeMethodsStateFlow.collectAsState()
        val cleanupData by scenarioStateHolder.cleanupDataStateFlow.collectAsState()
        GroupHeader("Initialize method:")
        CheckboxRow(
          text = "Cleanup app data",
          checked = cleanupData is CleanupData.Cleanup,
          onCheckedChange = {
            scenarioStateHolder.cleanupDataStateFlow.value = if (it) {
              CleanupData.Cleanup((cleanupData as? CleanupData.Cleanup)?.packageName ?: "")
            } else {
              CleanupData.Noop
            }
          }
        )
        TextField(
          modifier = Modifier
            .padding(4.dp),
          placeholder = { Text("Package name") },
          enabled = cleanupData is CleanupData.Cleanup,
          value = (cleanupData as? CleanupData.Cleanup)?.packageName ?: "",
          onValueChange = {
            scenarioStateHolder.cleanupDataStateFlow.value = CleanupData.Cleanup(it)
          },
        )
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButtonRow(
            selected = initializeMethods == InitializeMethods.Back,
            text = "Back",
            onClick = {
              scenarioStateHolder.initializeMethodsStateFlow.value = InitializeMethods.Back
            }
          )
        }
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButtonRow(
            text = "Do nothing",
            selected = initializeMethods is InitializeMethods.Noop,
            onClick = {
              scenarioStateHolder.initializeMethodsStateFlow.value = InitializeMethods.Noop
            }
          )
        }
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          var editingText by remember(initializeMethods) {
            mutableStateOf(
              (initializeMethods as? InitializeMethods.OpenApp)?.packageName ?: ""
            )
          }
          RadioButtonRow(
            selected = initializeMethods is InitializeMethods.OpenApp,
            onClick = {
              scenarioStateHolder.initializeMethodsStateFlow.value =
                InitializeMethods.OpenApp(editingText)
            }
          ) {
            Column {
              Text(modifier = Modifier.padding(top = 4.dp), text = "Launch app")
              TextField(
                modifier = Modifier
                  .padding(4.dp),
                enabled = initializeMethods is InitializeMethods.OpenApp,
                value = editingText,
                onValueChange = {
                  editingText = it
                  scenarioStateHolder.initializeMethodsStateFlow.value =
                    InitializeMethods.OpenApp(it)
                },
              )
            }
          }
        }
      }
      Column(
        modifier = Modifier.padding(8.dp).weight(1F)
      ) {
        GroupHeader("Max retry count")
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Retry count
          TextField(
            state = scenarioStateHolder.maxRetryState,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
              .padding(4.dp),
          )
        }
        GroupHeader("Max turn count")
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Retry count
          TextField(
            modifier = Modifier
              .padding(4.dp),
            state = scenarioStateHolder.maxTurnState,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          )
        }
      }
    }
    if (arbiter == null) {
      return
    }
    val taskToAgents: List<Pair<Arbiter.Task, Agent>> by arbiter!!.taskToAgentStateFlow.collectAsState()
    if (taskToAgents.isEmpty()) {
      return
    }
    ContentPanel(taskToAgents)
  }
}

@Composable
private fun ContentPanel(tasksToAgent: List<Pair<Arbiter.Task, Agent>>) {
  var selectedTurn: ArbiterContextHolder.Turn? by remember { mutableStateOf(null) }
  Row(Modifier) {
    LazyColumn(modifier = Modifier.weight(1f)) {
      itemsIndexed(items = tasksToAgent) { index, (tasks, agent) ->
        val latestContext by agent.latestArbiterContextStateFlow.collectAsState()
        val latestTurnsStateFlow = latestContext?.turns ?: return@itemsIndexed
        val turns: List<ArbiterContextHolder.Turn> by latestTurnsStateFlow.collectAsState()

        if (turns.isEmpty()) {
          return@itemsIndexed
        }
        Column(Modifier.padding(8.dp)) {
          GroupHeader(
            modifier = Modifier.fillMaxWidth(),
            text = tasks.goal + "(" + (index + 1) + "/" + tasksToAgent.size + ")",
          )
          turns.forEachIndexed { index, turn ->
            Column(
              Modifier.padding(8.dp)
                .background(
                  color = if (turn.memo.contains("Failed")) {
                    Color.Red
                  } else if (turn.agentCommand is GoalAchievedAgentCommand) {
                    Color.Green
                  } else {
                    Color.White
                  },
                )
                .clickable { selectedTurn = turn },
            ) {
              GroupHeader(
                modifier = Modifier.fillMaxWidth(),
                text = "Turn ${index + 1}",
              )
              Text(
                modifier = Modifier.padding(8.dp),
                text = turn.text()
              )
            }
          }
        }
      }
    }
    selectedTurn?.let { turn ->
      val scrollableState = rememberScrollState()
      Column(
        Modifier
          .weight(2f)
          .padding(8.dp)
          .verticalScroll(scrollableState),
      ) {
        turn.aiRequest?.let { request: String ->
          GroupHeader(
            modifier = Modifier.fillMaxWidth(),
            text = "AI Request"
          )
          Text(
            modifier = Modifier
              .padding(8.dp)
              .background(JewelTheme.globalColors.panelBackground),
            text = request
          )
        }
        turn.aiResponse?.let { response: String ->
          GroupHeader(
            modifier = Modifier.fillMaxWidth(),
            text = "AI Response"
          )
          Text(
            modifier = Modifier
              .padding(8.dp)
              .background(JewelTheme.globalColors.panelBackground),
            text = response
          )
        }
      }
      turn.screenshotFileName.let {
        Image(
          bitmap = loadImageBitmap(FileInputStream("screenshots/" + it + ".png")),
          contentDescription = "screenshot",
          modifier = Modifier.weight(1.5F)
        )
      }
    }
  }
}

fun main() = application {
  val appStateHolder = remember {
    AppStateHolder(
      aiFactory = { OpenAIAi(Preference.openAiApiKey) },
    )
  }
  AppWindow(
    appStateHolder = appStateHolder,
    onExit = {
      appStateHolder.close()
      exitApplication()
    }
  )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppWindow(
  appStateHolder: AppStateHolder,
  onExit: () -> Unit,
) {
  AppTheme {
    DecoratedWindow(
      title = "App Test AI Agent",
      onCloseRequest = {
        appStateHolder.close()
        onExit()
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
        val deviceConnectionState by appStateHolder.deviceConnectionState.collectAsState()
        val isDeviceConnected = deviceConnectionState.isConnected()
        TitleBar(
          style = TitleBarStyle
            .lightWithLightHeader(),
          gradientStartColor = JewelTheme.colorPalette.purple(8),
        ) {
          if (isDeviceConnected) {
            Box(Modifier.padding(8.dp).align(Alignment.Start)) {
              ScenarioFileControls(appStateHolder)
            }
            Box(Modifier.padding(8.dp).align(Alignment.End)) {
              ScenarioControls(appStateHolder)
            }
          }
        }
        MenuBar {
          Menu("Scenarios") {
            if (!(isDeviceConnected)) {
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

@Composable
fun AppTheme(content: @Composable () -> Unit) {
  IntUiTheme(
    theme = JewelTheme.lightThemeDefinition(),
    styling = ComponentStyling.default().decoratedWindow(
      titleBarStyle = TitleBarStyle.lightWithLightHeader()
    ),
    swingCompatMode = true,
  ) {
    content()
  }
}