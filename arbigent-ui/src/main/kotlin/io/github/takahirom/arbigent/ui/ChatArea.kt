package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentContextHolder
import io.github.takahirom.arbigent.ArbigentScenarioExecutor
import io.github.takahirom.arbigent.ArbigentTaskAssignment
import io.github.takahirom.arbigent.ExecuteMcpToolAgentAction
import io.github.takahirom.arbigent.getAnnotatedFilePath
import io.github.takahirom.arbigent.result.StepFeedback
import io.github.takahirom.arbigent.result.StepFeedbackEvent
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import org.jetbrains.jewel.ui.theme.colorPalette
import java.awt.Desktop
import java.io.File
import java.io.FileInputStream

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatArea(
  scenarioStateHolder: ArbigentScenarioStateHolder,
  stepFeedbacks: Set<StepFeedback>,
  onStepFeedback: (StepFeedbackEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val arbigentScenarioExecutor: ArbigentScenarioExecutor? by scenarioStateHolder.arbigentScenarioExecutorStateFlow.collectAsState()

  arbigentScenarioExecutor?.let { executor ->
    val taskToAgentsHistory: List<List<ArbigentTaskAssignment>> by executor.taskAssignmentsHistoryFlow.collectAsState(
      executor.taskAssignmentsHistory()
    )
    if (taskToAgentsHistory.isNotEmpty()) {
      ChatContent(
        tasksToAgentHistory = taskToAgentsHistory,
        stepFeedbacks = stepFeedbacks,
        onStepFeedback = onStepFeedback,
        modifier = modifier,
      )
    } else {
      EmptyStateView(modifier = modifier)
    }
  } ?: EmptyStateView(modifier = modifier)
}

@Composable
fun EmptyStateView(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = "No execution yet",
        style = JewelTheme.defaultTextStyle,
        color = JewelTheme.globalColors.text.info,
      )
      Text(
        text = "Type a goal below and click Run to start",
        color = JewelTheme.globalColors.text.info,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatContent(
  tasksToAgentHistory: List<List<ArbigentTaskAssignment>>,
  stepFeedbacks: Set<StepFeedback>,
  onStepFeedback: (StepFeedbackEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier) {
    // History selector
    var selectedHistory by remember(tasksToAgentHistory.size) { mutableStateOf(tasksToAgentHistory.lastIndex) }
    if (tasksToAgentHistory.size > 1) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Run History:", modifier = Modifier.padding(end = 8.dp))
        Dropdown(
          modifier = Modifier.padding(4.dp),
          menuContent = {
            tasksToAgentHistory.forEachIndexed { index, _ ->
              selectableItem(
                selected = index == selectedHistory,
                onClick = { selectedHistory = index },
              ) {
                Text(text = "Run ${index + 1}")
              }
            }
          }
        ) {
          Text("Run ${selectedHistory + 1}")
        }
      }
    }

    val tasksToAgent = tasksToAgentHistory[selectedHistory]
    var selectedStep: ArbigentContextHolder.Step? by remember { mutableStateOf(null) }

    Row(Modifier.weight(1f)) {
      // Chat messages
      val lazyColumnState = rememberLazyListState()
      val totalItemsCount by derivedStateOf { lazyColumnState.layoutInfo.totalItemsCount }
      LaunchedEffect(totalItemsCount) {
        lazyColumnState.animateScrollToItem(maxOf(totalItemsCount - 1, 0))
      }
      val sections: List<ScenarioSection> = buildSections(tasksToAgent)

      LazyColumn(
        state = lazyColumnState,
        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
      ) {
        sections.forEachIndexed { sectionIndex, section ->
          // Goal header
          stickyHeader {
            ChatGoalHeader(
              goal = section.goal,
              index = sectionIndex,
              total = tasksToAgent.size,
              isAchieved = section.isAchieved(),
            )
          }

          // Step messages
          itemsIndexed(items = section.steps) { stepIndex, item ->
            ChatMessage(
              step = item.step,
              stepIndex = stepIndex,
              isAchieved = item.isAchieved(),
              stepFeedbacks = stepFeedbacks,
              onStepFeedback = onStepFeedback,
              isSelected = item.step == selectedStep,
              onClick = {
                selectedStep = if (selectedStep == item.step) null else item.step
              },
            )
          }

          // Running indicator
          item {
            if (section.isRunning) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center,
              ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(
                  text = "Agent is working...",
                  modifier = Modifier.padding(start = 8.dp),
                  color = JewelTheme.globalColors.text.info,
                )
              }
            }
          }
        }
      }

      // Step detail panel (when a step is selected) — collapsible
      selectedStep?.let { step ->
        Divider(
          orientation = org.jetbrains.jewel.ui.Orientation.Vertical,
          modifier = Modifier.fillMaxHeight(),
          thickness = 1.dp,
        )
        Column(Modifier.weight(1f)) {
          // Close button header
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Step Details",
              modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconActionButton(
              key = AllIconsKeys.Actions.Cancel,
              onClick = { selectedStep = null },
              contentDescription = "Close details",
              hint = Size(16),
            ) {
              Text("Close detail panel")
            }
          }
          Divider(
            orientation = org.jetbrains.jewel.ui.Orientation.Horizontal,
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.dp,
          )
          StepDetailPanel(
            step = step,
            modifier = Modifier.weight(1f).padding(8.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun ChatGoalHeader(
  goal: String,
  index: Int,
  total: Int,
  isAchieved: Boolean,
) {
  val prefix = if (index + 1 == total) "Goal: " else "Dependency: "
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(JewelTheme.globalColors.panelBackground)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      key = AllIconsKeys.Debugger.ThreadAtBreakpoint,
      contentDescription = "Goal",
      hint = Size(16),
      modifier = Modifier.padding(end = 8.dp),
      tint = JewelTheme.colorPalette.purple(4),
    )
    Text(
      text = "$prefix$goal (${index + 1}/$total)",
      modifier = Modifier.weight(1f),
    )
    if (isAchieved) {
      PassedMark(modifier = Modifier.size(20.dp))
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessage(
  step: ArbigentContextHolder.Step,
  stepIndex: Int,
  isAchieved: Boolean,
  stepFeedbacks: Set<StepFeedback>,
  onStepFeedback: (StepFeedbackEvent) -> Unit,
  isSelected: Boolean,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(8.dp)
  val bgColor = if (isSelected) {
    JewelTheme.colorPalette.purple(2)
  } else {
    Color.Transparent
  }
  val borderColor = if (step.isFailed()) {
    JewelTheme.colorPalette.red(4)
  } else if (isAchieved) {
    JewelTheme.colorPalette.green(4)
  } else {
    Color.Transparent
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
      .clip(shape)
      .background(bgColor, shape)
      .then(
        if (borderColor != Color.Transparent) Modifier.border(1.dp, borderColor, shape)
        else Modifier
      )
      .clickable(onClick = onClick)
      .padding(12.dp),
  ) {
    // Header row: step number, timestamp, badges
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Step ${stepIndex + 1}",
        modifier = Modifier.padding(end = 8.dp),
      )
      Text(
        text = formatTimestamp(step.timestamp),
        color = JewelTheme.globalColors.text.info,
        modifier = Modifier.weight(1f),
      )
      if (step.cacheHit) {
        Text(
          "Cache",
          modifier = Modifier.padding(horizontal = 4.dp)
            .background(JewelTheme.colorPalette.purple(2), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        )
      }
      if (step.agentAction is ExecuteMcpToolAgentAction) {
        Text(
          "MCP",
          modifier = Modifier.padding(horizontal = 4.dp)
            .background(JewelTheme.colorPalette.purple(2), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        )
      }
      if (step.isFailed()) {
        Icon(
          key = AllIconsKeys.General.Error,
          contentDescription = "Failed",
          hint = Size(16),
          modifier = Modifier.padding(start = 4.dp),
        )
      } else if (isAchieved) {
        PassedMark(modifier = Modifier.size(16.dp))
      }
    }

    // Content: screenshot + description side by side
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
      // Screenshot thumbnail
      val annotatedPath = File(step.screenshotFilePath).getAnnotatedFilePath()
      val screenshotFile = File(annotatedPath)
      if (screenshotFile.exists()) {
        val bitmap = remember(annotatedPath) {
          try {
            loadImageBitmap(FileInputStream(screenshotFile))
          } catch (_: Exception) {
            null
          }
        }
        bitmap?.let {
          Image(
            bitmap = it,
            contentDescription = "Screenshot",
            modifier = Modifier
              .widthIn(max = 180.dp)
              .clip(RoundedCornerShape(6.dp))
              .onClick {
                Desktop.getDesktop().open(screenshotFile)
              },
          )
        }
      }

      // Step text description
      Column(
        modifier = Modifier.weight(1f).padding(start = 12.dp),
      ) {
        val stepText = step.text()
        if (stepText.isNotBlank()) {
          Text(
            text = stepText,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }

    // Feedback buttons
    if (step.apiCallJsonLFilePath != null) {
      Row(
        modifier = Modifier.padding(top = 6.dp),
      ) {
        val isGood = stepFeedbacks.any { it is StepFeedback.Good && it.stepId == step.stepId }
        val isBad = stepFeedbacks.any { it is StepFeedback.Bad && it.stepId == step.stepId }
        val feedbackHintText =
          "Feedback data is stored locally in project result files(result.yaml)."

        IconActionButton(
          key = AllIconsKeys.Ide.Like,
          onClick = {
            onStepFeedback(
              if (isGood) StepFeedbackEvent.RemoveGood(step.stepId)
              else StepFeedback.Good(step.stepId)
            )
          },
          colorFilter = if (isGood) ColorFilter.tint(JewelTheme.colorPalette.green(8)) else null,
          contentDescription = feedbackHintText,
          hint = Size(16),
        ) { Text(text = feedbackHintText) }

        IconActionButton(
          key = AllIconsKeys.Ide.Dislike,
          onClick = {
            onStepFeedback(
              if (isBad) StepFeedbackEvent.RemoveBad(step.stepId)
              else StepFeedback.Bad(step.stepId)
            )
          },
          colorFilter = if (isBad) ColorFilter.tint(JewelTheme.colorPalette.red(8)) else null,
          contentDescription = feedbackHintText,
          hint = Size(16),
        ) { Text(text = feedbackHintText) }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StepDetailPanel(
  step: ArbigentContextHolder.Step,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState()),
  ) {
    step.uiTreeStrings?.let { trees ->
      ExpandableSection(
        "All UI Tree (length=${trees.allTreeString.length})",
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          modifier = Modifier.padding(8.dp)
            .background(JewelTheme.globalColors.panelBackground),
          text = trees.allTreeString,
        )
      }
      ExpandableSection(
        "Optimized UI Tree (length=${trees.optimizedTreeString.length})",
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          modifier = Modifier.padding(8.dp)
            .background(JewelTheme.globalColors.panelBackground),
          text = trees.optimizedTreeString,
        )
      }
    }
    step.aiRequest?.let { request ->
      ExpandableSection(
        title = "AI Request",
        defaultExpanded = true,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          modifier = Modifier.padding(8.dp)
            .background(JewelTheme.globalColors.panelBackground),
          text = request,
        )
      }
    }
    step.aiResponse?.let { response ->
      ExpandableSection(
        title = "AI Response",
        defaultExpanded = true,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          modifier = Modifier.padding(8.dp)
            .background(JewelTheme.globalColors.panelBackground),
          text = response,
        )
      }
    }

    // Full screenshots
    val annotatedPath = File(step.screenshotFilePath).getAnnotatedFilePath()
    if (File(annotatedPath).exists()) {
      ExpandableSection(
        title = "Annotated Screenshot",
        defaultExpanded = false,
        modifier = Modifier.fillMaxWidth()
      ) {
        val bitmap = remember(annotatedPath) {
          try { loadImageBitmap(FileInputStream(annotatedPath)) } catch (_: Exception) { null }
        }
        bitmap?.let {
          Image(
            bitmap = it,
            contentDescription = "Annotated screenshot",
          )
        }
        Text(
          modifier = Modifier.onClick {
            Desktop.getDesktop().open(File(annotatedPath))
          },
          text = "Open: $annotatedPath",
        )
      }
    }
  }
}
