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
import androidx.compose.foundation.ExperimentalFoundationApi
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.ActionButton
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import org.jetbrains.jewel.ui.component.Checkbox

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GenerateScenarioDialog(
  appStateHolder: ArbigentAppStateHolder, 
  onCloseRequest: () -> Unit,
  onGenerate: (scenariosToGenerate: String, appUiStructure: String, useExistingScenarios: Boolean) -> Unit
) {
  TestCompatibleDialog(
    onCloseRequest = onCloseRequest,
    title = "Generate Scenario",
    resizable = false,
    content = {
      val scrollState = rememberScrollState()

      // Define the TextFieldState variables at this level so they're accessible to the buttons
      val scenariosToGenerate: TextFieldState = remember {
        TextFieldState("")
      }
      val appUiStructure: TextFieldState = remember {
        TextFieldState(appStateHolder.appUiStructureFlow.value)
      }

      LaunchedEffect(Unit) {
        snapshotFlow { appUiStructure.text }.collect { text ->
          if (text.isNotBlank()) {
            appStateHolder.onAppUiStructureChanged(text.toString())
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
          GroupHeader("App UI structure")
          TextArea(
            state = appUiStructure,
            modifier = Modifier
              .padding(8.dp)
              .height(120.dp)
              .testTag("app_ui_structure"),
            placeholder = { Text("Enter app UI structure") },
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
          ActionButton(
            onClick = {
              onGenerate(
                scenariosToGenerate.text.toString(),
                appUiStructure.text.toString(),
                useExistingScenarios
              )
              onCloseRequest()
            },
            modifier = Modifier.padding(end = 8.dp)
          ) {
            Text("Generate")
          }

          ActionButton(
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
