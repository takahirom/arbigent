package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.window.DialogWindow
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.TextField

@Composable
fun ProjectSettingsDialog(appStateHolder: ArbigentAppStateHolder, onCloseRequest: () -> Unit) {
  DialogWindow(
    onCloseRequest = onCloseRequest,
    title = "Project Settings",
    resizable = false,
    content = {
      Column {
        val additionalSystemPrompt: TextFieldState = remember {
          TextFieldState(
            appStateHolder.promptFlow.value.additionalSystemPrompts.firstOrNull() ?: ""
          )
        }
        LaunchedEffect(Unit) {
          snapshotFlow { additionalSystemPrompt.text }.collect {
            if(it.isNotBlank()) {
              appStateHolder.onPromptChanged(
                appStateHolder.promptFlow.value.copy(additionalSystemPrompts = listOf(it.toString()))
              )
            }
          }
        }
        GroupHeader("Additional System Prompt")
        TextField(
          state = additionalSystemPrompt,
        )
      }
    }
  )
}