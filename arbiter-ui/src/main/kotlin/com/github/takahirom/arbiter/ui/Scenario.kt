package com.github.takahirom.arbiter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.github.takahirom.arbiter.Agent
import com.github.takahirom.arbiter.Arbiter
import com.github.takahirom.arbiter.ArbiterContextHolder
import com.github.takahirom.arbiter.DeviceFormFactor
import com.github.takahirom.arbiter.GoalAchievedAgentCommand
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import java.io.FileInputStream

@Composable
fun Scenario(
  scenarioStateHolder: ScenarioStateHolder,
  dependencyScenarioMenu: MenuScope.() -> Unit,
  onExecute: (ScenarioStateHolder) -> Unit,
  onCancel: (ScenarioStateHolder) -> Unit,
  onRemove: (ScenarioStateHolder) -> Unit,
) {
  val arbiter: Arbiter? by scenarioStateHolder.arbiterStateFlow.collectAsState()
  val goal = scenarioStateHolder.goalState
  Column(
    modifier = Modifier.padding(8.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically
    ) {
      TextField(
        modifier = Modifier.weight(1f).padding(4.dp).testTag("goal"),
        state = goal,
        placeholder = { Text("Goal") },
      )
      IconActionButton(
        key = AllIconsKeys.RunConfigurations.TestState.Run,
        onClick = {
          onExecute(scenarioStateHolder)
        },
        contentDescription = "Run",
        hint = Size(28)
      )
      IconActionButton(
        key = AllIconsKeys.Actions.Cancel,
        onClick = {
          onCancel(scenarioStateHolder)
        },
        contentDescription = "Cancel",
        hint = Size(28)
      )
      var removeDialogShowing by remember { mutableStateOf(false) }
      IconActionButton(
        key = AllIconsKeys.General.Delete,
        onClick = {
          removeDialogShowing = true
        },
        contentDescription = "Remove",
        hint = Size(28)
      )
      if (removeDialogShowing) {
        Dialog(
          onDismissRequest = { removeDialogShowing = false }
        ) {
          Column(
            modifier = Modifier.background(JewelTheme.globalColors.panelBackground).padding(8.dp)
          ) {
            Text("Are you sure you want to remove this scenario?")
            Row {
              OutlinedButton(
                onClick = {
                  removeDialogShowing = false
                }
              ) {
                Text("Cancel")
              }
              OutlinedButton(
                onClick = {
                  removeDialogShowing = false
                  onRemove(scenarioStateHolder)
                }
              ) {
                Text("Remove")
              }
            }
          }
        }
      }
    }
    var isExpanded by remember { mutableStateOf(false) }
    Row(
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (isExpanded) {
        IconActionButton(
          key = AllIconsKeys.General.ArrowDown,
          onClick = {
            isExpanded = !isExpanded
          },
          contentDescription = "Collapse",
          hint = Size(28)
        )
      } else {
        IconActionButton(
          key = AllIconsKeys.General.ArrowRight,
          onClick = {
            isExpanded = !isExpanded
          },
          contentDescription = "Expand",
          hint = Size(28)
        )
      }
      GroupHeader("Options")
    }
    AnimatedVisibility(visible = isExpanded) {
      ScenarioOptions(scenarioStateHolder, dependencyScenarioMenu)
    }
    if (arbiter != null) {
      val taskToAgents: List<Pair<Arbiter.Task, Agent>> by arbiter!!.taskToAgentStateFlow.collectAsState()
      if (!taskToAgents.isEmpty()) {
        ContentPanel(taskToAgents)
      }
    }
  }
}

@Composable
private fun ScenarioOptions(
  scenarioStateHolder: ScenarioStateHolder,
  dependencyScenarioMenu: MenuScope.() -> Unit
) {
  Row(modifier = Modifier.padding(4.dp)) {
    Column(
      modifier = Modifier.padding(8.dp).weight(1F)
    ) {
      GroupHeader("Scenario dependency")
      val dependency by scenarioStateHolder.dependencyScenarioStateFlow.collectAsState()

      Dropdown(
        modifier = Modifier
          .testTag("dependency_dropdown")
          .padding(4.dp),
        menuContent = dependencyScenarioMenu
      ) {
        Text(dependency?.goal ?: "Select dependency")
      }
    }
    Column(
      modifier = Modifier.padding(8.dp).weight(1F)
    ) {
      val inputCommandType by scenarioStateHolder.deviceFormFactorStateFlow.collectAsState()
      GroupHeader("Device formfactor")
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButtonRow(
          text = "Mobile",
          selected = inputCommandType.isMobile(),
          onClick = {
            scenarioStateHolder.deviceFormFactorStateFlow.value = DeviceFormFactor.Mobile
          }
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButtonRow(
          text = "TV",
          selected = inputCommandType.isTv(),
          onClick = {
            scenarioStateHolder.deviceFormFactorStateFlow.value = DeviceFormFactor.Tv
          }
        )
      }
    }
    Column(
      modifier = Modifier.padding(8.dp).weight(1F)
    ) {
      val initializeMethods by scenarioStateHolder.initializeMethodsStateFlow.collectAsState()
      val cleanupData by scenarioStateHolder.cleanupDataStateFlow.collectAsState()
      GroupHeader("Initialize method")
      CheckboxRow(
        text = "Cleanup app data",
        checked = cleanupData is CleanupData.Cleanup,
        onCheckedChange = {
          scenarioStateHolder.cleanupDataStateFlow.value = if (it) {
            CleanupData.Cleanup((cleanupData as? CleanupData.Cleanup)?.packageName ?: "")
          } else {
            CleanupData.Noop
          }
        }
      )
      TextField(
        modifier = Modifier
          .padding(4.dp),
        placeholder = { Text("Package name") },
        enabled = cleanupData is CleanupData.Cleanup,
        value = (cleanupData as? CleanupData.Cleanup)?.packageName ?: "",
        onValueChange = {
          scenarioStateHolder.cleanupDataStateFlow.value = CleanupData.Cleanup(it)
        },
      )
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButtonRow(
          selected = initializeMethods == InitializeMethods.Back,
          text = "Back",
          onClick = {
            scenarioStateHolder.initializeMethodsStateFlow.value = InitializeMethods.Back
          }
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButtonRow(
          text = "Do nothing",
          selected = initializeMethods is InitializeMethods.Noop,
          onClick = {
            scenarioStateHolder.initializeMethodsStateFlow.value = InitializeMethods.Noop
          }
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        var editingText by remember(initializeMethods) {
          mutableStateOf(
            (initializeMethods as? InitializeMethods.OpenApp)?.packageName ?: ""
          )
        }
        RadioButtonRow(
          selected = initializeMethods is InitializeMethods.OpenApp,
          onClick = {
            scenarioStateHolder.initializeMethodsStateFlow.value =
              InitializeMethods.OpenApp(editingText)
          }
        ) {
          Column {
            Text(modifier = Modifier.padding(top = 4.dp), text = "Launch app")
            TextField(
              modifier = Modifier
                .padding(4.dp),
              enabled = initializeMethods is InitializeMethods.OpenApp,
              value = editingText,
              onValueChange = {
                editingText = it
                scenarioStateHolder.initializeMethodsStateFlow.value =
                  InitializeMethods.OpenApp(it)
              },
            )
          }
        }
      }
    }
    Column(
      modifier = Modifier.padding(8.dp).weight(1F)
    ) {
      GroupHeader("Max retry count")
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Retry count
        TextField(
          state = scenarioStateHolder.maxRetryState,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier
            .padding(4.dp),
        )
      }
      GroupHeader("Max turn count")
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Retry count
        TextField(
          modifier = Modifier
            .padding(4.dp),
          state = scenarioStateHolder.maxTurnState,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      }
    }
  }
}

@Composable
private fun ContentPanel(tasksToAgent: List<Pair<Arbiter.Task, Agent>>) {
  var selectedTurn: ArbiterContextHolder.Turn? by remember { mutableStateOf(null) }
  Row(Modifier) {
    LazyColumn(modifier = Modifier.weight(1.5f)) {
      itemsIndexed(items = tasksToAgent) { index, (tasks, agent) ->
        val latestContext by agent.latestArbiterContextStateFlow.collectAsState()
        val latestTurnsStateFlow = latestContext?.turns ?: return@itemsIndexed
        val turns: List<ArbiterContextHolder.Turn> by latestTurnsStateFlow.collectAsState()

        if (turns.isEmpty()) {
          return@itemsIndexed
        }
        Column(Modifier.padding(8.dp)) {
          GroupHeader(
            modifier = Modifier.fillMaxWidth(),
            text = tasks.goal + "(" + (index + 1) + "/" + tasksToAgent.size + ")",
          )
          turns.forEachIndexed { index, turn ->
            Column(
              Modifier.padding(8.dp)
                .background(
                  color = if (turn.memo.contains("Failed")) {
                    Color.Red
                  } else if (turn.agentCommand is GoalAchievedAgentCommand) {
                    Color.Green
                  } else {
                    Color.White
                  },
                )
                .clickable { selectedTurn = turn },
            ) {
              GroupHeader(
                modifier = Modifier.fillMaxWidth(),
                text = "Turn ${index + 1}",
              )
              Text(
                modifier = Modifier.padding(8.dp),
                text = turn.text()
              )
            }
          }
        }
      }
    }
    selectedTurn?.let { turn ->
      val scrollableState = rememberScrollState()
      Column(
        Modifier
          .weight(2f)
          .padding(8.dp)
          .verticalScroll(scrollableState),
      ) {
        turn.aiRequest?.let { request: String ->
          GroupHeader(
            modifier = Modifier.fillMaxWidth(),
            text = "AI Request"
          )
          Text(
            modifier = Modifier
              .padding(8.dp)
              .background(JewelTheme.globalColors.panelBackground),
            text = request
          )
        }
        turn.aiResponse?.let { response: String ->
          GroupHeader(
            modifier = Modifier.fillMaxWidth(),
            text = "AI Response"
          )
          Text(
            modifier = Modifier
              .padding(8.dp)
              .background(JewelTheme.globalColors.panelBackground),
            text = response
          )
        }
      }
      turn.screenshotFileName.let { name ->
        Column(
          Modifier
            .fillMaxHeight()
            .weight(1f)
            .padding(8.dp),
          verticalArrangement = Arrangement.Center,
        ) {
          val fileName = "screenshots/$name.png"
          Image(
            bitmap = loadImageBitmap(FileInputStream(fileName)),
            contentDescription = "screenshot",
          )
          Text("Screenshot($fileName)")
        }
      }
    }
  }
}
