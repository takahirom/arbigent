package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import io.github.takahirom.arbigent.ArbigentAi
import io.github.takahirom.arbigent.ArbigentGlobalStatus
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.OpenAIAi
import io.github.takahirom.arbigent.printLogger
import io.github.takahirom.arbigent.ui.ArbigentAppStateHolder.ProjectDialogState
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.styling.TitleBarStyle
import java.awt.Window


@OptIn(ArbigentInternalApi::class)
fun main() {
  // Route logs to ArbigentGlobalStatus for BottomConsole display
  printLogger = { log -> ArbigentGlobalStatus.log(log) }
  
  plantErrorDialog()
  application {
    val appStateHolder = remember {
      ArbigentAppStateHolder(
        aiFactory = {
          val aiSetting = Preference.aiSettingValue
          val aiProviderSetting = aiSetting.aiSettings.first { it.id == aiSetting.selectedId }
          if (aiProviderSetting is AiProviderSetting.OpenAiBasedApiProviderSetting) {
            OpenAIAi(
              apiKey = aiProviderSetting.apiKey,
              modelName = aiProviderSetting.modelName,
              baseUrl = aiProviderSetting.baseUrl,
              loggingEnabled = aiSetting.loggingEnabled,
              jsonSchemaType = if(aiProviderSetting is AiProviderSetting.Gemini) {
                ArbigentAi.JsonSchemaType.GeminiOpenAICompatible
              } else {
                ArbigentAi.JsonSchemaType.OpenAI
              }
            )
          } else if (aiProviderSetting is AiProviderSetting.AzureOpenAi) {
            OpenAIAi(
              apiKey = aiProviderSetting.apiKey,
              modelName = aiProviderSetting.modelName,
              baseUrl = aiProviderSetting.endpoint,
              loggingEnabled = aiSetting.loggingEnabled,
              requestBuilderModifier = {
                parameter("api-version", aiProviderSetting.apiVersion)
                header("api-key", aiProviderSetting.apiKey)
              }
            )
          } else if (aiProviderSetting is AiProviderSetting.CustomOpenAiApiBasedAi) {
            OpenAIAi(
              apiKey = aiProviderSetting.apiKey,
              modelName = aiProviderSetting.modelName,
              loggingEnabled = aiSetting.loggingEnabled,
              baseUrl = aiProviderSetting.baseUrl,
            )
          } else {
            throw IllegalArgumentException("Unsupported aiProviderSetting: $aiProviderSetting")
          }
        },
      )
    }
    AppWindow(
      appStateHolder = appStateHolder,
      onExit = {
        if (!appStateHolder.deviceConnectionState.value.isConnected()) {
          exitApplication()
        }
        appStateHolder.cancel()
        appStateHolder.close()
      }
    )
  }
}


@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppWindow(
  appStateHolder: ArbigentAppStateHolder,
  onExit: () -> Unit,
) {
  var showCloseConfirmDialog by remember { mutableStateOf(false) }
  var pendingExitAfterSave by remember { mutableStateOf(false) }

  // Watch for save completion to exit if pending
  val projectDialogState by appStateHolder.projectDialogState.collectAsState()
  LaunchedEffect(projectDialogState) {
    if (pendingExitAfterSave && projectDialogState == ProjectDialogState.NotSelected) {
      pendingExitAfterSave = false
      onExit()
    }
  }

  AppTheme {
    if (showCloseConfirmDialog) {
      UnsavedChangesDialog(
        onSave = {
          showCloseConfirmDialog = false
          pendingExitAfterSave = true
          appStateHolder.projectDialogState.value = ProjectDialogState.SaveProjectContent
        },
        onDiscard = {
          showCloseConfirmDialog = false
          onExit()
        },
        onCancel = {
          showCloseConfirmDialog = false
        }
      )
    }

    DecoratedWindow(
      title = "App Test AI Agent",
      onCloseRequest = {
        if (appStateHolder.hasUnsavedChanges()) {
          showCloseConfirmDialog = true
        } else {
          onExit()
        }
      }) {
      CompositionLocalProvider(
        LocalWindowExceptionHandlerFactory provides object : WindowExceptionHandlerFactory {
          override fun exceptionHandler(window: Window): WindowExceptionHandler {
            return WindowExceptionHandler { throwable ->
              throwable.printStackTrace()
            }
          }
        }
      ) {
        val deviceConnectionState by appStateHolder.deviceConnectionState.collectAsState()
        val isDeviceConnected = deviceConnectionState.isConnected()
        TitleBar(
          style = TitleBarStyle
            .lightWithLightHeader(),
          gradientStartColor = JewelTheme.colorPalette.purple(8),
        ) {
          if (isDeviceConnected) {
            Box(Modifier.padding(8.dp).align(Alignment.Start)) {
              ProjectFileControls(appStateHolder)
            }
            Box(Modifier.padding(8.dp).align(Alignment.End)) {
              ScenarioControls(appStateHolder)
            }
          }
        }
        MenuBar {
          Menu("Scenarios") {
            if (!(isDeviceConnected)) {
              return@Menu
            }
            Item("Add") {
              appStateHolder.addScenario()
            }
            Item("Run all") {
              appStateHolder.runAll()
            }
            Item("Run all failed") {
              appStateHolder.runAllFailed()
            }
            Item("Save") {
              appStateHolder.projectDialogState.value = ProjectDialogState.SaveProjectContent
            }
            Item("Load") {
              appStateHolder.projectDialogState.value = ProjectDialogState.LoadProjectContent
            }
          }
        }
        App(
          appStateHolder = appStateHolder,
        )
      }
    }
  }
}
