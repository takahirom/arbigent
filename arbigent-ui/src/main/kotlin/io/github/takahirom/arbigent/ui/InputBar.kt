package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InputBar(
  scenarioStateHolder: ArbigentScenarioStateHolder,
  onExecute: (ArbigentScenarioStateHolder) -> Unit,
  onDebugExecute: (ArbigentScenarioStateHolder) -> Unit,
  onCancel: (ArbigentScenarioStateHolder) -> Unit,
  onAddSubScenario: (ArbigentScenarioStateHolder) -> Unit,
  onShowOptions: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scenarioType by scenarioStateHolder.scenarioTypeStateFlow.collectAsState()
  val isRunning by scenarioStateHolder.isRunning.collectAsState()
  val goal = scenarioStateHolder.goalState
  val shape = RoundedCornerShape(16.dp)
  val canRun = !isRunning && scenarioType.isScenario()

  // Single rounded-corner container: text + buttons inside, one border only
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 8.dp)
      .clip(shape)
      .border(1.dp, Color(0xFFCCCCCC), shape)
      .padding(horizontal = 12.dp, vertical = 8.dp),
  ) {
    // Borderless text field
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 36.dp, max = 72.dp)
        .testTag("goal"),
    ) {
      BasicTextField(
        state = goal,
        enabled = scenarioType.isScenario() && !isRunning,
        modifier = Modifier.fillMaxWidth(),
        textStyle = JewelTheme.editorTextStyle.copy(
          color = JewelTheme.globalColors.text.normal
        ),
        cursorBrush = SolidColor(JewelTheme.globalColors.text.normal),
      )
      // Placeholder
      if (goal.text.isEmpty()) {
        Text(
          text = "Describe your test goal...",
          color = JewelTheme.globalColors.text.info,
        )
      }
    }

    // Action buttons row at bottom-right
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconActionButton(
        key = AllIconsKeys.RunConfigurations.TestState.Run,
        onClick = { onExecute(scenarioStateHolder) },
        contentDescription = "Run",
        hint = Size(16),
        enabled = canRun,
      ) {
        Text(text = "Run with dependencies")
      }
      IconActionButton(
        key = AllIconsKeys.Actions.StartDebugger,
        onClick = { onDebugExecute(scenarioStateHolder) },
        contentDescription = "Debug",
        hint = Size(16),
        enabled = canRun,
      ) {
        Text(text = "Run only this scenario")
      }
      IconActionButton(
        key = AllIconsKeys.Actions.Cancel,
        onClick = { onCancel(scenarioStateHolder) },
        contentDescription = "Cancel",
        hint = Size(16),
        enabled = isRunning,
      ) {
        Text(text = "Cancel")
      }
      IconActionButton(
        key = AllIconsKeys.CodeStyle.AddNewSectionRule,
        onClick = { onAddSubScenario(scenarioStateHolder) },
        contentDescription = "Add sub",
        hint = Size(16),
      ) {
        Text(text = "Add sub scenario")
      }
      IconActionButton(
        key = AllIconsKeys.General.Settings,
        onClick = onShowOptions,
        contentDescription = "Options",
        hint = Size(16),
      ) {
        Text(text = "Scenario options")
      }
    }
  }
}
