package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentAvailableDevice
import io.github.takahirom.arbigent.ArbigentDeviceOs
import io.github.takahirom.arbigent.arbigentDebugLog
import io.github.takahirom.arbigent.ui.ArbigentAppStateHolder.DeviceConnectionState
import io.github.takahirom.arbigent.ui.ArbigentAppStateHolder.ProjectDialogState
import io.github.takahirom.arbigent.ui.components.ComboBoxItem
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size

// Label for a device in the picker. Names alone can collide (a simulator and a phone, two phones,
// two simulators, or two Android devices with the same model name), so when a name is shared we
// append a disambiguating hint: a physical iPhone shows its masked UDID; anything else shows its OS
// plus an ordinal among the same-named devices, so two simulators (or two Android devices) never
// render identically without leaking a raw identifier. Selection itself is keyed by the device
// object, not this label.
private fun deviceItemLabel(device: ArbigentAvailableDevice, all: List<ArbigentAvailableDevice>): String {
  val sameName = all.filter { it.name == device.name }
  if (sameName.size <= 1) return device.name
  val hint = when (device) {
    is ArbigentAvailableDevice.IosReal -> "iOS device ${device.maskedUdid}"
    else -> {
      val ordinal = sameName.indexOfFirst { it.stableKey == device.stableKey } + 1
      "${device.deviceOs.name} #$ordinal"
    }
  }
  return "${device.name} ($hint)"
}

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
  } else if (projectDialogState is ProjectDialogState.ShowGenerateScenarioDialog) {
    ScenarioGenerationDialog(
      appStateHolder = appStateHolder,
      onCloseRequest = {
        appStateHolder.projectDialogState.value = ProjectDialogState.NotSelected
      },
      onGenerate = { scenariosToGenerate, appUiStructure, customInstruction, useExistingScenarios ->
        appStateHolder.onGenerateScenarios(
          scenariosToGenerate,
          appUiStructure,
          customInstruction,
          useExistingScenarios
        )
      }
    )
  } else if (projectDialogState is ProjectDialogState.ShowFixedScenariosDialog) {
    FixedScenariosDialog(
      appStateHolder = appStateHolder,
      onCloseRequest = {
        appStateHolder.projectDialogState.value = ProjectDialogState.NotSelected
      },
      onScenarioSelected = { scenarioId ->
        // Update the initialization method with the selected scenario ID
        appStateHolder.updateInitializationMethod(scenarioId)
        appStateHolder.projectDialogState.value = ProjectDialogState.NotSelected
      }
    )
  } else if (projectDialogState is ProjectDialogState.ShowReusableScenariosDialog) {
    ReusableScenariosDialog(
      appStateHolder = appStateHolder,
      onCloseRequest = {
        appStateHolder.projectDialogState.value = ProjectDialogState.NotSelected
      },
      onScenarioSelected = { reusableScenarioId ->
        appStateHolder.updateReusableStepSelection(reusableScenarioId)
        appStateHolder.projectDialogState.value = ProjectDialogState.NotSelected
      }
    )
  } else if (projectDialogState is ProjectDialogState.ShowScenarioGraphDialog) {
    ScenarioGraphDialog(
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
        .pointerHoverIcon(PointerIcon.Hand)
        .pointerInput(Unit) {
          detectDragGestures { change, dragAmount ->
            change.consume()
            scenariosWidth += dragAmount.x.toDp()
          }
        },
      thickness = 8.dp
    )
    val scenarioStateHolderAndDepth = scenarioAndDepths.getOrNull(scenarioIndex)
    val stepFeedbacks by appStateHolder.stepFeedbacks.collectAsState()
    if (scenarioStateHolderAndDepth != null) {
      Column(Modifier.weight(3f)) {
        Scenario(
          scenarioStateHolder = scenarioStateHolderAndDepth.first,
          stepFeedbacks = stepFeedbacks,
          onStepFeedback = {
            appStateHolder.onStepFeedback(it)
          },
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
                      text = scenarioStateHolder.goal.ifBlank { scenarioStateHolder.id }
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
          onDebugExecute = {
            appStateHolder.runDebug(it)
          },
          onCancel = {
            appStateHolder.cancel()
            scenarioStateHolderAndDepth.first.cancel()
          },
          onRemove = {
            appStateHolder.removeScenario(it)
          },
          onShowFixedScenariosDialog = { scenarioStateHolder, index ->
            appStateHolder.onShowFixedScenariosDialogWithContext(scenarioStateHolder, index)
          },
          getFixedScenarioById = { scenarioId ->
            appStateHolder.getFixedScenarioById(scenarioId)
          },
          mcpServerNames = appStateHolder.mcpServerNamesFlow.collectAsState().value,
          onShowReusableScenariosDialog = { scenarioStateHolder, index ->
            appStateHolder.onShowReusableScenariosDialogWithContext(scenarioStateHolder, index)
          },
          getReusableScenarioById = { reusableScenarioId ->
            appStateHolder.getReusableScenarioById(reusableScenarioId)
          },
          onMakeReusable = { scenarioStateHolder, newReusableId ->
            appStateHolder.makeScenarioReusable(scenarioStateHolder, newReusableId)
          }
        )
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
      contentDescription = "Project Settings",
      hint = Size(28),
      modifier = Modifier.testTag("settings_button")
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
          ComboBoxItem(
            text = item.name,
            isSelected = isSelected,
            onClick = {
              // The holder's OS collector re-fetches on this change; no explicit fetch needed (which
              // would only launch a second, redundant discovery).
              devicesStateHolder.selectedDeviceOs.value = item
              devicesStateHolder.onSelectedDeviceChanged(null)
            },
          )
        }
      }
    }

    val selectedDevice by devicesStateHolder.selectedDevice.collectAsState()
    val deviceList = devicesStateHolder.devices.collectAsState().value
    arbigentDebugLog("selectedDevice: $selectedDevice")
    ComboBox(
      modifier = Modifier.width(170.dp).padding(end = 2.dp),
      labelText = selectedDevice?.let { deviceItemLabel(it, deviceList) } ?: "Select device",
      maxPopupHeight = 150.dp,
    ) {
      Column {
        // Iterate device objects (not display names) so a simulator and a phone, or two phones, that
        // share a name each select the exact device the user clicked rather than the first name match.
        deviceList.forEach { device ->
          val isSelected = device == selectedDevice
          ComboBoxItem(
            text = deviceItemLabel(device, deviceList),
            isSelected = isSelected,
            onClick = {
              devicesStateHolder.onSelectedDeviceChanged(device)
              devicesStateHolder.selectedDevice.value?.let {
                appStateHolder.onClickConnect(devicesStateHolder)
              }
            },
          )
        }
      }
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
