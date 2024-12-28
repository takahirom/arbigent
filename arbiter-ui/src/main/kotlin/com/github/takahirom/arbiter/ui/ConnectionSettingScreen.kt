package com.github.takahirom.arbiter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.arbiter.DeviceType
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size

@Composable
fun BoxScope.ConnectionSettingScreen(
  appStateHolder: AppStateHolder
) {
  val devicesStateHolder = remember { DevicesStateHolder() }
  Column(
    Modifier.align(Alignment.Center).width(400.dp).fillMaxHeight(),
    verticalArrangement = Arrangement.Center
  ) {
    GroupHeader("Device Type")
    Row {
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
      GroupHeader("Devices")
      IconButton(
        modifier = Modifier.padding(8.dp),
        onClick = {
          devicesStateHolder.fetchDevices()
        }) {
        Icon(
          key = AllIconsKeys.Actions.Refresh,
          contentDescription = "Refresh",
          hint = Size(28)
        )
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
    if (devices.isNotEmpty()) {
      OutlinedButton(
        modifier = Modifier, onClick = {
          appStateHolder.onClickConnect(devicesStateHolder)
        }) {
        Text("Connect to device")
      }
    }
  }
}