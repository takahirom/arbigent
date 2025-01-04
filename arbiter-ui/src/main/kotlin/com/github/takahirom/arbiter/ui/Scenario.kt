package com.github.takahirom.arbiter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.github.takahirom.arbiter.ArbiterContextHolder
import com.github.takahirom.arbiter.ArbiterScenarioContent
import com.github.takahirom.arbiter.ArbiterScenarioDeviceFormFactor
import com.github.takahirom.arbiter.ArbiterScenarioExecutor
import com.github.takahirom.arbiter.ArbiterTaskAssignment
import com.github.takahirom.arbiter.GoalAchievedAgentCommand
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.styling.GroupHeaderStyle
import org.jetbrains.jewel.ui.component.styling.LocalGroupHeaderStyle
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import org.jetbrains.jewel.ui.theme.colorPalette
import java.io.FileInputStream

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Scenario(
  scenarioStateHolder: ArbiterScenarioStateHolder,
  dependencyScenarioMenu: MenuScope.() -> Unit,
  onAddSubScenario: (ArbiterScenarioStateHolder) -> Unit,
  onExecute: (ArbiterScenarioStateHolder) -> Unit,
  onCancel: (ArbiterScenarioStateHolder) -> Unit,
  onRemove: (ArbiterScenarioStateHolder) -> Unit,
) {
  val arbiterScenarioExecutor: ArbiterScenarioExecutor? by scenarioStateHolder.arbiterScenarioExecutorStateFlow.collectAsState()
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
      ) {
        Text(
          text = "Run",
        )
      }
      IconActionButton(
        key = AllIconsKeys.Actions.Cancel,
        onClick = {
          onCancel(scenarioStateHolder)
        },
        contentDescription = "Cancel",
        hint = Size(28)
      ) {
        Text(
          text = "Cancel",
        )
      }
      IconActionButton(
        key = AllIconsKeys.CodeStyle.AddNewSectionRule,
        onClick = {
          onAddSubScenario(scenarioStateHolder)
        },
        contentDescription = "Add sub scenario",
        hint = Size(28)
      ) {
        Text(
          text = "Add sub scenario",
        )
      }
      var removeDialogShowing by remember { mutableStateOf(false) }
      IconActionButton(
        key = AllIconsKeys.General.Delete,
        onClick = {
          removeDialogShowing = true
        },
        contentDescription = "Remove",
        hint = Size(28)
      ) {
        Text(
          text = "Remove",
        )
      }
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

    var isOptionExpanded by remember { mutableStateOf(false) }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.clickable { isOptionExpanded = !isOptionExpanded }
    ) {
      if (isOptionExpanded) {
        Icon(
          key = AllIconsKeys.General.ArrowDown,
          contentDescription = "Collapse options",
          hint = Size(28)
        )
      } else {
        Icon(
          key = AllIconsKeys.General.ArrowRight,
          contentDescription = "Expand options",
          hint = Size(28)
        )
      }
      GroupHeader("Options")
    }
    AnimatedVisibility(visible = isOptionExpanded) {
      ScenarioOptions(scenarioStateHolder, dependencyScenarioMenu)
    }
    Column(Modifier.weight(1f).padding(top = 8.dp)) {
      GroupHeader("AI Agent Logs")
      arbiterScenarioExecutor?.let { arbiterScenarioExecutor ->
        val taskToAgents: List<ArbiterTaskAssignment> by arbiterScenarioExecutor.taskAssignmentsFlow.collectAsState(arbiterScenarioExecutor.taskAssignments())
        if (taskToAgents.isNotEmpty()) {
          ContentPanel(taskToAgents)
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenarioOptions(
  scenarioStateHolder: ArbiterScenarioStateHolder,
  dependencyScenarioMenu: MenuScope.() -> Unit
) {
  FlowRow(modifier = Modifier.padding(4.dp)) {
    Column(
      modifier = Modifier.padding(8.dp).width(200.dp)
    ) {
      GroupHeader("Scenario dependency")
      val dependency by scenarioStateHolder.dependencyScenarioStateHolderStateFlow.collectAsState()

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
      modifier = Modifier.padding(8.dp).width(200.dp)
    ) {
      val inputCommandType by scenarioStateHolder.deviceFormFactorStateFlow.collectAsState()
      GroupHeader("Device form factors")
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButtonRow(
          text = "Mobile",
          selected = inputCommandType.isMobile(),
          onClick = {
            scenarioStateHolder.deviceFormFactorStateFlow.value =
              ArbiterScenarioDeviceFormFactor.Mobile
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
            scenarioStateHolder.deviceFormFactorStateFlow.value = ArbiterScenarioDeviceFormFactor.Tv
          }
        )
      }
    }
    Column(
      modifier = Modifier.padding(8.dp).width(200.dp)
    ) {
      val initializeMethods by scenarioStateHolder.initializeMethodsStateFlow.collectAsState()
      val cleanupData by scenarioStateHolder.cleanupDataStateFlow.collectAsState()
      GroupHeader("Initialize method")
      CheckboxRow(
        text = "Cleanup app data",
        checked = cleanupData is ArbiterScenarioContent.CleanupData.Cleanup,
        onCheckedChange = {
          scenarioStateHolder.cleanupDataStateFlow.value = if (it) {
            ArbiterScenarioContent.CleanupData.Cleanup(
              (cleanupData as? ArbiterScenarioContent.CleanupData.Cleanup)?.packageName ?: ""
            )
          } else {
            ArbiterScenarioContent.CleanupData.Noop
          }
        }
      )
      TextField(
        modifier = Modifier
          .padding(4.dp),
        placeholder = { Text("Package name") },
        enabled = cleanupData is ArbiterScenarioContent.CleanupData.Cleanup,
        value = (cleanupData as? ArbiterScenarioContent.CleanupData.Cleanup)?.packageName ?: "",
        onValueChange = {
          scenarioStateHolder.cleanupDataStateFlow.value =
            ArbiterScenarioContent.CleanupData.Cleanup(it)
        },
      )
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButtonRow(
          selected = initializeMethods == ArbiterScenarioContent.InitializeMethods.Back,
          text = "Back",
          onClick = {
            scenarioStateHolder.initializeMethodsStateFlow.value =
              ArbiterScenarioContent.InitializeMethods.Back
          }
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButtonRow(
          text = "Do nothing",
          selected = initializeMethods is ArbiterScenarioContent.InitializeMethods.Noop,
          onClick = {
            scenarioStateHolder.initializeMethodsStateFlow.value =
              ArbiterScenarioContent.InitializeMethods.Noop
          }
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        var editingText by remember(initializeMethods) {
          mutableStateOf(
            (initializeMethods as? ArbiterScenarioContent.InitializeMethods.LaunchApp)?.packageName
              ?: ""
          )
        }
        RadioButtonRow(
          selected = initializeMethods is ArbiterScenarioContent.InitializeMethods.LaunchApp,
          onClick = {
            scenarioStateHolder.initializeMethodsStateFlow.value =
              ArbiterScenarioContent.InitializeMethods.LaunchApp(editingText)
          }
        ) {
          Column {
            Text(modifier = Modifier.padding(top = 4.dp), text = "Launch app")
            TextField(
              modifier = Modifier
                .padding(4.dp),
              enabled = initializeMethods is ArbiterScenarioContent.InitializeMethods.LaunchApp,
              value = editingText,
              onValueChange = {
                editingText = it
                scenarioStateHolder.initializeMethodsStateFlow.value =
                  ArbiterScenarioContent.InitializeMethods.LaunchApp(it)
              },
            )
          }
        }
      }
    }
    Column(
      modifier = Modifier.padding(8.dp).width(200.dp)
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
      GroupHeader("Max step count")
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Retry count
        TextField(
          modifier = Modifier
            .padding(4.dp),
          state = scenarioStateHolder.maxStepState,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      }
    }
  }
}

data class ScenarioSection(val goal: String, val isRunning: Boolean, val steps: List<StepItem>) {
  fun isArchived(): Boolean {
    return steps.any { it.isArchived() }
  }
}

data class StepItem(val step: ArbiterContextHolder.Step) {
  fun isArchived(): Boolean {
    return step.agentCommand is GoalAchievedAgentCommand
  }
}

@Composable
fun buildSections(tasksToAgent: List<ArbiterTaskAssignment>): List<ScenarioSection> {
  val sections = mutableListOf<ScenarioSection>()
  for ((tasks, agent) in tasksToAgent) {
    val latestContext: ArbiterContextHolder? by agent.latestArbiterContextFlow.collectAsState(agent.latestArbiterContext())
    val isRunning by agent.isRunningFlow.collectAsState()
    val nonNullContext = latestContext ?: continue
    val steps: List<ArbiterContextHolder.Step> by nonNullContext.stepsFlow.collectAsState(nonNullContext.steps())
    sections += ScenarioSection(
      goal = tasks.goal,
      isRunning = isRunning,
      steps = steps.map { StepItem(it) })
  }
  return sections
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContentPanel(tasksToAgent: List<ArbiterTaskAssignment>) {
  var selectedStep: ArbiterContextHolder.Step? by remember { mutableStateOf(null) }
  Row(Modifier) {
    val lazyColumnState = rememberLazyListState()
    val totalItemsCount by derivedStateOf { lazyColumnState.layoutInfo.totalItemsCount }
    LaunchedEffect(totalItemsCount) {
      lazyColumnState.animateScrollToItem(maxOf(totalItemsCount - 1, 0))
    }
    println(tasksToAgent)
    val sections: List<ScenarioSection> = buildSections(tasksToAgent)
    println(sections)
    LazyColumn(state = lazyColumnState, modifier = Modifier.weight(1.5f)) {
      sections.forEachIndexed { index, section ->
        stickyHeader {
          val prefix = if (index + 1 == tasksToAgent.size) {
            "Goal: "
          } else {
            "Dependency scenario goal: "
          }
          Row(Modifier.background(Color.White)) {
            GroupHeader(
              modifier = Modifier.padding(8.dp)
                .weight(1F),
              text = prefix + section.goal + "(" + (index + 1) + "/" + tasksToAgent.size + ")",
            )
            if (section.isArchived()) {
              PassedMark(modifier = Modifier.align(Alignment.CenterVertically)
                .padding(8.dp)
              )
            }
          }
        }
        itemsIndexed(items = section.steps) { stepIndex, item ->
          val step = item.step
          Column(
            Modifier.padding(8.dp)
              .background(
                color = if (step == selectedStep) {
                  JewelTheme.colorPalette.purple(9)
                } else {
                  Color.Transparent
                },
              )
              .clickable { selectedStep = step },
          ) {
            GroupHeader(
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = "Step ${stepIndex + 1}",
              )
              if (step.isFailed()) {
                Icon(
                  key = AllIconsKeys.General.Error,
                  contentDescription = "Failed",
                  modifier = Modifier.padding(4.dp).align(Alignment.CenterVertically),
                  hint = Size(12)
                )
              } else if (item.isArchived()) {
                PassedMark(modifier = Modifier.padding(4.dp).size(12.dp)
                  .align(Alignment.CenterVertically))
              }
            }
            Text(
              modifier = Modifier.padding(8.dp),

              text = step.text()
            )
          }
        }
        item {
          if (section.isRunning) {
            Column(Modifier.fillMaxWidth()) {
              CircularProgressIndicator(
                modifier = Modifier.padding(8.dp).align(Alignment.CenterHorizontally),
              )
            }
          }
        }
      }
    }
    selectedStep?.let { step ->
      val scrollableState = rememberScrollState()
      Column(
        Modifier
          .weight(1.5f)
          .padding(8.dp)
          .verticalScroll(scrollableState),
      ) {
        step.aiRequest?.let { request: String ->
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
        step.aiResponse?.let { response: String ->
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
      Column(
        Modifier
          .fillMaxHeight()
          .weight(1f)
          .padding(8.dp),
        verticalArrangement = Arrangement.Center,
      ) {
        val fileName = step.screenshotFilePath
        Image(
          bitmap = loadImageBitmap(FileInputStream(fileName)),
          contentDescription = "screenshot",
        )
        Text("Screenshot($fileName)")
      }
    }
  }
}

@Composable
fun PassedMark(modifier: Modifier = Modifier) {
  Icon(
    key = AllIconsKeys.Actions.Checked,
    contentDescription = "Archived",
    modifier = modifier
      .size(32.dp)
      .clip(
        CircleShape
      )
      .background(JewelTheme.colorPalette.green(8))
  )
}

@Composable
fun GroupHeader(
  modifier: Modifier = Modifier,
  style: GroupHeaderStyle = LocalGroupHeaderStyle.current,
  content: @Composable RowScope.() -> Unit,
) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    content()

    Divider(
      orientation = Orientation.Horizontal,
      modifier = Modifier.fillMaxWidth(),
      color = style.colors.divider,
      thickness = style.metrics.dividerThickness,
      startIndent = style.metrics.indent,
    )
  }
}
