package com.github.takahirom.arbiter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import com.github.takahirom.arbiter.OpenAIAi
import com.github.takahirom.arbiter.ui.AppStateHolder.FileSelectionState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.styling.TitleBarStyle
import java.awt.Window


fun main() = application {
  val appStateHolder = remember {
    AppStateHolder(
      aiFactory = { OpenAIAi(Preference.openAiApiKey) },
    )
  }
  AppWindow(
    appStateHolder = appStateHolder,
    onExit = {
      appStateHolder.close()
      exitApplication()
    }
  )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppWindow(
  appStateHolder: AppStateHolder,
  onExit: () -> Unit,
) {
  AppTheme {
    DecoratedWindow(
      title = "App Test AI Agent",
      onCloseRequest = {
        appStateHolder.close()
        onExit()
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
              ScenarioFileControls(appStateHolder)
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
              appStateHolder.fileSelectionState.value = FileSelectionState.Saving
            }
            Item("Load") {
              appStateHolder.fileSelectionState.value = FileSelectionState.Loading
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

