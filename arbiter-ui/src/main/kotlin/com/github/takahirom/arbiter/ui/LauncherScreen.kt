package com.github.takahirom.arbiter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.takahirom.arbiter.DeviceOs
import org.jetbrains.jewel.intui.standalone.styling.light
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.styling.TextFieldStyle
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size

@Composable
fun BoxScope.LauncherScreen(
  appStateHolder: ArbiterAppStateHolder
) {
  val devicesStateHolder = appStateHolder.devicesStateHolder
  Column(
    Modifier.align(Alignment.Center).width(400.dp).fillMaxHeight(),
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
        onClick = { devicesStateHolder.selectedDeviceOs.value = DeviceOs.Android }
      )
      RadioButtonRow(
        text = "iOS",
        selected = deviceOs.isIOS(),
        onClick = { devicesStateHolder.selectedDeviceOs.value = DeviceOs.iOS }
      )
    }
    val devices by devicesStateHolder.devices.collectAsState()
    Column(Modifier) {
      Row {
        GroupHeader("Devices", modifier = Modifier.weight(1F).align(Alignment.CenterVertically))
        IconButton(
          modifier = Modifier.padding(8.dp).align(Alignment.CenterVertically),
          onClick = {
            devicesStateHolder.fetchDevices()
          }) {
          Icon(
            key = AllIconsKeys.Actions.Refresh,
            contentDescription = "Refresh",
            hint = Size(28)
          )
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
    AiProviderSetting()
    if (devices.isNotEmpty()) {
      DefaultButton(
        modifier = Modifier.align(Alignment.CenterHorizontally), onClick = {
          appStateHolder.onClickConnect(devicesStateHolder)
        }) {
        Text("Connect to device")
      }
    }
  }
}

@Composable
private fun AiProviderSetting() {
  var aiProvider by remember { mutableStateOf(Preference.aiProviderEnum) }
  GroupHeader("AI Provider")
  RadioButtonRow(
    text = "OpenAI",
    selected = aiProvider == AiProvider.OpenAi,
    onClick = {
      aiProvider = AiProvider.OpenAi
    }
  )
  RadioButtonRow(
    text = "Gemini",
    selected = aiProvider == AiProvider.Gemini,
    onClick = {
      aiProvider = AiProvider.Gemini
    }
  )
  when (aiProvider) {
    AiProvider.OpenAi -> {
      OpenAiSetting()
    }

    AiProvider.Gemini -> {
      GeminiSetting()
    }
  }
}

@Composable
private fun OpenAiSetting() {
  val openAiApiKey = rememberTextFieldState(Preference.openAiApiKey)
  LaunchedEffect(Unit) {
    val collector: suspend (value: CharSequence) -> Unit = {
      Preference.openAiApiKey = it.toString()
    }
    snapshotFlow { openAiApiKey.text }
      .collect(collector)
  }
  val openAiModelName = rememberTextFieldState(Preference.openAiModelName)
  LaunchedEffect(Unit) {
    snapshotFlow { openAiModelName.text }
      .collect {
        Preference.openAiModelName = it.toString()
      }
  }
  Text("OpenAI API Key(Saved in Keychain on Mac)")
  BasicSecureTextField(
    modifier = Modifier.padding(8.dp),
    decorator = {
      Box(
        Modifier.background(color = TextFieldStyle.light().colors.background)
          .padding(8.dp)
          .clip(RoundedCornerShape(4.dp))
      ) {
        if (openAiApiKey.text.isEmpty()) {
          Text("Enter OpenAI API Key")
        }
        it()
      }
    },
    state = openAiApiKey,
  )
  Text("OpenAI Model Name")
  TextField(
    state = openAiModelName,
    modifier = Modifier.padding(8.dp)
  )
}

@Composable
private fun GeminiSetting() {
  val geminiApiKey = rememberTextFieldState(Preference.geminiApiKey)
  LaunchedEffect(Unit) {
    snapshotFlow { geminiApiKey.text }
      .collect {
        Preference.geminiApiKey = it.toString()
      }
  }
  val geminiModelName = rememberTextFieldState(Preference.geminiModelName)
  LaunchedEffect(Unit) {
    snapshotFlow { geminiModelName.text }
      .collect {
        Preference.geminiModelName = it.toString()
      }
  }
  Text("Gemini API Key(Saved in Keychain on Mac)")
  BasicSecureTextField(
    modifier = Modifier.padding(8.dp),
    decorator = {
      Box(
        Modifier.background(color = TextFieldStyle.light().colors.background)
          .padding(8.dp)
          .clip(RoundedCornerShape(4.dp))
      ) {
        if (geminiApiKey.text.isEmpty()) {
          Text("Enter Gemini API Key")
        }
        it()
      }
    },
    state = geminiApiKey,
  )
  Text("Gemini Model Name")
  TextField(
    state = geminiModelName,
    modifier = Modifier.padding(8.dp)
  )
}