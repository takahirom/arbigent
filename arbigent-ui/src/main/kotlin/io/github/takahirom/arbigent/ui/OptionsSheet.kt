package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.takahirom.arbigent.FixedScenario
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OptionsSheet(
  scenarioStateHolder: ArbigentScenarioStateHolder,
  scenarioCountById: (String) -> Int,
  dependencyScenarioMenu: MenuScope.() -> Unit,
  onAddSubScenario: (ArbigentScenarioStateHolder) -> Unit,
  onRemove: (ArbigentScenarioStateHolder) -> Unit,
  onShowFixedScenariosDialog: (ArbigentScenarioStateHolder, Int) -> Unit,
  getFixedScenarioById: (String) -> FixedScenario?,
  mcpServerNames: List<String>,
  onDismiss: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    val dialogShape = RoundedCornerShape(12.dp)
    Column(
      modifier = Modifier
        .clip(dialogShape)
        .background(JewelTheme.globalColors.panelBackground)
        .padding(20.dp)
        .fillMaxWidth()
    ) {
      // Title
      Text(
        text = "Scenario Options",
        style = JewelTheme.defaultTextStyle,
        modifier = Modifier.padding(bottom = 16.dp)
      )

      // Tags section
      val tags by scenarioStateHolder.tags.collectAsState()
      GroupHeader("Tags")
      Tags(
        tags = tags,
        onTagAdded = { scenarioStateHolder.addTag() },
        onTagRemoved = { scenarioStateHolder.removeTag(it) },
        onTagChanged = { tag, newName -> scenarioStateHolder.onTagChanged(tag, newName) }
      )

      Spacer(Modifier.height(8.dp))

      // Scrollable options
      Column(
        modifier = Modifier.testTag("scenario_options")
          .heightIn(max = 400.dp)
          .verticalScroll(rememberScrollState())
          .wrapContentHeight(unbounded = true)
      ) {
        ScenarioOptions(
          scenarioStateHolder = scenarioStateHolder,
          scenarioCountById = scenarioCountById,
          dependencyScenarioMenu = dependencyScenarioMenu,
          onShowFixedScenariosDialog = onShowFixedScenariosDialog,
          getFixedScenarioById = getFixedScenarioById,
          mcpServerNames = mcpServerNames,
        )
      }

      Spacer(Modifier.height(16.dp))

      // Bottom action bar
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row {
          OutlinedButton(
            onClick = { onAddSubScenario(scenarioStateHolder) },
          ) {
            Text("Add sub scenario")
          }
          Spacer(Modifier.width(8.dp))
          var removeDialogShowing by remember { mutableStateOf(false) }
          OutlinedButton(
            onClick = { removeDialogShowing = true },
          ) {
            Text("Remove")
          }
          if (removeDialogShowing) {
            Dialog(onDismissRequest = { removeDialogShowing = false }) {
              Column(
                modifier = Modifier
                  .clip(RoundedCornerShape(12.dp))
                  .background(JewelTheme.globalColors.panelBackground)
                  .padding(20.dp)
              ) {
                Text("Are you sure you want to remove this scenario?")
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                  OutlinedButton(onClick = { removeDialogShowing = false }) {
                    Text("Cancel")
                  }
                  Spacer(Modifier.width(8.dp))
                  DefaultButton(onClick = {
                    removeDialogShowing = false
                    onRemove(scenarioStateHolder)
                    onDismiss()
                  }) {
                    Text("Remove")
                  }
                }
              }
            }
          }
        }

        DefaultButton(
          onClick = onDismiss,
        ) {
          Text("Close")
        }
      }
    }
  }
}
