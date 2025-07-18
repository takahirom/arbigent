package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import org.jetbrains.jewel.ui.component.Checkbox

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScenarioGenerationDialog(
  appStateHolder: ArbigentAppStateHolder,
  onCloseRequest: () -> Unit,
  onGenerate: (scenariosToGenerate: String, appUiStructure: String, customInstruction: String, useExistingScenarios: Boolean) -> Unit
) {
  TestCompatibleDialog(
    onCloseRequest = onCloseRequest,
    title = "Generate Scenario",
    content = {
      val scrollState = rememberScrollState()

      // Define the TextFieldState variables at this level so they're accessible to the buttons
      val scenariosToGenerate: TextFieldState = remember {
        TextFieldState("")
      }
      val appUiStructure: TextFieldState = remember {
        TextFieldState(appStateHolder.promptFlow.value.appUiStructure)
      }
      val customInstruction: TextFieldState = remember {
        TextFieldState(appStateHolder.promptFlow.value.scenarioGenerationCustomInstruction)
      }

      LaunchedEffect(Unit) {
        snapshotFlow { appUiStructure.text }.collect { text ->
          if (text.isNotBlank()) {
            appStateHolder.onAppUiStructureChanged(text.toString())
          }
        }
      }

      LaunchedEffect(Unit) {
        snapshotFlow { customInstruction.text }.collect { text ->
          if (text.isNotBlank()) {
            appStateHolder.onScenarioGenerationCustomInstructionChanged(text.toString())
          }
        }
      }

      // State for the checkbox
      var useExistingScenarios by remember { mutableStateOf(false) }

      Column {
        Column(
          modifier = Modifier
            .padding(16.dp)
            .weight(1F)
            .verticalScroll(scrollState)
        ) {
          // Scenarios to generate
          GroupHeader("Scenarios to generate")
          TextArea(
            state = scenariosToGenerate,
            modifier = Modifier
              .padding(8.dp)
              .height(120.dp)
              .testTag("scenarios_to_generate"),
            placeholder = { Text("Enter scenarios to generate") },
            decorationBoxModifier = Modifier.padding(horizontal = 8.dp),
          )

          // App UI structure
          GroupHeader("App UI structure (Optional)")
          TextArea(
            state = appUiStructure,
            modifier = Modifier
              .padding(8.dp)
              .height(120.dp)
              .testTag("app_ui_structure"),
            placeholder = { Text("Enter app UI structure (Optional)") },
            decorationBoxModifier = Modifier.padding(horizontal = 8.dp),
          )

          // Custom instruction
          GroupHeader("Custom instruction (Optional)")
          TextArea(
            state = customInstruction,
            modifier = Modifier
              .padding(8.dp)
              .height(120.dp)
              .testTag("custom_instruction"),
            placeholder = { Text("Enter custom instruction (Optional)") },
            decorationBoxModifier = Modifier.padding(horizontal = 8.dp),
          )
        }

        // Checkbox for using existing scenarios
        Row(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Checkbox(
            checked = useExistingScenarios,
            onCheckedChange = { useExistingScenarios = it },
            modifier = Modifier.testTag("use_existing_scenarios_checkbox")
          )
          Text(
            "Use existing scenarios as examples",
            modifier = Modifier.padding(start = 8.dp)
          )
        }

        // Buttons
        Row(
          modifier = Modifier.padding(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          DefaultButton(
            onClick = {
              val appUiStructureText = appUiStructure.text.toString()
              val customInstructionText = customInstruction.text.toString()
              onGenerate(
                scenariosToGenerate.text.toString(),
                appUiStructureText,
                customInstructionText,
                useExistingScenarios,
              )
              onCloseRequest()
            },
            modifier = Modifier.padding(end = 8.dp)
          ) {
            Text("Generate")
          }

          OutlinedButton(
            onClick = onCloseRequest,
            modifier = Modifier.padding(start = 8.dp)
          ) {
            Text("Close")
          }
        }
      }
    }
  )
}
