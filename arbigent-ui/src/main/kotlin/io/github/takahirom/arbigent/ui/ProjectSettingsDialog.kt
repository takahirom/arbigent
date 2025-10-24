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
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
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
import org.jetbrains.jewel.ui.component.RadioButtonRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.rememberDialogState
import io.github.takahirom.arbigent.ArbigentAiOptions
import io.github.takahirom.arbigent.ui.components.AiOptionsComponent
import org.jetbrains.jewel.ui.component.OutlinedButton

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectSettingsDialog(appStateHolder: ArbigentAppStateHolder, onCloseRequest: () -> Unit) {
  TestCompatibleDialog(
    onCloseRequest = onCloseRequest,
    title = "Project Settings",
    content = {
      val scrollState = rememberScrollState()
      Column {
        Column(
          modifier = Modifier
            .padding(16.dp)
            .weight(1F)
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

          GroupHeader("Default Device Form Factor")
          val defaultDeviceFormFactor by appStateHolder.defaultDeviceFormFactorFlow.collectAsState()

          // Display the current value
          val formFactorName = when {
            defaultDeviceFormFactor.isMobile() -> "Mobile"
            defaultDeviceFormFactor.isTv() -> "TV"
            else -> "Unspecified"
          }

          // Create a mutable state to track the selected option
          var selectedOption by remember { mutableStateOf(formFactorName) }

          Column(
            modifier = Modifier.padding(8.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically
            ) {
              RadioButtonRow(
                text = "Mobile",
                selected = selectedOption == "Mobile",
                onClick = {
                  selectedOption = "Mobile"
                  // Update the defaultDeviceFormFactor using the provided method
                  appStateHolder.onDefaultDeviceFormFactorChanged(ArbigentScenarioDeviceFormFactor.Mobile)
                }
              )
            }
            Row(
              verticalAlignment = Alignment.CenterVertically
            ) {
              RadioButtonRow(
                text = "TV",
                selected = selectedOption == "TV",
                onClick = {
                  selectedOption = "TV"
                  // Update the defaultDeviceFormFactor using the provided method
                  appStateHolder.onDefaultDeviceFormFactorChanged(ArbigentScenarioDeviceFormFactor.Tv)
                }
              )
            }
            Row(
              verticalAlignment = Alignment.CenterVertically
            ) {
              RadioButtonRow(
                text = "Unspecified",
                selected = selectedOption == "Unspecified",
                onClick = {
                  selectedOption = "Unspecified"
                  // Update the defaultDeviceFormFactor using the provided method
                  appStateHolder.onDefaultDeviceFormFactorChanged(ArbigentScenarioDeviceFormFactor.Unspecified)
                }
              )
            }
          }

          GroupHeader("Additional Actions")
          val additionalActions by appStateHolder.additionalActionsFlow.collectAsState()
          val currentActions = additionalActions ?: emptyList()

          Column(modifier = Modifier.padding(8.dp)) {
            AdditionalActionsConstants.AVAILABLE_ACTIONS.forEach { actionName ->
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp)
              ) {
                Checkbox(
                  checked = currentActions.contains(actionName),
                  onCheckedChange = { isChecked ->
                    val updatedActions = if (isChecked) {
                      currentActions + actionName
                    } else {
                      currentActions - actionName
                    }
                    appStateHolder.onAdditionalActionsChanged(
                      if (updatedActions.isEmpty()) null else updatedActions
                    )
                  },
                  modifier = Modifier.testTag("additional_action_$actionName")
                )
                Text(
                  text = actionName,
                  modifier = Modifier.padding(start = 8.dp)
                )
              }
            }
          }

          GroupHeader("AI Options")
          val aiOptions by appStateHolder.aiOptionsFlow.collectAsState()
          val currentOptions = aiOptions ?: ArbigentAiOptions()
          AiOptionsComponent(
            currentOptions = currentOptions,
            onOptionsChanged = appStateHolder::onAiOptionsChanged
          )
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

          GroupHeader("MCP JSON Configuration")
          val mcpJson: TextFieldState = remember {
            TextFieldState(
              appStateHolder.mcpJsonFlow.value
            )
          }
          LaunchedEffect(Unit) {
            snapshotFlow { mcpJson.text }.collect { text ->
              if (text.isNotBlank()) {
                appStateHolder.onMcpJsonChanged(text.toString())
              }
            }
          }
          TextArea(
            state = mcpJson,
            modifier = Modifier
              .padding(8.dp)
              .height(200.dp)
              .testTag("mcp_json"),
            placeholder = { Text("MCP JSON Configuration") },
            decorationBoxModifier = Modifier.padding(horizontal = 8.dp),
          )
        }
        // Close Button
        OutlinedButton(
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
  resizable: Boolean = true,
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
      state = rememberDialogState(
        width = 520.dp,
        height = 520.dp
      ),
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
