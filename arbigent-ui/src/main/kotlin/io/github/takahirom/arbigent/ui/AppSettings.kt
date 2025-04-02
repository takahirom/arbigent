package io.github.takahirom.arbigent.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.takahirom.arbigent.ArbigentAppSettings
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
  override val workingDirectory: String? = null
) : ArbigentAppSettings

class AppSettingsStateHolder {
  var appSettings by mutableStateOf(Preference.appSettingValue)
    private set

  fun onWorkingDirectoryChanged(workingDirectory: String) {
    appSettings = appSettings.copy(workingDirectory = workingDirectory)
    Preference.appSettingValue = appSettings
  }
}
