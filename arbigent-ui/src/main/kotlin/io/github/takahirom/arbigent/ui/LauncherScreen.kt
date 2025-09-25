package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
    MCPSettingsSection(
      modifier = Modifier.padding(8.dp),
      appStateHolder = appStateHolder,
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

  fun removeAiProvider(providerId: String) {
    val newSettings = aiSetting.aiSettings.filter { it.id != providerId }
    val newSelectedId = if (aiSetting.selectedId == providerId) {
      newSettings.firstOrNull()?.id
    } else {
      aiSetting.selectedId
    }
    aiSetting = aiSetting.copy(
      selectedId = newSelectedId,
      aiSettings = newSettings
    )
    Preference.aiSettingValue = aiSetting
  }

  fun updateAiProvider(aiProviderSetting: AiProviderSetting) {
    val newSettings = aiSetting.aiSettings.map { existing ->
      if (existing.id == aiProviderSetting.id) aiProviderSetting else existing
    }
    aiSetting = aiSetting.copy(aiSettings = newSettings)
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
    FlowRow(
      modifier = modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      aiSetting.aiSettings.forEach { aiProviderSetting: AiProviderSetting ->
        var showingEditDialog by remember { mutableStateOf(false) }
        
        Row(
          modifier = Modifier.wrapContentWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButtonRow(
            text = aiProviderSetting.name + "(${aiProviderSetting.id})",
            selected = aiSetting.selectedId == aiProviderSetting.id,
            onClick = {
              aiSettingStateHolder.onSelectedAiProviderSettingChanged(aiProviderSetting)
            }
          )
          
          IconButton(
            onClick = { showingEditDialog = true },
            modifier = Modifier.size(20.dp).padding(start = 4.dp)
          ) {
            Icon(
              key = AllIconsKeys.Actions.Edit,
              contentDescription = "Edit",
              hint = Size(12)
            )
          }
          
          IconButton(
            onClick = { aiSettingStateHolder.removeAiProvider(aiProviderSetting.id) },
            modifier = Modifier.size(20.dp)
          ) {
            Icon(
              key = AllIconsKeys.General.Remove,
              contentDescription = "Remove",
              hint = Size(12)
            )
          }
          
          if (showingEditDialog) {
            AddAiProviderDialog(
              aiSettingStateHolder,
              editingProvider = aiProviderSetting,
              onCloseRequest = {
                showingEditDialog = false
              }
            )
          }
        }
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
  }
}

@Composable
private fun AppSettingsSection(
  modifier: Modifier = Modifier,
  appSettingsStateHolder: AppSettingsStateHolder,
) {
  ExpandableSection("App Settings") {
    Column(modifier = modifier) {
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
  
  // Keep simplified approach but use value/onValueChange pattern for now
  val variables = appSettings.variables ?: emptyMap()
  val variablesList = remember(variables) {
    mutableStateListOf<MutableState<Pair<String, String>>>().apply {
      variables.forEach { (k, v) -> add(mutableStateOf(k to v)) }
      if (isEmpty()) add(mutableStateOf("" to ""))
    }
  }
  
  Column(modifier = Modifier.padding(horizontal = 8.dp)) {
    variablesList.forEachIndexed { index, variableState ->
      val keyState = rememberTextFieldState()
      val valueState = rememberTextFieldState()
      var keyError by remember { mutableStateOf<String?>(null) }
      
      // Initialize states with current values
      LaunchedEffect(variableState.value) {
        keyState.setTextAndPlaceCursorAtEnd(variableState.value.first)
        valueState.setTextAndPlaceCursorAtEnd(variableState.value.second)
      }
      
      LaunchedEffect(keyState.text, valueState.text) {
        delay(300)
        
        val newKey = keyState.text.toString().trim()
        val newValue = valueState.text.toString().trim()
        
        // Validate
        val existingKeys = variablesList
          .mapIndexedNotNull { i, state -> 
            if (i != index) state.value.first else null 
          }
          .filter { it.isNotEmpty() }
        
        keyError = when {
          newKey.isNotEmpty() && !isValidVariableName(newKey) -> "Invalid name"
          newKey.isNotEmpty() && existingKeys.contains(newKey) -> "Already exists"
          else -> null
        }
        
        if (keyError == null) {
          variableState.value = newKey to newValue
          
          // Update app settings
          val newVariables = variablesList
            .map { it.value }
            .filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() }
            .toMap()
          
          appSettingsStateHolder.setVariables(newVariables.ifEmpty { null })
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
        
        IconButton(
          onClick = {
            variablesList.removeAt(index)
            if (variablesList.isEmpty()) {
              variablesList.add(mutableStateOf("" to ""))
            }
            // Update app settings
            val newVariables = variablesList
              .map { it.value }
              .filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() }
              .toMap()
            appSettingsStateHolder.setVariables(newVariables.ifEmpty { null })
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
    
    // Add button
    OutlinedButton(
      onClick = { variablesList.add(mutableStateOf("" to "")) },
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

@Composable
private fun MCPSettingsSection(
  modifier: Modifier = Modifier,
  appStateHolder: ArbigentAppStateHolder,
) {
  val appSettingsStateHolder = appStateHolder.appSettingsStateHolder
  val appSettings = appSettingsStateHolder.appSettings

  ExpandableSection("MCP Settings") {
    Column(modifier = modifier) {
      Text("MCP Working Directory")
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

      Text("MCP PATH Environment Variable")
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
      
      MCPEnvironmentVariablesSection(appSettingsStateHolder)
    }
  }
}

@Composable
private fun MCPEnvironmentVariablesSection(
  appSettingsStateHolder: AppSettingsStateHolder,
  modifier: Modifier = Modifier
) {
  val appSettings = appSettingsStateHolder.appSettings
  
  Text("MCP Tool Environment Variables")
  Text(
    "Define environment variables for MCP tool processes",
    style = androidx.compose.ui.text.TextStyle(
      fontSize = 12.sp,
      color = androidx.compose.ui.graphics.Color.Gray
    ),
    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
  )
  
  val mcpVariables = appSettings.mcpEnvironmentVariables ?: emptyMap()
  val mcpVariablesList = remember(mcpVariables) {
    mutableStateListOf<MutableState<Pair<String, String>>>().apply {
      mcpVariables.forEach { (k, v) -> add(mutableStateOf(k to v)) }
      if (isEmpty()) add(mutableStateOf("" to ""))
    }
  }
  
  // Helper function to update MCP environment variables
  fun updateMcpEnvironmentVariables() {
    val newMcpVariables = mcpVariablesList
      .map { it.value }
      .filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() }
      .toMap()
    appSettingsStateHolder.setMcpEnvironmentVariables(newMcpVariables.ifEmpty { null })
  }
  
  Column(modifier = Modifier.padding(horizontal = 8.dp)) {
    mcpVariablesList.forEachIndexed { index, variableState ->
      val keyState = rememberTextFieldState()
      val valueState = rememberTextFieldState()
      var keyError by remember { mutableStateOf<String?>(null) }
      
      // Initialize states with current values
      LaunchedEffect(variableState.value) {
        keyState.setTextAndPlaceCursorAtEnd(variableState.value.first)
        valueState.setTextAndPlaceCursorAtEnd(variableState.value.second)
      }
      
      LaunchedEffect(keyState.text, valueState.text) {
        delay(300)
        
        val newKey = keyState.text.toString().trim()
        val newValue = valueState.text.toString().trim()
        
        // Validate
        val existingKeys = mcpVariablesList
          .mapIndexedNotNull { i, state -> 
            if (i != index) state.value.first else null 
          }
          .filter { it.isNotEmpty() }
        
        keyError = when {
          newKey.isNotEmpty() && !isValidEnvironmentVariableName(newKey) -> "Invalid name"
          newKey.isNotEmpty() && existingKeys.contains(newKey) -> "Already exists"
          else -> null
        }
        
        if (keyError == null) {
          variableState.value = newKey to newValue
          updateMcpEnvironmentVariables()
        }
      }
      
      Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.width(150.dp)) {
          TextField(
            state = keyState,
            placeholder = { Text("VARIABLE_NAME") },
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
        
        IconButton(
          onClick = {
            mcpVariablesList.removeAt(index)
            if (mcpVariablesList.isEmpty()) {
              mcpVariablesList.add(mutableStateOf("" to ""))
            }
            updateMcpEnvironmentVariables()
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
    
    // Add button
    OutlinedButton(
      onClick = { mcpVariablesList.add(mutableStateOf("" to "")) },
      modifier = Modifier.padding(vertical = 8.dp)
    ) {
      Icon(
        key = AllIconsKeys.General.Add,
        contentDescription = "Add MCP environment variable",
        hint = Size(16)
      )
      Text(" Add Environment Variable", modifier = Modifier.padding(start = 4.dp))
    }
  }
}

/**
 * Validates environment variable names.
 * Environment variables typically allow uppercase letters, numbers, and underscores.
 */
internal fun isValidEnvironmentVariableName(name: String): Boolean {
  return name.matches(Regex("^[A-Z_][A-Z0-9_]*$"))
}
