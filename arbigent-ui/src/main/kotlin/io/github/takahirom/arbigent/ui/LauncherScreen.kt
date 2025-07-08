package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

  ExpandableSection("App Settings") {
    Column(modifier = modifier) {
      Text("Working Directory (Used for MCP)")
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

      Text("PATH (Used for MCP)")
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
      
      Spacer(modifier = Modifier.height(16.dp))
      
      VariablesSection(appSettingsStateHolder)
    }
  }
}

@Composable
private fun VariablesSection(
  appSettingsStateHolder: AppSettingsStateHolder,
  modifier: Modifier = Modifier
) {
  val appSettings = appSettingsStateHolder.appSettings
  
  Text("Variables (for goal substitution)")
  Text(
    "Add key-value pairs to replace {{variable}} in goals",
    style = androidx.compose.ui.text.TextStyle(
      fontSize = 12.sp,
      color = androidx.compose.ui.graphics.Color.Gray
    ),
    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
  )
  Text(
    "Variable names must contain only letters, numbers, and underscores",
    style = androidx.compose.ui.text.TextStyle(
      fontSize = 11.sp,
      color = androidx.compose.ui.graphics.Color.Gray
    ),
    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
  )
  
  val variables = appSettings.variables ?: emptyMap()
  val maxVariables = 20
  
  // Display existing variables
  variables.forEach { (key, value) ->
    var isEditing by remember { mutableStateOf(false) }
    var editKey by remember(key) { mutableStateOf(key) }
    var editValue by remember(value) { mutableStateOf(value) }
    
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (isEditing) {
        // Edit mode
        val keyState = rememberSaveable(key, saver = TextFieldState.Saver) {
          TextFieldState(editKey, TextRange(editKey.length))
        }
        val valueState = rememberSaveable(value, saver = TextFieldState.Saver) {
          TextFieldState(editValue, TextRange(editValue.length))
        }
        
        LaunchedEffect(Unit) {
          snapshotFlow { keyState.text }
            .collect { editKey = it.toString() }
        }
        LaunchedEffect(Unit) {
          snapshotFlow { valueState.text }
            .collect { editValue = it.toString() }
        }
        
        TextField(
          state = keyState,
          modifier = Modifier.width(120.dp),
          placeholder = { Text("Variable name") }
        )
        Text(" = ", modifier = Modifier.padding(horizontal = 4.dp))
        TextField(
          state = valueState,
          modifier = Modifier.weight(1f),
          placeholder = { Text("Value") }
        )
        // Save button
        IconButton(
          onClick = {
            val trimmedKey = editKey.trim()
            val trimmedValue = editValue.trim()
            if (isValidVariableName(trimmedKey) && trimmedValue.isNotBlank()) {
              appSettingsStateHolder.updateVariable(key, trimmedKey, trimmedValue)
              isEditing = false
            }
          },
          enabled = isValidVariableName(editKey.trim()) && editValue.trim().isNotBlank()
        ) {
          Icon(
            key = AllIconsKeys.Actions.Execute,
            contentDescription = "Save",
            hint = Size(16)
          )
        }
        // Cancel button
        IconButton(
          onClick = {
            editKey = key
            editValue = value
            isEditing = false
          }
        ) {
          Icon(
            key = AllIconsKeys.Actions.Cancel,
            contentDescription = "Cancel",
            hint = Size(16)
          )
        }
      } else {
        // Display mode
        Text("{{$key}} = ", modifier = Modifier.width(150.dp))
        Text(value, modifier = Modifier.weight(1f))
        // Edit button
        IconButton(
          onClick = { isEditing = true }
        ) {
          Icon(
            key = AllIconsKeys.Actions.Edit,
            contentDescription = "Edit variable",
            hint = Size(16)
          )
        }
        // Remove button
        IconButton(
          onClick = { appSettingsStateHolder.removeVariable(key) }
        ) {
          Icon(
            key = AllIconsKeys.General.Remove,
            contentDescription = "Remove variable",
            hint = Size(16)
          )
        }
      }
    }
  }
  
  // Add new variable (only show if under limit)
  if (variables.size < maxVariables) {
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var keyValidationError by remember { mutableStateOf<String?>(null) }
    
    // Validate key as it changes
    LaunchedEffect(newKey) {
      keyValidationError = when {
        newKey.isEmpty() -> null
        !isValidVariableName(newKey) -> 
          "Only letters, numbers, and underscores allowed"
        variables.containsKey(newKey) -> 
          "Variable already exists"
        else -> null
      }
    }
    
    Column {
      Row(
        modifier = Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.width(150.dp)) {
          val newKeyState = rememberTextFieldState(newKey)
          LaunchedEffect(Unit) {
            snapshotFlow { newKeyState.text }
              .collect { newKey = it.toString() }
          }
          TextField(
            state = newKeyState,
            placeholder = { Text("Variable name") }
          )
          keyValidationError?.let { error ->
            Text(
              text = error,
              style = androidx.compose.ui.text.TextStyle(
                fontSize = 10.sp,
                color = androidx.compose.ui.graphics.Color.Red
              ),
              modifier = Modifier.padding(top = 2.dp)
            )
          }
        }
        Text(" = ", modifier = Modifier.padding(horizontal = 8.dp))
        val newValueState = rememberTextFieldState(newValue)
        LaunchedEffect(Unit) {
          snapshotFlow { newValueState.text }
            .collect { newValue = it.toString() }
        }
        TextField(
          state = newValueState,
          modifier = Modifier.weight(1f),
          placeholder = { Text("Value") }
        )
        IconButton(
          onClick = {
            val trimmedKey = newKey.trim()
            val trimmedValue = newValue.trim()
            if (isValidVariableName(trimmedKey) && 
                trimmedValue.isNotBlank() && 
                !variables.containsKey(trimmedKey)) {
              appSettingsStateHolder.addVariable(trimmedKey, trimmedValue)
              newKey = ""
              newValue = ""
            }
          },
          enabled = keyValidationError == null && 
                   newKey.isNotEmpty() && 
                   newValue.isNotEmpty()
        ) {
          Icon(
            key = AllIconsKeys.General.Add,
            contentDescription = "Add variable",
            hint = Size(16)
          )
        }
      }
    }
  } else {
    Text(
      "Maximum number of variables reached ($maxVariables)",
      style = androidx.compose.ui.text.TextStyle(
        fontSize = 11.sp,
        color = androidx.compose.ui.graphics.Color.Gray
      ),
      modifier = Modifier.padding(8.dp)
    )
  }
}

/**
 * Validates variable names to ensure they only contain letters, numbers, and underscores.
 * This prevents issues with variable substitution in goals.
 */
private fun isValidVariableName(name: String): Boolean {
  return name.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))
}
