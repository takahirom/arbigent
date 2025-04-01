package io.github.takahirom.arbigent.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
  val workingDirectory: String = ""
)

class AppSettingsStateHolder {
  var appSettings by mutableStateOf(Preference.appSettingValue)
    private set

  fun onWorkingDirectoryChanged(workingDirectory: String) {
    appSettings = appSettings.copy(workingDirectory = workingDirectory)
    Preference.appSettingValue = appSettings
  }
}
