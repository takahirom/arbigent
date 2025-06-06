package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentDeviceOs
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size

@Composable
fun LauncherScreen(
  appStateHolder: ArbigentAppStateHolder,
  modifier: Modifier = Modifier
) {
  val devicesStateHolder = appStateHolder.devicesStateHolder
  val aiSettingStateHolder = remember { AiSettingStateHolder() }
  val aiSetting = aiSettingStateHolder.aiSetting
  Column(
    modifier
      .width(400.dp)
      .verticalScroll(rememberScrollState())
      .padding(8.dp),
    verticalArrangement = Arrangement.Center
  ) {
    GroupHeader("Device Type")
    Row(
      Modifier.padding(8.dp)
    ) {
      val deviceOs by devicesStateHolder.selectedDeviceOs.collectAsState()
      RadioButtonRow(
        text = "Android",
        selected = deviceOs.isAndroid(),
        onClick = { devicesStateHolder.selectedDeviceOs.value = ArbigentDeviceOs.Android }
      )
      RadioButtonRow(
        text = "iOS",
        selected = deviceOs.isIos(),
        onClick = { devicesStateHolder.selectedDeviceOs.value = ArbigentDeviceOs.Ios }
      )
      RadioButtonRow(
        text = "Web(Experimental)",
        selected = deviceOs.isWeb(),
        onClick = { devicesStateHolder.selectedDeviceOs.value = ArbigentDeviceOs.Web }
      )
    }
    val devices by devicesStateHolder.devices.collectAsState()
    Column(Modifier) {
      Row {
        GroupHeader(modifier = Modifier.weight(1F).align(Alignment.CenterVertically)) {
          Text("Devices")
          IconButton(
            modifier = Modifier.align(Alignment.CenterVertically),
            onClick = {
              devicesStateHolder.fetchDevices()
            }) {
            Icon(
              key = AllIconsKeys.Actions.Refresh,
              contentDescription = "Refresh",
              hint = Size(16)
            )
          }
        }

      }
      if (devices.isEmpty()) {
        Text(
          modifier = Modifier.padding(8.dp),
          text = "No devices found"
        )
      } else {
        devices.forEachIndexed { index, device ->
          val selectedDevice by devicesStateHolder.selectedDevice.collectAsState()
          RadioButtonRow(
            modifier = Modifier.padding(8.dp),
            text = device.name,
            selected = device == selectedDevice || (selectedDevice == null && index == 0),
            onClick = {
              devicesStateHolder.onSelectedDeviceChanged(device)
            }
          )
        }
      }
    }
    AiProviderSetting(
      modifier = Modifier.padding(8.dp),
      aiSettingStateHolder = aiSettingStateHolder,
    )
    AppSettingsSection(
      modifier = Modifier.padding(8.dp),
    )
    val deviceIsSelected = devices.isNotEmpty()
    if (!deviceIsSelected) {
      Text(
        text = "Error: No devices found. Please connect to a device.",
        color = androidx.compose.ui.graphics.Color.Red,
        modifier = Modifier.padding(8.dp).align(Alignment.CenterHorizontally)
      )
    }
    val isAiProviderSelected = aiSetting.selectedId != null
    if (!isAiProviderSelected) {
      Text(
        text = "Error: No AI provider selected. Please select an AI provider.",
        color = androidx.compose.ui.graphics.Color.Red,
        modifier = Modifier.padding(8.dp).align(Alignment.CenterHorizontally)
      )
    }
    DefaultButton(
      modifier = Modifier.align(Alignment.CenterHorizontally),
      onClick = {
        appStateHolder.onClickConnect(devicesStateHolder)
      },
      enabled = isAiProviderSelected && deviceIsSelected
    ) {
      Text("Connect to device")
    }
  }
}

class AiSettingStateHolder {
  var aiSetting by mutableStateOf(Preference.aiSettingValue)

  fun onSelectedAiProviderSettingChanged(aiProviderSetting: AiProviderSetting) {
    aiSetting = aiSetting.copy(selectedId = aiProviderSetting.id)
    Preference.aiSettingValue = aiSetting
  }

  fun onLoggingEnabledChanged(enabled: Boolean) {
    aiSetting = aiSetting.copy(loggingEnabled = enabled)
    Preference.aiSettingValue = aiSetting
  }

  fun addAiProvider(aiProviderSetting: AiProviderSetting) {
    // Check if ID already exists
    if (aiSetting.aiSettings.any { it.id == aiProviderSetting.id }) {
      return
    }
    aiSetting = aiSetting.copy(aiSettings = aiSetting.aiSettings + aiProviderSetting)
    Preference.aiSettingValue = aiSetting
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiProviderSetting(
  aiSettingStateHolder: AiSettingStateHolder,
  modifier: Modifier
) {
  Column {
    GroupHeader("AI Provider")
    val aiSetting = aiSettingStateHolder.aiSetting
    Row(
      modifier = Modifier.padding(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Checkbox(
        checked = aiSetting.loggingEnabled,
        onCheckedChange = { enabled ->
          aiSettingStateHolder.onLoggingEnabledChanged(enabled)
        }
      )
      Text("Enable Debug Logging")
    }
    FlowRow(modifier = modifier) {
      aiSetting.aiSettings.forEach { aiProviderSetting: AiProviderSetting ->
        RadioButtonRow(
          text = aiProviderSetting.name + "(${aiProviderSetting.id})",
          selected = aiSetting.selectedId == aiProviderSetting.id,
          onClick = {
            aiSettingStateHolder.onSelectedAiProviderSettingChanged(aiProviderSetting)
          }
        )
      }
    }
    var showingAddAiProviderDialog by remember { mutableStateOf(false) }
    OutlinedButton(
      modifier = Modifier.padding(8.dp),
      onClick = {
        showingAddAiProviderDialog = true
      },
    ) {
      Text("Add AI Provider")
    }
    if (showingAddAiProviderDialog) {
      AddAiProviderDialog(
        aiSettingStateHolder,
        onCloseRequest = {
          showingAddAiProviderDialog = false
        }
      )
    }
    // Remove selected model button
    OutlinedButton(
      modifier = Modifier.padding(8.dp),
      onClick = {
        val selectedId = aiSetting.selectedId
        if (selectedId != null) {
          aiSettingStateHolder.aiSetting =
            aiSetting.copy(
              selectedId = aiSetting.aiSettings.firstOrNull()?.id,
              aiSettings = aiSetting.aiSettings.filter { it.id != selectedId }
            )
          Preference.aiSettingValue = aiSetting
        }
      },
    ) {
      Text("Remove selected model")
    }
  }
}

@Composable
private fun AppSettingsSection(
  modifier: Modifier = Modifier,
) {
  val appSettingsStateHolder = remember { AppSettingsStateHolder() }
  val appSettings = appSettingsStateHolder.appSettings

  ExpandableSection("App Settings(Used for MCP)") {
    Column(modifier = modifier) {
      Text("Working Directory")
      val workingDirectory = rememberSaveable(saver = TextFieldState.Saver) {
        TextFieldState(appSettings.workingDirectory ?: "", TextRange(appSettings.workingDirectory?.length ?: 0))
      }
      LaunchedEffect(Unit) {
        snapshotFlow { workingDirectory.text }
          .collect {
            appSettingsStateHolder.onWorkingDirectoryChanged(it.toString())
          }
      }
      TextField(
        state = workingDirectory,
        modifier = Modifier.padding(8.dp)
      )

      Text("PATH")
      val path = rememberSaveable(saver = TextFieldState.Saver) {
        TextFieldState(appSettings.path ?: "", TextRange(appSettings.path?.length ?: 0))
      }
      LaunchedEffect(Unit) {
        snapshotFlow { path.text }
          .collect {
            appSettingsStateHolder.onPathChanged(it.toString())
          }
      }
      TextField(
        state = path,
        modifier = Modifier.padding(8.dp)
      )
    }
  }
}
