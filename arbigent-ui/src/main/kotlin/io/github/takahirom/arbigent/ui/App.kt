package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentDeviceOs
import io.github.takahirom.arbigent.arbigentDebugLog
import io.github.takahirom.arbigent.ui.ArbigentAppStateHolder.DeviceConnectionState
import io.github.takahirom.arbigent.ui.ArbigentAppStateHolder.ProjectDialogState
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.theme.simpleListItemStyle

@Composable
fun App(
  appStateHolder: ArbigentAppStateHolder
) {
  Column(
    Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)
  ) {
    val deviceConnectionState by appStateHolder.deviceConnectionState.collectAsState()
    if (deviceConnectionState is DeviceConnectionState.NotConnected) {
      LauncherScreen(
        appStateHolder = appStateHolder,
        modifier = Modifier.align(Alignment.CenterHorizontally).weight(1F)
      )
    } else {
      MainScreen(
        appStateHolder,
        modifier = Modifier.weight(1F)
      )
    }
    BottomConsole()
  }
}

@Composable
private fun MainScreen(
  appStateHolder: ArbigentAppStateHolder,
  modifier: Modifier
) {
  val projectDialogState by appStateHolder.projectDialogState.collectAsState()
  if (projectDialogState is ProjectDialogState.LoadProjectContent) {
    FileLoadDialog(
      title = "Choose a file",
      onCloseRequest = { file ->
        appStateHolder.loadProjectContents(file)
        appStateHolder.projectDialogState.value = ProjectDialogState.NotSelected
      }
    )
  } else if (projectDialogState is ProjectDialogState.SaveProjectContent) {
    FileSaveDialog(
      title = "Save a file",
      onCloseRequest = { file ->
        appStateHolder.saveProjectContents(file)
        appStateHolder.projectDialogState.value = ProjectDialogState.NotSelected
      }
    )
  } else if (projectDialogState is ProjectDialogState.SaveProjectResult) {
    FileSaveDialog(
      title = "Save a file",
      onCloseRequest = { file ->
        appStateHolder.saveProjectResult(file)
        appStateHolder.projectDialogState.value = ProjectDialogState.NotSelected
      }
    )
  } else if (projectDialogState is ProjectDialogState.ShowProjectSettings) {
    ProjectSettingsDialog(
      appStateHolder = appStateHolder,
      onCloseRequest = {
        appStateHolder.projectDialogState.value = ProjectDialogState.NotSelected
      }
    )
  }
  val scenarioIndex by appStateHolder.selectedScenarioIndex.collectAsState()
  var scenariosWidth by remember { mutableStateOf(200.dp) }
  Row(modifier) {
    val scenarioAndDepths by appStateHolder.sortedScenariosAndDepthsStateFlow.collectAsState()
    LeftScenariosPanel(scenarioAndDepths, scenariosWidth, scenarioIndex, appStateHolder)
    Divider(
      orientation = Orientation.Vertical,
      modifier = Modifier
        .fillMaxHeight()
        .pointerInput(Unit) {
          detectDragGestures { change, dragAmount ->
            change.consume()
            scenariosWidth += dragAmount.x.toDp()
          }
        },
      thickness = 8.dp
    )
    val scenarioStateHolderAndDepth = scenarioAndDepths.getOrNull(scenarioIndex)
    if (scenarioStateHolderAndDepth != null) {
      Column(Modifier.weight(3f)) {
        Scenario(
          scenarioStateHolder = scenarioStateHolderAndDepth.first,
          dependencyScenarioMenu = {
            selectableItem(
              selected = scenarioStateHolderAndDepth.first.dependencyScenarioStateHolderStateFlow.value == null,
              onClick = {
                scenarioStateHolderAndDepth.first.dependencyScenarioStateHolderStateFlow.value =
                  null
              },
              content = {
                Text("No dependency")
              }
            )
            appStateHolder.sortedScenariosAndDepthsStateFlow.value.map { it.first }
              .filter { senario -> senario != scenarioStateHolderAndDepth.first }
              .forEach { scenarioStateHolder: ArbigentScenarioStateHolder ->
                selectableItem(
                  selected = scenarioStateHolderAndDepth.first.dependencyScenarioStateHolderStateFlow.value == scenarioStateHolder,
                  onClick = {
                    scenarioStateHolderAndDepth.first.dependencyScenarioStateHolderStateFlow.value =
                      scenarioStateHolder
                  },
                  content = {
                    Text(
                      modifier = Modifier.testTag("dependency_scenario"),
                      text = scenarioStateHolder.goal
                    )
                  }
                )
              }
          },
          scenarioCountById = {
            appStateHolder.scenarioCountById(it)
          },
          onAddSubScenario = {
            appStateHolder.addSubScenario(parent = it)
          },
          onExecute = {
            appStateHolder.run(it)
          },
          onCancel = {
            appStateHolder.cancel()
            scenarioStateHolderAndDepth.first.cancel()
          },
          onRemove = {
            appStateHolder.removeScenario(it)
          }
        )
      }
    }
  }
}

@Composable
private fun LeftScenariosPanel(
  scenarioAndDepths: List<Pair<ArbigentScenarioStateHolder, Int>>,
  scenariosWidth: Dp,
  selectedScenarioIndex: Int,
  appStateHolder: ArbigentAppStateHolder
) {
  Box(
    Modifier
      .run {
        if (scenarioAndDepths.isEmpty()) {
          fillMaxSize()
        } else {
          width(scenariosWidth)
        }
      },
  ) {
    if (scenarioAndDepths.isNotEmpty()) {
      (0..scenarioAndDepths.maxOf { it.second }).forEach {
        Divider(
          orientation = Orientation.Vertical,
          modifier = Modifier.padding(start = 4.dp + 12.dp * it)
            .fillMaxHeight()
            .background(JewelTheme.colorPalette.purple(8))
            .width(2.dp)
        )
      }
    }
    val lazyColumnState = rememberLazyListState()
    LazyColumn(
      state = lazyColumnState,
      modifier = Modifier.fillMaxSize()
    ) {
      itemsIndexed(scenarioAndDepths) { index, (scenarioStateHolder, depth) ->
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
              if (index == selectedScenarioIndex) {
                JewelTheme.colorPalette.purple(9)
              } else {
                Color.White
              }
            )
            .clickable { appStateHolder.selectedScenarioIndex.value = index },
        ) {
          Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            val runningInfo by scenarioStateHolder.arbigentScenarioRunningInfo.collectAsState()
            Text(
              modifier = Modifier.weight(1f),
              text = "Goal: " + goal + "\n" + runningInfo?.toString().orEmpty()
            )
            val isAchieved by scenarioStateHolder.isAchieved.collectAsState()
            if (isAchieved) {
              PassedMark(
                Modifier.padding(8.dp)
              )
            }
            val isRunning by scenarioStateHolder.isRunning.collectAsState()
            if (isRunning) {
              CircularProgressIndicator(
                modifier = Modifier.padding(8.dp)
                  .size(32.dp)
                  .testTag("scenario_running")
              )
            }
          }
        }
      }
    }
    if (scenarioAndDepths.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(8.dp)) {
        DefaultButton(
          modifier = Modifier.align(Alignment.Center),
          onClick = {
            appStateHolder.addScenario()
          },
        ) {
          Text("Add a scenario")
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectFileControls(appStateHolder: ArbigentAppStateHolder) {
  FlowRow {
    IconActionButton(
      key = AllIconsKeys.Actions.MenuSaveall,
      onClick = {
        appStateHolder.projectDialogState.value = ProjectDialogState.SaveProjectContent
      },
      contentDescription = "Save",
      hint = Size(28)
    ) {
      Text("Save project content")
    }
    IconActionButton(
      key = AllIconsKeys.Actions.MenuOpen,
      onClick = {
        appStateHolder.projectDialogState.value = ProjectDialogState.LoadProjectContent
      },
      contentDescription = "Load",
      hint = Size(28)
    ) {
      Text("Load project content")
    }
    IconActionButton(
      key = AllIconsKeys.CodeWithMe.CwmShared,
      onClick = {
        appStateHolder.projectDialogState.value = ProjectDialogState.SaveProjectResult
      },
      contentDescription = "Save result",
      hint = Size(28)
    ) {
      Text("Save project result")
    }
    // Setting
    IconActionButton(
      key = AllIconsKeys.General.Settings,
      onClick = {
        appStateHolder.projectDialogState.value = ProjectDialogState.ShowProjectSettings
      },
      contentDescription = "Settings",
      hint = Size(28)
    ) {
      Text("Settings")
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ScenarioControls(appStateHolder: ArbigentAppStateHolder) {
  val coroutineScope = rememberCoroutineScope()
  FlowRow {
    val devicesStateHolder = appStateHolder.devicesStateHolder
    val deviceOs by devicesStateHolder.selectedDeviceOs.collectAsState()
    ComboBox(
      modifier = Modifier.width(100.dp).padding(end = 2.dp),
      labelText = deviceOs.name,
      maxPopupHeight = 150.dp,
    ) {
      Column {
        ArbigentDeviceOs.entries.forEach { item ->
          val isSelected = item == deviceOs
          val isItemHovered = false
          val isPreviewSelection = false
          SimpleListItem(
            text = item.name,
            state = ListItemState(isSelected, isItemHovered, isPreviewSelection),
            modifier = Modifier
              .clickable {
                devicesStateHolder.selectedDeviceOs.value = item
                devicesStateHolder.fetchDevices()
                devicesStateHolder.onSelectedDeviceChanged(null)
              },
            style = JewelTheme.simpleListItemStyle,
            contentDescription = item.name,
          )
        }
      }
    }

    val selectedDevice by devicesStateHolder.selectedDevice.collectAsState()
    val items = devicesStateHolder.devices.collectAsState().value.map { it.name }
    arbigentDebugLog("selectedDevice: $selectedDevice")
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
    ) {
      Text("Add scenario")
    }
    IconActionButton(
      key = AllIconsKeys.Actions.RunAll,
      onClick = {
        appStateHolder.runAll()
      },
      contentDescription = "Run all",
      hint = Size(28)
    ) {
      Text("Run all")
    }
    IconActionButton(
      key = AllIconsKeys.Actions.Rerun,
      onClick = {
        coroutineScope.launch {
          appStateHolder.runAllFailed()
        }
      },
      contentDescription = "Run all failed",
      hint = Size(28)
    ) {
      Text("Run all failed")
    }
  }
}

