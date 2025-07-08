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
import kotlinx.coroutines.delay
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
      appSettingsStateHolder = appStateHolder.appSettingsStateHolder,
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
  appSettingsStateHolder: AppSettingsStateHolder,
) {
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
    "Use {{variable_name}} in goals to substitute values",
    style = androidx.compose.ui.text.TextStyle(
      fontSize = 12.sp,
      color = androidx.compose.ui.graphics.Color.Gray
    ),
    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
  )
  
  val variables = appSettings.variables ?: emptyMap()
  var numberOfSlots by rememberSaveable { mutableStateOf((variables.size + 1).coerceAtLeast(1)) }
  var clearTrigger by remember { mutableStateOf(0) }
  
  Column(modifier = Modifier.padding(horizontal = 8.dp)) {
    // Create slots for variables
    repeat(numberOfSlots) { index ->
      val variableKey = remember(variables) { 
        variables.keys.elementAtOrNull(index) ?: ""
      }
      val variableValue = remember(variables, variableKey) {
        if (variableKey.isNotEmpty()) variables[variableKey] ?: "" else ""
      }
      
      // Use key to force recreation when clearing
      key(index, clearTrigger) {
        var shouldClear by remember { mutableStateOf(false) }
        val keyState = rememberTextFieldState(if (shouldClear) "" else variableKey)
        val valueState = rememberTextFieldState(if (shouldClear) "" else variableValue)
        var keyError by remember { mutableStateOf<String?>(null) }
        var previousKey by remember { mutableStateOf(variableKey) }
      
      // Update variables when text changes
      LaunchedEffect(keyState.text, valueState.text) {
        delay(500) // Debounce
        
        val newKey = keyState.text.toString().trim()
        val newValue = valueState.text.toString().trim()
        
        // Validate key
        when {
          newKey.isNotEmpty() && !isValidVariableName(newKey) -> {
            keyError = "Invalid name"
          }
          newKey.isNotEmpty() && newKey != previousKey && variables.containsKey(newKey) -> {
            keyError = "Already exists"
          }
          else -> {
            keyError = null
            
            // Update variables
            if (previousKey.isNotEmpty() && previousKey != newKey) {
              // Key changed or removed
              appSettingsStateHolder.removeVariable(previousKey)
            }
            
            if (newKey.isNotEmpty() && newValue.isNotEmpty()) {
              // Add or update variable
              appSettingsStateHolder.addVariable(newKey, newValue)
              previousKey = newKey
            } else if (newKey.isEmpty() && previousKey.isNotEmpty()) {
              // Key cleared, remove variable
              appSettingsStateHolder.removeVariable(previousKey)
              previousKey = ""
            } else if (newKey.isNotEmpty() && newValue.isEmpty() && variables.containsKey(newKey)) {
              // Value cleared, remove variable
              appSettingsStateHolder.removeVariable(newKey)
              previousKey = ""
            }
          }
        }
      }
      
      Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.width(150.dp)) {
          TextField(
            state = keyState,
            placeholder = { Text("variable_name") },
            modifier = Modifier.fillMaxWidth()
          )
          keyError?.let { error ->
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
        TextField(
          state = valueState,
          placeholder = { Text("value") },
          modifier = Modifier.weight(1f)
        )
        
        // Delete button - only show if this slot has content or is the last empty slot
        val hasContent = keyState.text.toString().trim().isNotEmpty() || valueState.text.toString().trim().isNotEmpty()
        val isLastSlot = index == numberOfSlots - 1
        if (hasContent || (isLastSlot && numberOfSlots > 1)) {
          IconButton(
            onClick = {
              if (hasContent) {
                // Clear the fields by triggering state change
                val key = previousKey
                if (key.isNotEmpty()) {
                  appSettingsStateHolder.removeVariable(key)
                }
                shouldClear = true
                clearTrigger++
              }
              // Remove slot if it's the last one and there are multiple slots
              if (isLastSlot && numberOfSlots > 1) {
                numberOfSlots--
              }
            }
          ) {
            Icon(
              key = AllIconsKeys.General.Remove,
              contentDescription = "Remove",
              hint = Size(16)
            )
          }
        }
      }
      } // Close key block
    }
    
    // Add button
    OutlinedButton(
      onClick = { numberOfSlots++ },
      modifier = Modifier.padding(vertical = 8.dp)
    ) {
      Icon(
        key = AllIconsKeys.General.Add,
        contentDescription = "Add variable",
        hint = Size(16)
      )
      Text(" Add Variable", modifier = Modifier.padding(start = 4.dp))
    }
  }
}

/**
 * Validates variable names to ensure they only contain letters, numbers, and underscores.
 * This prevents issues with variable substitution in goals.
 */
private fun isValidVariableName(name: String): Boolean {
  return name.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))
}
