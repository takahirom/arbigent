package io.github.takahirom.arbiter.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbiter.DeviceOs
import io.github.takahirom.arbiter.arbiterDebugLog
import io.github.takahirom.arbiter.ui.ArbiterAppStateHolder.DeviceConnectionState
import io.github.takahirom.arbiter.ui.ArbiterAppStateHolder.FileSelectionState
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.ComboBox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.ListItemState
import org.jetbrains.jewel.ui.component.SimpleListItem
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.theme.simpleListItemStyle

@Composable
fun App(
  appStateHolder: ArbiterAppStateHolder
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
    val scenarioIndex by appStateHolder.selectedScenarioIndex.collectAsState()
    var scenariosWidth by remember { mutableStateOf(200.dp) }
    Row {
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
                .forEach { scenarioStateHolder: ArbiterScenarioStateHolder ->
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
            onAddSubScenario = {
              appStateHolder.addSubScenario(parent = it)
            },
            onExecute = {
              appStateHolder.run(it)
            },
            onCancel = {
              appStateHolder.close()
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
}

@Composable
private fun LeftScenariosPanel(
  scenarioAndDepths: List<Pair<ArbiterScenarioStateHolder, Int>>,
  scenariosWidth: Dp,
  selectedScenarioIndex: Int,
  appStateHolder: ArbiterAppStateHolder
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
            val runningInfo by scenarioStateHolder.arbiterScenarioRunningInfo.collectAsState()
            Text(
              modifier = Modifier.weight(1f),
              text = "Goal: " + goal + "\n" + runningInfo?.toString().orEmpty()
            )
            val isArchived by scenarioStateHolder.isArchived.collectAsState()
            if (isArchived) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScenarioFileControls(appStateHolder: ArbiterAppStateHolder) {
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ScenarioControls(appStateHolder: ArbiterAppStateHolder) {
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
        DeviceOs.values().forEach { item ->
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
    arbiterDebugLog("selectedDevice: $selectedDevice")
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

