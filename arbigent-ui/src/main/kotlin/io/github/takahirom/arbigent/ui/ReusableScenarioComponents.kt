package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.takahirom.arbigent.ArbigentScenarioContent
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size

/**
 * Editor for the call form: an ordered list of reusable-scenario calls.
 * Each row selects its target via the Reusable Scenarios dialog (Browse) and binds
 * the target's declared inputs via generated `with` fields.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReusableStepsEditor(
  scenarioStateHolder: ArbigentScenarioStateHolder,
  getReusableScenarioById: (String) -> ArbigentScenarioContent?,
  onBrowseReusableScenarios: (ArbigentScenarioStateHolder, Int) -> Unit,
) {
  val steps by scenarioStateHolder.reusableStepsStateFlow.collectAsState()
  Column(
    modifier = Modifier.fillMaxWidth().padding(4.dp).testTag("reusable_steps_editor")
  ) {
    GroupHeader("Steps")
    steps.forEachIndexed { index, step ->
      val target = getReusableScenarioById(step.uses)
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
      ) {
        Text("${index + 1}.", modifier = Modifier.padding(end = 4.dp))
        Text(
          text = step.uses.ifBlank { "Select scenario" },
          modifier = Modifier
            .width(200.dp)
            .background(JewelTheme.globalColors.panelBackground)
            .padding(8.dp)
            .clickable { onBrowseReusableScenarios(scenarioStateHolder, index) }
            .testTag("reusable_step_uses_$index")
        )
        IconActionButton(
          key = AllIconsKeys.General.OpenDisk,
          onClick = { onBrowseReusableScenarios(scenarioStateHolder, index) },
          contentDescription = "Browse reusable scenarios",
          hint = Size(16),
          modifier = Modifier.testTag("browse_reusable_scenarios_$index")
        ) {
          Text("Browse reusable scenarios")
        }
        IconActionButton(
          key = AllIconsKeys.General.Delete,
          onClick = { scenarioStateHolder.onRemoveReusableStep(index) },
          contentDescription = "Remove step",
          hint = Size(16),
        ) {
          Text("Remove step")
        }
      }
      if (step.uses.isNotBlank() && target == null) {
        Text(
          text = "'${step.uses}' is not defined in reusable scenarios",
          color = androidx.compose.ui.graphics.Color.Red,
          modifier = Modifier.padding(start = 16.dp)
        )
      }
      // `with` fields generated from the target's declared inputs.
      target?.inputs?.forEach { (name, input) ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(start = 16.dp, top = 2.dp)
        ) {
          Text(
            text = name + if (input.required && input.default == null) " *" else "",
            modifier = Modifier.width(120.dp)
          )
          TextField(
            value = step.withValues[name] ?: "",
            onValueChange = { newValue ->
              val newWith = step.withValues.toMutableMap()
              if (newValue.isEmpty()) newWith.remove(name) else newWith[name] = newValue
              scenarioStateHolder.onReusableStepChanged(index, step.copy(withValues = newWith))
            },
            placeholder = { Text(input.default ?: "") },
            modifier = Modifier.width(200.dp).testTag("reusable_step_with_${index}_$name")
          )
        }
      }
    }
    Row {
      OutlinedButton(
        onClick = { scenarioStateHolder.onAddReusableStep() },
        modifier = Modifier.padding(4.dp).testTag("add_reusable_step_button")
      ) {
        Text("+ Add step")
      }
    }
  }
}

/** Editor for a reusable scenario's input declarations (name / required / default). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReusableInputsEditor(
  scenarioStateHolder: ArbigentScenarioStateHolder,
) {
  val inputs by scenarioStateHolder.reusableInputsStateFlow.collectAsState()
  Column(modifier = Modifier.padding(4.dp).testTag("reusable_inputs_editor")) {
    inputs.forEachIndexed { index, (name, input) ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
      ) {
        TextField(
          value = name,
          onValueChange = { scenarioStateHolder.onReusableInputChanged(index, it, input) },
          placeholder = { Text("Input name") },
          modifier = Modifier.width(160.dp).testTag("reusable_input_name_$index")
        )
        Checkbox(
          checked = input.required,
          onCheckedChange = {
            scenarioStateHolder.onReusableInputChanged(index, name, input.copy(required = it))
          },
          modifier = Modifier.padding(start = 8.dp)
        )
        Text("required")
        TextField(
          value = input.default ?: "",
          onValueChange = {
            scenarioStateHolder.onReusableInputChanged(
              index, name, input.copy(default = it.ifEmpty { null })
            )
          },
          placeholder = { Text("Default value") },
          modifier = Modifier.width(160.dp).padding(start = 8.dp)
        )
        IconActionButton(
          key = AllIconsKeys.General.Delete,
          onClick = { scenarioStateHolder.onRemoveReusableInput(index) },
          contentDescription = "Remove input",
          hint = Size(16),
        ) {
          Text("Remove input")
        }
      }
    }
    OutlinedButton(
      onClick = { scenarioStateHolder.onAddReusableInput() },
      modifier = Modifier.padding(4.dp).testTag("add_reusable_input_button")
    ) {
      Text("+ Add input")
    }
  }
}

/**
 * Confirmation dialog for "Make this reusable". Previews what will move into the new
 * reusable scenario before anything is applied; the new id is editable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MakeReusableDialog(
  scenarioStateHolder: ArbigentScenarioStateHolder,
  onDismiss: () -> Unit,
  onConfirm: (newReusableId: String) -> Unit,
) {
  val idState = remember { TextFieldState(scenarioStateHolder.id + "-reusable") }
  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.background(JewelTheme.globalColors.panelBackground).padding(16.dp)
    ) {
      GroupHeader("Make this reusable")
      Text(
        text = "The goal, initialization methods and execution options of this scenario move into a new reusable scenario.\n" +
          "This scenario keeps its id, dependency and tags, and becomes a call to the new reusable scenario,\n" +
          "so scenarios depending on it are unaffected.\n" +
          "Parameterize the goal with {{inputs.*}} by editing the reusable scenario afterwards.",
        modifier = Modifier.padding(vertical = 8.dp)
      )
      Text("New reusable scenario id:")
      TextField(
        state = idState,
        modifier = Modifier.width(320.dp).padding(vertical = 4.dp).testTag("make_reusable_id"),
      )
      Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
      ) {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(end = 8.dp)) {
          Text("Cancel")
        }
        DefaultButton(
          onClick = { onConfirm(idState.text.toString()) },
          enabled = idState.text.isNotBlank(),
          modifier = Modifier.testTag("make_reusable_confirm")
        ) {
          Text("Make reusable")
        }
      }
    }
  }
}
