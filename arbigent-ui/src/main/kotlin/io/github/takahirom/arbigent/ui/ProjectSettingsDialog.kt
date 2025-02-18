package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import io.github.takahirom.arbigent.AiDecisionCacheStrategy
import io.github.takahirom.arbigent.BuildConfig
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

@Composable
fun ProjectSettingsDialog(appStateHolder: ArbigentAppStateHolder, onCloseRequest: () -> Unit) {
  TestCompatibleDialog(
    onCloseRequest = onCloseRequest,
    title = "Project Settings",
    resizable = false,
    content = {
      Column(
        modifier = Modifier.padding(16.dp)
      ) {
        val additionalSystemPrompt: TextFieldState = remember {
          TextFieldState(
            appStateHolder.promptFlow.value.additionalSystemPrompts.firstOrNull() ?: ""
          )
        }
        LaunchedEffect(Unit) {
          snapshotFlow { additionalSystemPrompt.text }.collect {
            if (it.isNotBlank()) {
              appStateHolder.onPromptChanged(
                appStateHolder.promptFlow.value.copy(additionalSystemPrompts = listOf(it.toString()))
              )
            }
          }
        }
        GroupHeader("Version")
        Text(
          text = BuildConfig.VERSION_NAME,
          modifier = Modifier.padding(8.dp)
        )
        GroupHeader("Additional System Prompt")
        TextField(
          state = additionalSystemPrompt,
          modifier = Modifier.padding(8.dp)
        )
        GroupHeader("AI decision cache")
        val cacheStrategy by appStateHolder.cacheStrategyFlow.collectAsState()
        Dropdown(
          modifier = Modifier.padding(8.dp),
          menuContent = {
            selectableItem(
              cacheStrategy.aiDecisionCacheStrategy == AiDecisionCacheStrategy.Disabled,
              onClick = {
                appStateHolder.onCacheStrategyChanged(
                  appStateHolder.cacheStrategyFlow.value.copy(aiDecisionCacheStrategy = AiDecisionCacheStrategy.Disabled)
                )
              }
            ) {
              Text("Disabled")
            }
            selectableItem(
              cacheStrategy.aiDecisionCacheStrategy is AiDecisionCacheStrategy.InMemory,
              onClick = {
                appStateHolder.onCacheStrategyChanged(
                  appStateHolder.cacheStrategyFlow.value.copy(aiDecisionCacheStrategy = AiDecisionCacheStrategy.InMemory())
                )
              }
            ) {
              Text("InMemory")
            }
          }
        ) {
          Text(
            when (cacheStrategy.aiDecisionCacheStrategy) {
              is AiDecisionCacheStrategy.Disabled -> "Disabled"
              is AiDecisionCacheStrategy.InMemory -> "InMemory"
            }
          )
        }
        // Close Button
        ActionButton(
          onClick = onCloseRequest,
          modifier = Modifier.padding(8.dp)
        ) {
          Text("Close")
        }
      }
    }
  )
}

@Composable
fun TestCompatibleDialog(
  onCloseRequest: () -> Unit,
  title: String,
  resizable: Boolean = false,
  content: @Composable () -> Unit
) {
  val isUiTest = LocalIsUiTest.current
  if (isUiTest) {
    Box(
      Modifier.padding(16.dp)
        .background(color = Color.White)
        .fillMaxSize()
    ) {
      content()
    }
  } else {
    DialogWindow(
      onCloseRequest = onCloseRequest,
      title = title,
      resizable = resizable,
      content = {
        content()
      }
    )
  }
}

val LocalIsUiTest = staticCompositionLocalOf<Boolean> {
  false
}