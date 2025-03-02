package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import io.github.takahirom.arbigent.AiDecisionCacheStrategy
import io.github.takahirom.arbigent.BuildConfig
import io.github.takahirom.arbigent.UserPromptTemplate
import androidx.compose.foundation.ExperimentalFoundationApi
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import io.github.takahirom.arbigent.ArbigentAiOptions

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectSettingsDialog(appStateHolder: ArbigentAppStateHolder, onCloseRequest: () -> Unit) {
  TestCompatibleDialog(
    onCloseRequest = onCloseRequest,
    title = "Project Settings",
    resizable = false,
    content = {
      val scrollState = rememberScrollState()
      Column(
        modifier = Modifier
          .padding(16.dp)
          .verticalScroll(scrollState)
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
        TextArea(
          state = additionalSystemPrompt,
          modifier = Modifier
            .padding(8.dp)
            .height(60.dp)
            .testTag("additional_system_prompt"),
          placeholder = { Text("Additional System Prompt") },
          decorationBoxModifier = Modifier.padding(horizontal = 8.dp),
        )

        GroupHeader("AI Options")
        val aiOptions by appStateHolder.aiOptionsFlow.collectAsState()
        val currentOptions = aiOptions ?: ArbigentAiOptions()
        Row(
          modifier = Modifier.padding(horizontal = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Checkbox(
            checked = currentOptions.temperature != null,
            onCheckedChange = { enabled: Boolean ->
              appStateHolder.onAiOptionsChanged(
                currentOptions.copy(temperature = if (enabled) 0.7 else null)
              )
            }
          )
          Text("Use Temperature", modifier = Modifier.padding(start = 8.dp))
        }
        currentOptions.temperature?.let { temp ->
          Text("Temperature (0.0 - 1.0)", modifier = Modifier.padding(horizontal = 8.dp))
          Slider(
            value = temp.toFloat(),
            onValueChange = { newTemperature ->
              appStateHolder.onAiOptionsChanged(
                currentOptions.copy(temperature = newTemperature.toDouble())
              )
            },
            valueRange = 0f..1f,
            modifier = Modifier.padding(horizontal = 8.dp)
          )
          Text(
            text = String.format("%.2f", temp),
            modifier = Modifier.padding(horizontal = 8.dp)
          )
        }
        GroupHeader {
          Text("User Prompt Template")
          IconActionButton(
            key = AllIconsKeys.General.Information,
            onClick = {},
            contentDescription = "Prompt Template Info",
            hint = Size(16),
          ) {
            Text(
              text = "Available placeholders:\n" +
                "{{USER_INPUT_GOAL}} - The goal of the scenario\n" +
                "{{CURRENT_STEP}} - Current step number\n" +
                "{{MAX_STEP}} - Maximum steps allowed\n" +
                "{{STEPS}} - Steps completed so far"
            )
          }
        }
        var isValidTemplate by remember { mutableStateOf(true) }
        val userPromptTemplate: TextFieldState = remember {
          TextFieldState(
            appStateHolder.promptFlow.value.userPromptTemplate
          )
        }
        LaunchedEffect(Unit) {
          snapshotFlow { userPromptTemplate.text }.collect { text ->
            if (text.isNotBlank()) {
              isValidTemplate = try {
                UserPromptTemplate(text.toString())
                appStateHolder.onPromptChanged(
                  appStateHolder.promptFlow.value.copy(userPromptTemplate = text.toString())
                )
                true
              } catch (e: IllegalArgumentException) {
                false
              }
            }
          }
        }
        TextArea(
          state = userPromptTemplate,
          modifier = Modifier
            .padding(8.dp)
            .height(120.dp)
            .testTag("user_prompt_template"),
          placeholder = { Text("User Prompt Template") },
          decorationBoxModifier = Modifier.padding(horizontal = 8.dp),
        )
        if (!isValidTemplate) {
          Text(
            text = "Template must contain all required placeholders: {{USER_INPUT_GOAL}}, {{CURRENT_STEP}}, {{MAX_STEP}}, {{STEPS}}",
            color = Color.Red,
            modifier = Modifier.padding(4.dp)
          )
        }
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
            selectableItem(
              cacheStrategy.aiDecisionCacheStrategy is AiDecisionCacheStrategy.Disk,
              onClick = {
                appStateHolder.onCacheStrategyChanged(
                  appStateHolder.cacheStrategyFlow.value.copy(aiDecisionCacheStrategy = AiDecisionCacheStrategy.Disk())
                )
              }
            ) {
              Text("Disk")
            }
          }
        ) {
          Text(
            when (cacheStrategy.aiDecisionCacheStrategy) {
              is AiDecisionCacheStrategy.Disabled -> "Disabled"
              is AiDecisionCacheStrategy.InMemory -> "InMemory"
              is AiDecisionCacheStrategy.Disk -> "Disk"
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
