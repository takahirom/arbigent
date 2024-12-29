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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.takahirom.arbiter.DeviceType
import org.jetbrains.jewel.intui.standalone.styling.light
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.TextFieldStyle
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size

@Composable
fun BoxScope.LauncherScreen(
  appStateHolder: AppStateHolder
) {
  val devicesStateHolder = remember { DevicesStateHolder() }
  Column(
    Modifier.align(Alignment.Center).width(400.dp).fillMaxHeight(),
    verticalArrangement = Arrangement.Center
  ) {
    GroupHeader("Device Type")
    Row(
      Modifier.padding(8.dp)
    ) {
      val deviceType by devicesStateHolder.deviceType.collectAsState()
      RadioButtonRow(
        text = "Android",
        selected = deviceType.isAndroid(),
        onClick = { devicesStateHolder.deviceType.value = DeviceType.Android }
      )
      RadioButtonRow(
        text = "iOS",
        selected = deviceType.isIOS(),
        onClick = { devicesStateHolder.deviceType.value = DeviceType.iOS }
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
            onClick = { devicesStateHolder.selectedDevice.value = device }
          )
        }
      }
    }
    val openAiApiKey = rememberTextFieldState(Preference.openAiApiKey)
    LaunchedEffect(Unit) {
      snapshotFlow { openAiApiKey.text }
        .collect {
          Preference.openAiApiKey = it.toString()
        }
    }
    GroupHeader("OpenAI API Key(Saved in Keychain on Mac)")
    BasicSecureTextField(
      modifier = Modifier.padding(8.dp),
      decorator = {
        Box(
          Modifier.background(color = TextFieldStyle.Companion.light().colors.background)
            .padding(8.dp)
            .clip(RoundedCornerShape(4.dp))
        ) {
          if(openAiApiKey.text.isEmpty()) {
            Text("Enter OpenAI API Key")
          }
          it()
        }
      },
      state = openAiApiKey,
    )
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