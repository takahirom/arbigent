package io.github.takahirom.arbigent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.onClick
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.takahirom.arbigent.*
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
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
import java.awt.Desktop
import java.io.File
import java.io.FileInputStream

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Scenario(
  scenarioStateHolder: ArbigentScenarioStateHolder,
  dependencyScenarioMenu: MenuScope.() -> Unit,
  onAddSubScenario: (ArbigentScenarioStateHolder) -> Unit,
  onExecute: (ArbigentScenarioStateHolder) -> Unit,
  onCancel: (ArbigentScenarioStateHolder) -> Unit,
  onRemove: (ArbigentScenarioStateHolder) -> Unit,
) {
  val arbigentScenarioExecutor: ArbigentScenarioExecutor? by scenarioStateHolder.arbigentScenarioExecutorStateFlow.collectAsState()
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
    ExpandableSection(title = "Options", modifier = Modifier.fillMaxWidth()) {
      ScenarioOptions(scenarioStateHolder, dependencyScenarioMenu)
    }
    arbigentScenarioExecutor?.let { arbigentScenarioExecutor ->
      val taskToAgents: List<List<ArbigentTaskAssignment>> by arbigentScenarioExecutor.taskAssignmentsHistoryFlow.collectAsState(
        arbigentScenarioExecutor.taskAssignmentsHistory()
      )
      if (taskToAgents.isNotEmpty()) {
        ContentPanel(taskToAgents, modifier = Modifier.weight(1f))
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ScenarioOptions(
  scenarioStateHolder: ArbigentScenarioStateHolder,
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
              ArbigentScenarioDeviceFormFactor.Mobile
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
            scenarioStateHolder.deviceFormFactorStateFlow.value =
              ArbigentScenarioDeviceFormFactor.Tv
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
        checked = cleanupData is ArbigentScenarioContent.CleanupData.Cleanup,
        onCheckedChange = {
          scenarioStateHolder.cleanupDataStateFlow.value = if (it) {
            ArbigentScenarioContent.CleanupData.Cleanup(
              (cleanupData as? ArbigentScenarioContent.CleanupData.Cleanup)?.packageName ?: ""
            )
          } else {
            ArbigentScenarioContent.CleanupData.Noop
          }
        }
      )
      TextField(
        modifier = Modifier
          .padding(4.dp),
        placeholder = { Text("Package name") },
        enabled = cleanupData is ArbigentScenarioContent.CleanupData.Cleanup,
        value = (cleanupData as? ArbigentScenarioContent.CleanupData.Cleanup)?.packageName ?: "",
        onValueChange = {
          scenarioStateHolder.cleanupDataStateFlow.value =
            ArbigentScenarioContent.CleanupData.Cleanup(it)
        },
      )
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        var editingText by remember(initializeMethods) {
          mutableStateOf(
            (initializeMethods as? ArbigentScenarioContent.InitializeMethods.Back)?.times.toString()
          )
        }
        RadioButtonRow(
          selected = initializeMethods is ArbigentScenarioContent.InitializeMethods.Back,
          onClick = {
            scenarioStateHolder.initializeMethodsStateFlow.value =
              ArbigentScenarioContent.InitializeMethods.Back()
          }
        ) {
          Column {
            Text(modifier = Modifier.padding(top = 4.dp), text = "Back")
            TextField(
              modifier = Modifier
                .padding(4.dp),
              enabled = initializeMethods is ArbigentScenarioContent.InitializeMethods.Back,
              value = editingText,
              placeholder = { Text("Times") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              onValueChange = {
                editingText = it
                scenarioStateHolder.initializeMethodsStateFlow.value =
                  ArbigentScenarioContent.InitializeMethods.Back(it.toIntOrNull() ?: 1)
              },
            )
          }
        }
      }
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButtonRow(
          text = "Do nothing",
          selected = initializeMethods is ArbigentScenarioContent.InitializeMethods.Noop,
          onClick = {
            scenarioStateHolder.initializeMethodsStateFlow.value =
              ArbigentScenarioContent.InitializeMethods.Noop
          }
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        var editingText by remember(initializeMethods) {
          mutableStateOf(
            (initializeMethods as? ArbigentScenarioContent.InitializeMethods.LaunchApp)?.packageName
              ?: ""
          )
        }
        RadioButtonRow(
          selected = initializeMethods is ArbigentScenarioContent.InitializeMethods.LaunchApp,
          onClick = {
            scenarioStateHolder.initializeMethodsStateFlow.value =
              ArbigentScenarioContent.InitializeMethods.LaunchApp(editingText)
          }
        ) {
          Column {
            Text(modifier = Modifier.padding(top = 4.dp), text = "Launch app")
            TextField(
              modifier = Modifier
                .padding(4.dp),
              enabled = initializeMethods is ArbigentScenarioContent.InitializeMethods.LaunchApp,
              value = editingText,
              onValueChange = {
                editingText = it
                scenarioStateHolder.initializeMethodsStateFlow.value =
                  ArbigentScenarioContent.InitializeMethods.LaunchApp(it)
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
    Column(
      modifier = Modifier.padding(8.dp).width(200.dp)
    ) {
      GroupHeader {
        Text("Image assertion")
        IconActionButton(
          key = AllIconsKeys.General.Information,
          onClick = {},
          contentDescription = "Image assertion",
          hint = Size(16),
        ) {
          Text(
            text = "The AI checks the screenshot when the goal is achieved. If the screenshot doesn't match the assertion, the goal is considered not achieved, and the agent will try other actions.",
          )
        }
      }
      val imageAssertions by scenarioStateHolder.imageAssertionsStateFlow.collectAsState()
      // We don't support multiple image assertions yet.
      val imageAssertion = imageAssertions.firstOrNull()
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        TextField(
          modifier = Modifier
            .padding(4.dp)
            .testTag("image_assertion"),
          placeholder = { Text("The register button should exist") },
          value = imageAssertion?.assertionPrompt ?: "",
          onValueChange = {
            if (it.isBlank()) {
              scenarioStateHolder.imageAssertionsStateFlow.value = emptyList()
            } else {
              scenarioStateHolder.imageAssertionsStateFlow.value = listOf(
                ArbigentImageAssertion(
                  assertionPrompt = it,
                )
              )
            }
          },
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

data class StepItem(val step: ArbigentContextHolder.Step) {
  fun isArchived(): Boolean {
    return step.agentCommand is GoalAchievedAgentCommand
  }
}

@Composable
fun buildSections(tasksToAgent: List<ArbigentTaskAssignment>): List<ScenarioSection> {
  val sections = mutableListOf<ScenarioSection>()
  for ((tasks, agent) in tasksToAgent) {
    val latestContext: ArbigentContextHolder? by agent.latestArbigentContextFlow.collectAsState(
      agent.latestArbigentContext()
    )
    val isRunning by agent.isRunningFlow.collectAsState()
    val nonNullContext = latestContext ?: continue
    val steps: List<ArbigentContextHolder.Step> by nonNullContext.stepsFlow.collectAsState(
      nonNullContext.steps()
    )
    sections += ScenarioSection(
      goal = tasks.goal,
      isRunning = isRunning,
      steps = steps.map { StepItem(it) })
  }
  return sections
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContentPanel(tasksToAgentHistory: List<List<ArbigentTaskAssignment>>, modifier: Modifier) {
  Column(modifier.padding(top = 8.dp)) {
    var selectedHistory by remember(tasksToAgentHistory.size) { mutableStateOf(tasksToAgentHistory.lastIndex) }
    GroupHeader {
      Text("AI Agent Logs")
      Dropdown(
        modifier = Modifier.padding(4.dp),
        menuContent = {
          tasksToAgentHistory.forEachIndexed { index, taskToAgent ->
            selectableItem(
              selected = index == selectedHistory,
              onClick = { selectedHistory = index },
            ) {
              Text(
                text = "History " + index,
              )
            }
          }
        }
      ) {
        Text("History $selectedHistory")
      }
    }
    val tasksToAgent = tasksToAgentHistory[selectedHistory]
    var selectedStep: ArbigentContextHolder.Step? by remember { mutableStateOf(null) }
    Row(Modifier) {
      val lazyColumnState = rememberLazyListState()
      val totalItemsCount by derivedStateOf { lazyColumnState.layoutInfo.totalItemsCount }
      LaunchedEffect(totalItemsCount) {
        lazyColumnState.animateScrollToItem(maxOf(totalItemsCount - 1, 0))
      }
      val sections: List<ScenarioSection> = buildSections(tasksToAgent)
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
                PassedMark(
                  modifier = Modifier.align(Alignment.CenterVertically)
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
                  PassedMark(
                    modifier = Modifier.padding(4.dp).size(12.dp)
                      .align(Alignment.CenterVertically)
                  )
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
          step.uiTreeStrings?.let {
            val clipboardManager = LocalClipboardManager.current
            ExpandableSection("All UI Tree(length=${it.allTreeString.length})", modifier = Modifier.fillMaxWidth()) {
              Text(
                modifier = Modifier
                  .padding(8.dp)
                  .clickable {
                    clipboardManager.setText(
                      annotatedString = buildAnnotatedString { append(it.allTreeString) }
                    )
                  }
                  .background(JewelTheme.globalColors.panelBackground),
                text = it.allTreeString
              )
            }
            ExpandableSection("Optimized UI Tree(length=${it.optimizedTreeString.length})", modifier = Modifier.fillMaxWidth()) {
              Text(
                modifier = Modifier
                  .padding(8.dp)
                  .background(JewelTheme.globalColors.panelBackground),
                text = it.optimizedTreeString
              )
            }
          }
          step.aiRequest?.let { request: String ->
            ExpandableSection(
              title = "AI Request",
              defaultExpanded = true,
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                modifier = Modifier
                  .padding(8.dp)
                  .background(JewelTheme.globalColors.panelBackground),
                text = request
              )
            }
          }
          step.aiResponse?.let { response: String ->
            ExpandableSection(
              title = "AI Response",
              defaultExpanded = true,
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                modifier = Modifier
                  .padding(8.dp)
                  .background(JewelTheme.globalColors.panelBackground),
                text = response
              )
            }
          }
        }
        Column(
          Modifier
            .verticalScroll(rememberScrollState())
            .fillMaxHeight()
            .weight(1f)
            .padding(8.dp),
          verticalArrangement = Arrangement.Center,
        ) {
          ExpandableSection(
            title = "Annotated Screenshot",
            defaultExpanded = true,
            modifier = Modifier.fillMaxWidth()
          ) {
            val filePath = File(step.screenshotFilePath).getAnnotatedFilePath()
            Image(
              bitmap = loadImageBitmap(FileInputStream(filePath)),
              contentDescription = "screenshot",
            )
            Text(
              modifier = Modifier.onClick {
                Desktop.getDesktop().open(File(filePath))
              },
              text = "Screenshot($filePath)"
            )
          }
          ExpandableSection(
            title = "Screenshot",
            defaultExpanded = false,
            modifier = Modifier.fillMaxWidth()
          ) {
            val filePath = step.screenshotFilePath
            Image(
              bitmap = loadImageBitmap(FileInputStream(filePath)),
              contentDescription = "screenshot",
            )
            Text(
              modifier = Modifier.onClick {
                Desktop.getDesktop().open(File(filePath))
              },
              text = "Screenshot($filePath)"
            )
          }
        }
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

@Composable
fun ExpandableSection(
  title: String,
  defaultExpanded: Boolean = false,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  var expanded by remember { mutableStateOf(defaultExpanded) }
  Column(modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .clickable { expanded = !expanded }
        .fillMaxWidth()
    ) {
      if (expanded) {
        Icon(
          key = AllIconsKeys.General.ArrowDown,
          contentDescription = "Collapse " + title,
          hint = Size(28)
        )
      } else {
        Icon(
          key = AllIconsKeys.General.ArrowRight,
          contentDescription = "Expand " + title,
          hint = Size(28)
        )
      }
      Text(title)
    }
    AnimatedVisibility(visible = expanded) {
      Column {
        content()
      }
    }
  }
}