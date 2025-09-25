package io.github.takahirom.arbigent.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.takahirom.arbigent.ArbigentAppSettings
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
  override val workingDirectory: String? = null,
  override val path: String? = null,
  override val variables: Map<String, String>? = null,
  override val mcpEnvironmentVariables: Map<String, String>? = null
) : ArbigentAppSettings

class AppSettingsStateHolder {
  var appSettings by mutableStateOf(Preference.appSettingValue)
    private set

  fun onWorkingDirectoryChanged(workingDirectory: String) {
    appSettings = appSettings.copy(workingDirectory = workingDirectory)
    Preference.appSettingValue = appSettings
  }

  fun onPathChanged(path: String) {
    appSettings = appSettings.copy(path = path)
    Preference.appSettingValue = appSettings
  }

  fun addVariable(key: String, value: String) {
    val currentVariables = appSettings.variables?.toMutableMap() ?: mutableMapOf()
    currentVariables[key] = value
    appSettings = appSettings.copy(variables = currentVariables)
    Preference.appSettingValue = appSettings
  }

  fun removeVariable(key: String) {
    val currentVariables = appSettings.variables?.toMutableMap() ?: return
    currentVariables.remove(key)
    appSettings = appSettings.copy(variables = if (currentVariables.isEmpty()) null else currentVariables)
    Preference.appSettingValue = appSettings
  }
  
  fun setVariables(variables: Map<String, String>?) {
    appSettings = appSettings.copy(variables = variables)
    Preference.appSettingValue = appSettings
  }
  
  fun setMcpEnvironmentVariables(mcpEnvironmentVariables: Map<String, String>?) {
    appSettings = appSettings.copy(mcpEnvironmentVariables = mcpEnvironmentVariables)
    Preference.appSettingValue = appSettings
  }
}
