package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.takahirom.arbigent.ArbigentScenarioContent
import io.github.takahirom.arbigent.ArbigentTagManager
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size

/**
 * Library dialog for reusable scenarios: list + add/edit/delete + selection.
 * Structural sibling of [FixedScenariosDialog].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReusableScenariosDialog(
  appStateHolder: ArbigentAppStateHolder,
  onCloseRequest: () -> Unit,
  onScenarioSelected: (String) -> Unit,
) {
  val reusableScenarios by appStateHolder.reusableScenariosFlow.collectAsState()
  var editingScenario by remember { mutableStateOf<ArbigentScenarioContent?>(null) }
  var showEditor by remember { mutableStateOf(false) }
  var selectedScenarioId by remember { mutableStateOf<String?>(null) }

  TestCompatibleDialog(
    onCloseRequest = onCloseRequest,
    title = "Reusable Scenarios",
    content = {
      Column(
        modifier = Modifier.padding(16.dp).fillMaxWidth().fillMaxHeight(0.8f)
      ) {
        LazyColumn(
          modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
          items(reusableScenarios) { scenario ->
            Row(
              modifier = Modifier.fillMaxWidth().padding(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              RadioButtonRow(
                text = "",
                selected = scenario.id == selectedScenarioId,
                onClick = { selectedScenarioId = scenario.id },
                modifier = Modifier.testTag("select_reusable_${scenario.id}")
              )
              Column(
                modifier = Modifier.weight(1f).padding(start = 8.dp)
              ) {
                Text(
                  text = scenario.id,
                  modifier = Modifier.testTag("reusable_id_${scenario.id}")
                )
                Text(
                  text = if (scenario.isCallForm()) {
                    "Steps: " + scenario.callSteps().joinToString(" -> ") { it.uses }
                  } else {
                    scenario.goal
                  },
                )
              }
              IconActionButton(
                key = AllIconsKeys.Actions.Edit,
                onClick = {
                  editingScenario = scenario
                  showEditor = true
                },
                contentDescription = "Edit reusable scenario",
                hint = Size(16),
                modifier = Modifier.testTag("edit_reusable_${scenario.id}")
              )
              val references = appStateHolder.reusableScenarioReferences(scenario.id)
              IconActionButton(
                key = AllIconsKeys.General.Delete,
                onClick = {
                  if (references.isEmpty()) {
                    appStateHolder.removeReusableScenario(scenario.id)
                  }
                },
                enabled = references.isEmpty(),
                contentDescription = "Delete reusable scenario",
                hint = Size(16),
                modifier = Modifier.testTag("delete_reusable_${scenario.id}")
              ) {
                if (references.isNotEmpty()) {
                  Text("Referenced by: ${references.joinToString(", ")}")
                }
              }
            }
            Divider(orientation = Orientation.Horizontal)
          }
        }

        Row(
          modifier = Modifier.padding(8.dp).fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          OutlinedButton(
            onClick = {
              editingScenario = null
              showEditor = true
            },
            modifier = Modifier.testTag("add_reusable_button")
          ) {
            Text("Add Reusable Scenario")
          }

          Row {
            DefaultButton(
              onClick = {
                selectedScenarioId?.let { onScenarioSelected(it) }
                onCloseRequest()
              },
              enabled = selectedScenarioId != null,
              modifier = Modifier.padding(end = 8.dp).testTag("select_reusable_button")
            ) {
              Text("Select")
            }
            OutlinedButton(
              onClick = onCloseRequest,
              modifier = Modifier.testTag("close_reusable_dialog_button")
            ) {
              Text("Close")
            }
          }
        }
      }

      if (showEditor) {
        ReusableScenarioEditorDialog(
          appStateHolder = appStateHolder,
          existingScenario = editingScenario,
          onCloseRequest = {
            showEditor = false
            editingScenario = null
          },
          onSave = { content ->
            val original = editingScenario
            if (original == null) {
              appStateHolder.addReusableScenario(content)
            } else {
              appStateHolder.updateReusableScenario(content, originalId = original.id)
            }
            showEditor = false
            editingScenario = null
          }
        )
      }
    }
  )
}

/**
 * Full editor for one reusable scenario definition, backed by [ArbigentScenarioStateHolder]
 * so the same option editors as ordinary scenarios are available (goal, type,
 * initialization methods, AI/MCP options, image assertions, steps, inputs).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReusableScenarioEditorDialog(
  appStateHolder: ArbigentAppStateHolder,
  existingScenario: ArbigentScenarioContent?,
  onCloseRequest: () -> Unit,
  onSave: (ArbigentScenarioContent) -> Unit,
) {
  val stateHolder = remember(existingScenario) {
    ArbigentScenarioStateHolder(
      id = existingScenario?.id ?: "new-reusable-scenario",
      tagManager = ArbigentTagManager()
    ).apply {
      existingScenario?.let { load(it) }
    }
  }
  // Browse target for steps rows inside this editor (local, to avoid nested library dialogs).
  var stepBrowseIndex by remember { mutableStateOf<Int?>(null) }
  // Fixed-scenario browse target for MaestroYaml initialization methods inside this editor.
  var fixedScenarioBrowseIndex by remember { mutableStateOf<Int?>(null) }

  TestCompatibleDialog(
    onCloseRequest = onCloseRequest,
    title = if (existingScenario == null) "Add Reusable Scenario" else "Edit Reusable Scenario",
    content = {
      Column(
        modifier = Modifier.padding(16.dp).fillMaxWidth().fillMaxHeight(0.9f)
      ) {
        Column(
          modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
        ) {
          val reusableStepsMode by stateHolder.reusableStepsModeStateFlow.collectAsState()
          if (reusableStepsMode) {
            ReusableStepsEditor(
              scenarioStateHolder = stateHolder,
              getReusableScenarioById = { appStateHolder.getReusableScenarioById(it) },
              onBrowseReusableScenarios = { _, index -> stepBrowseIndex = index },
            )
          } else {
            GroupHeader("Goal")
            TextArea(
              state = stateHolder.goalState,
              modifier = Modifier.fillMaxWidth().height(80.dp).padding(4.dp)
                .testTag("reusable_goal"),
              placeholder = { Text("Goal (use {{inputs.name}} for declared inputs)") },
            )
          }
          ScenarioOptions(
            scenarioStateHolder = stateHolder,
            scenarioCountById = { id ->
              appStateHolder.reusableScenariosFlow.value.count { it.id == id && it.id != existingScenario?.id }
            },
            dependencyScenarioMenu = {},
            onShowFixedScenariosDialog = { _, index -> fixedScenarioBrowseIndex = index },
            getFixedScenarioById = { appStateHolder.getFixedScenarioById(it) },
            mcpServerNames = appStateHolder.mcpServerNamesFlow.collectAsState().value,
            isReusableDefinition = true,
          )
        }
        var validationError by remember { mutableStateOf<String?>(null) }
        validationError?.let { error ->
          Text(
            text = error,
            color = androidx.compose.ui.graphics.Color.Red,
            modifier = Modifier.padding(8.dp).testTag("reusable_validation_error")
          )
        }
        Row(
          modifier = Modifier.padding(8.dp).fillMaxWidth(),
          horizontalArrangement = Arrangement.End
        ) {
          DefaultButton(
            onClick = {
              val content = stateHolder.createArbigentScenarioContent()
              // Reject definitions that would make the saved project fail to load.
              val error = appStateHolder.validateReusableScenarioCandidate(content, existingScenario?.id)
              if (error == null) {
                onSave(content)
              } else {
                validationError = error
              }
            },
            modifier = Modifier.padding(end = 8.dp).testTag("save_reusable_button")
          ) {
            Text(if (existingScenario == null) "Add" else "Update")
          }
          OutlinedButton(onClick = onCloseRequest) {
            Text("Cancel")
          }
        }
      }

      stepBrowseIndex?.let { index ->
        val allReusables = appStateHolder.reusableScenariosFlow.collectAsState().value
        ReusableScenarioPicker(
          // Exclude candidates that would create a cycle: the candidate itself and any
          // composite whose expansion reaches the reusable being edited.
          candidates = allReusables.filter { candidate ->
            candidate.id != stateHolder.id && !reachesReusable(candidate, stateHolder.id, allReusables)
          },
          onDismiss = { stepBrowseIndex = null },
          onPicked = { pickedId ->
            val steps = stateHolder.reusableStepsStateFlow.value
            if (index in steps.indices) {
              stateHolder.onReusableStepChanged(index, steps[index].copy(uses = pickedId))
            }
            stepBrowseIndex = null
          }
        )
      }

      if (fixedScenarioBrowseIndex != null) {
        FixedScenariosDialog(
          appStateHolder = appStateHolder,
          onCloseRequest = { fixedScenarioBrowseIndex = null },
          onScenarioSelected = { fixedScenarioId ->
            fixedScenarioBrowseIndex?.let { index ->
              stateHolder.onInitializationMethodChanged(
                index,
                ArbigentScenarioContent.InitializationMethod.MaestroYaml(scenarioId = fixedScenarioId)
              )
            }
            fixedScenarioBrowseIndex = null
          }
        )
      }
    }
  )
}

/** True when [candidate]'s expansion (transitively) references [targetId]. */
private fun reachesReusable(
  candidate: ArbigentScenarioContent,
  targetId: String,
  allReusables: List<ArbigentScenarioContent>,
  visited: MutableSet<String> = mutableSetOf(),
): Boolean {
  if (!visited.add(candidate.id)) return false
  return candidate.callSteps().any { step ->
    step.uses == targetId || allReusables.firstOrNull { it.id == step.uses }
      ?.let { reachesReusable(it, targetId, allReusables, visited) } == true
  }
}

/** Lightweight picker used inside the editor dialog to choose a reusable scenario. */
@Composable
private fun ReusableScenarioPicker(
  candidates: List<ArbigentScenarioContent>,
  onDismiss: () -> Unit,
  onPicked: (String) -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.background(JewelTheme.globalColors.panelBackground).padding(16.dp)
    ) {
      GroupHeader("Select reusable scenario")
      if (candidates.isEmpty()) {
        Text("No reusable scenarios defined yet.")
      }
      LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
        items(candidates) { candidate ->
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(4.dp)
          ) {
            OutlinedButton(
              onClick = { onPicked(candidate.id) },
              modifier = Modifier.testTag("pick_reusable_${candidate.id}")
            ) {
              Text(candidate.id)
            }
            Text(
              text = if (candidate.isCallForm()) {
                candidate.callSteps().joinToString(" -> ") { it.uses }
              } else {
                candidate.goal
              },
              modifier = Modifier.padding(start = 8.dp)
            )
          }
        }
      }
      Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onDismiss) {
          Text("Cancel")
        }
      }
    }
  }
}
