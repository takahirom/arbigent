package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentDeviceOs
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
  appStateHolder: ArbigentAppStateHolder
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
      modifier = Modifier.padding(8.dp)
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

class AiSettingStateHolder {
  var aiSetting by mutableStateOf(Preference.aiSettingValue)

  fun onSelectedAiProviderSettingChanged(aiProviderSetting: AiProviderSetting) {
    aiSetting = aiSetting.copy(selectedId = aiProviderSetting.id)
    Preference.aiSettingValue = aiSetting
  }

  fun onAiProviderSettingChanged(aiProviderSetting: AiProviderSetting) {
    aiSetting = aiSetting.copy(aiSettings = aiSetting.aiSettings.map {
      if (it.id == aiProviderSetting.id) {
        aiProviderSetting
      } else {
        it
      }
    })
    Preference.aiSettingValue = aiSetting
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiProviderSetting(modifier: Modifier) {
  val aiSettingStateHolder = remember { AiSettingStateHolder() }
  GroupHeader("AI Provider")
  val aiSetting = aiSettingStateHolder.aiSetting
  FlowRow(modifier = modifier) {
    aiSetting.aiSettings.forEach { aiProviderSetting: AiProviderSetting ->
      RadioButtonRow(
        text = aiProviderSetting.name,
        selected = aiSetting.selectedId == aiProviderSetting.id,
        onClick = {
          aiSettingStateHolder.onSelectedAiProviderSettingChanged(aiProviderSetting)
        }
      )
    }
    val selectedAiProviderSetting: AiProviderSetting = aiSetting.aiSettings.firstOrNull() { it.id == aiSetting.selectedId }
      ?: aiSetting.aiSettings.first()
    if (selectedAiProviderSetting is AiProviderSetting.NormalAiProviderSetting) {
      NormalAiSetting(
        modifier = Modifier.padding(8.dp),
        aiProviderSetting = selectedAiProviderSetting,
        onAiProviderSettingChanged = {
          aiSettingStateHolder.onAiProviderSettingChanged(it)
        })
    } else if (selectedAiProviderSetting is AiProviderSetting.AzureOpenAi) {
      AzureOpenAiSetting(
        modifier = Modifier.padding(8.dp),
        aiProviderSetting = selectedAiProviderSetting as AiProviderSetting.AzureOpenAi,
        onAiProviderSettingChanged = {
          aiSettingStateHolder.onAiProviderSettingChanged(it)
        })
    } else if (selectedAiProviderSetting is AiProviderSetting.CustomOpenAiApiBasedAi) {
      CustomOpenAiApiBasedAiSetting(
        modifier = Modifier.padding(8.dp),
        aiProviderSetting = selectedAiProviderSetting,
        onAiProviderSettingChanged = {
          aiSettingStateHolder.onAiProviderSettingChanged(it)
        })
    }
  }
}

@Composable
private fun NormalAiSetting(
  aiProviderSetting: AiProviderSetting.NormalAiProviderSetting,
  onAiProviderSettingChanged: (AiProviderSetting.NormalAiProviderSetting) -> Unit,
  modifier: Modifier = Modifier,
) {
  val openAiApiKey =
    rememberSaveable(saver = TextFieldState.Saver, inputs = arrayOf(aiProviderSetting.id)) {
      TextFieldState(aiProviderSetting.apiKey, TextRange(aiProviderSetting.apiKey.length))
    }
  val updatedOnAiProviderSettingChanged by rememberUpdatedState(onAiProviderSettingChanged)
  LaunchedEffect(Unit) {
    snapshotFlow { openAiApiKey.text }
      .collect({
        updatedOnAiProviderSettingChanged(aiProviderSetting.updatedApiKey(apiKey = it.toString()))
      })
  }
  val modelName =
    rememberSaveable(saver = TextFieldState.Saver, inputs = arrayOf(aiProviderSetting.id)) {
      TextFieldState(aiProviderSetting.modelName, TextRange(aiProviderSetting.modelName.length))
    }
  LaunchedEffect(Unit) {
    snapshotFlow { modelName.text }
      .collect {
        updatedOnAiProviderSettingChanged(aiProviderSetting.updatedModelName(modelName = it.toString()))
      }
  }
  val providerName = aiProviderSetting.name
  Column(modifier = modifier) {
    if(aiProviderSetting.isApiKeyRequired) {
      Text("$providerName API Key(Saved in Keychain on Mac)")
      BasicSecureTextField(
        modifier = Modifier.padding(8.dp),
        decorator = {
          Box(
            Modifier.background(color = TextFieldStyle.light().colors.background)
              .padding(8.dp)
              .clip(RoundedCornerShape(4.dp))
          ) {
            if (openAiApiKey.text.isEmpty()) {
              Text("Enter $providerName API Key")
            }
            it()
          }
        },
        state = openAiApiKey,
      )
    }
    Text("$providerName Model Name")
    TextField(
      state = modelName,
      modifier = Modifier.padding(8.dp)
    )
  }
}

@Composable
private fun CustomOpenAiApiBasedAiSetting(
  aiProviderSetting: AiProviderSetting.CustomOpenAiApiBasedAi,
  onAiProviderSettingChanged: (AiProviderSetting.CustomOpenAiApiBasedAi) -> Unit,
  modifier: Modifier = Modifier,
) {
  val apiKey =
    rememberSaveable(saver = TextFieldState.Saver, inputs = arrayOf(aiProviderSetting.id)) {
      TextFieldState(aiProviderSetting.apiKey, TextRange(aiProviderSetting.apiKey.length))
    }
  val updatedOnAiProviderSettingChanged by rememberUpdatedState(onAiProviderSettingChanged)
  LaunchedEffect(Unit) {
    snapshotFlow { apiKey.text }
      .collect({
        updatedOnAiProviderSettingChanged(aiProviderSetting.updatedApiKey(apiKey = it.toString()))
      })
  }
  val modelName =
    rememberSaveable(saver = TextFieldState.Saver, inputs = arrayOf(aiProviderSetting.id)) {
      TextFieldState(aiProviderSetting.modelName, TextRange(aiProviderSetting.modelName.length))
    }
  LaunchedEffect(Unit) {
    snapshotFlow { modelName.text }
      .collect {
        updatedOnAiProviderSettingChanged(aiProviderSetting.updatedModelName(modelName = it.toString()))
      }
  }
  val endpoint =
    rememberSaveable(saver = TextFieldState.Saver, inputs = arrayOf(aiProviderSetting.id)) {
      TextFieldState(aiProviderSetting.baseUrl, TextRange(aiProviderSetting.baseUrl.length))
    }
  LaunchedEffect(Unit) {
    snapshotFlow { endpoint.text }
      .collect {
        updatedOnAiProviderSettingChanged(aiProviderSetting.updatedBaseUrl(baseUrl = it.toString()))
      }
  }
  val providerName = aiProviderSetting.name
  Column(modifier = modifier) {
    Text("$providerName API Key(Saved in Keychain on Mac)")
    BasicSecureTextField(
      modifier = Modifier.padding(8.dp),
      decorator = {
        Box(
          Modifier.background(color = TextFieldStyle.light().colors.background)
            .padding(8.dp)
            .clip(RoundedCornerShape(4.dp))
        ) {
          if (apiKey.text.isEmpty()) {
            Text("Enter $providerName API Key")
          }
          it()
        }
      },
      state = apiKey,
    )
    Text("$providerName Model Name")
    TextField(
      state = modelName,
      modifier = Modifier.padding(8.dp)
    )
    Text("$providerName Endpoint")
    TextField(
      state = endpoint,
      modifier = Modifier.padding(8.dp)
    )
  }
}

@Composable
private fun AzureOpenAiSetting(
  aiProviderSetting: AiProviderSetting.AzureOpenAi,
  onAiProviderSettingChanged: (AiProviderSetting.AzureOpenAi) -> Unit,
  modifier: Modifier = Modifier,
) {
  val apiKey =
    rememberSaveable(saver = TextFieldState.Saver, inputs = arrayOf(aiProviderSetting.id)) {
      TextFieldState(aiProviderSetting.apiKey, TextRange(aiProviderSetting.apiKey.length))
    }
  val updatedOnAiProviderSettingChanged by rememberUpdatedState(onAiProviderSettingChanged)
  LaunchedEffect(Unit) {
    snapshotFlow { apiKey.text }
      .collect({
        updatedOnAiProviderSettingChanged(aiProviderSetting.updatedApiKey(apiKey = it.toString()))
      })
  }
  val modelName =
    rememberSaveable(saver = TextFieldState.Saver, inputs = arrayOf(aiProviderSetting.id)) {
      TextFieldState(aiProviderSetting.modelName, TextRange(aiProviderSetting.modelName.length))
    }
  LaunchedEffect(Unit) {
    snapshotFlow { modelName.text }
      .collect {
        updatedOnAiProviderSettingChanged(aiProviderSetting.updatedModelName(modelName = it.toString()))
      }
  }
  val endpoint =
    rememberSaveable(saver = TextFieldState.Saver, inputs = arrayOf(aiProviderSetting.id)) {
      TextFieldState(aiProviderSetting.endpoint, TextRange(aiProviderSetting.endpoint.length))
    }
  LaunchedEffect(Unit) {
    snapshotFlow { endpoint.text }
      .collect {
        updatedOnAiProviderSettingChanged(aiProviderSetting.updatedEndpoint(endpoint = it.toString()))
      }
  }
  val apiVersion =
    rememberSaveable(saver = TextFieldState.Saver, inputs = arrayOf(aiProviderSetting.id)) {
      TextFieldState(aiProviderSetting.apiVersion, TextRange(aiProviderSetting.apiVersion.length))
    }
  LaunchedEffect(Unit) {
    snapshotFlow { apiVersion.text }
      .collect {
        updatedOnAiProviderSettingChanged(aiProviderSetting.updatedApiVersion(apiVersion = it.toString()))
      }
  }
  val providerName = aiProviderSetting.name
  Column(modifier = modifier) {
    Text("$providerName API Key(Saved in Keychain on Mac)")
    BasicSecureTextField(
      modifier = Modifier.padding(8.dp),
      decorator = {
        Box(
          Modifier.background(color = TextFieldStyle.light().colors.background)
            .padding(8.dp)
            .clip(RoundedCornerShape(4.dp))
        ) {
          if (apiKey.text.isEmpty()) {
            Text("Enter $providerName API Key")
          }
          it()
        }
      },
      state = apiKey,
    )
    Text("$providerName Model Name")
    TextField(
      state = modelName,
      modifier = Modifier.padding(8.dp)
    )
    Text("$providerName Endpoint")
    TextField(
      state = endpoint,
      modifier = Modifier.padding(8.dp)
    )
    Text("$providerName API Version")
    TextField(
      state = apiVersion,
      modifier = Modifier.padding(8.dp)
    )
  }
}