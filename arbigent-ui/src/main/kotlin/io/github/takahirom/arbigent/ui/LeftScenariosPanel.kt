package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.takahirom.arbigent.ArbigentTag
import io.github.takahirom.arbigent.ui.ArbigentAppStateHolder.ProjectDialogState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import org.jetbrains.jewel.ui.theme.colorPalette

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LeftScenariosPanel(
  scenarioAndDepths: List<Pair<ArbigentScenarioStateHolder, Int>>,
  scenariosWidth: Dp,
  selectedScenarioIndex: Int,
  appStateHolder: ArbigentAppStateHolder
) {
  val expandedStates = remember { mutableStateMapOf<ArbigentScenarioStateHolder, Boolean>() }
  Column(
    Modifier
      .run {
        if (scenarioAndDepths.isEmpty()) {
          fillMaxSize()
        } else {
          width(scenariosWidth).fillMaxHeight()
        }
      }
      .background(JewelTheme.globalColors.panelBackground),
  ) {
    // Header
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Scenarios",
        modifier = Modifier.weight(1f),
      )
      IconActionButton(
        key = AllIconsKeys.General.Add,
        onClick = { appStateHolder.addScenario() },
        contentDescription = "New scenario",
        hint = Size(16),
      ) {
        Text("New scenario")
      }
    }

    Divider(
      orientation = Orientation.Horizontal,
      modifier = Modifier.fillMaxWidth(),
      thickness = 1.dp,
    )

    // Scenario list
    Box(Modifier.weight(1f)) {
      val lazyColumnState = rememberLazyListState()

      val visibleIndices by remember(scenarioAndDepths, expandedStates) {
        derivedStateOf {
          val indices = mutableSetOf<Int>()
          val ancestorStack = mutableListOf<Pair<Int, ArbigentScenarioStateHolder>>()
          scenarioAndDepths.forEachIndexed { index, (scenarioHolder, depth) ->
            while (ancestorStack.isNotEmpty()) {
              val (ancestorIndex, _) = ancestorStack.last()
              if (scenarioAndDepths[ancestorIndex].second >= depth) {
                ancestorStack.removeLast()
              } else {
                break
              }
            }
            val allAncestorsExpanded = ancestorStack.all { (_, ancestorHolder) ->
              expandedStates.getOrDefault(ancestorHolder, true)
            }
            if (allAncestorsExpanded) {
              indices.add(index)
            }
            ancestorStack.add(index to scenarioHolder)
          }
          indices
        }
      }

      LazyColumn(
        state = lazyColumnState,
        modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
      ) {
        itemsIndexed(scenarioAndDepths) { index, (scenarioStateHolder, depth) ->
          if (!visibleIndices.contains(index)) {
            return@itemsIndexed
          }

          val hasChildren = index < scenarioAndDepths.size - 1 &&
            scenarioAndDepths[index + 1].second > depth

          val isSelected = index == selectedScenarioIndex
          val goal = scenarioStateHolder.goalState.text
          val isAchieved by scenarioStateHolder.isAchieved.collectAsState()
          val isRunning by scenarioStateHolder.isRunning.collectAsState()
          val scenarioType by scenarioStateHolder.scenarioTypeStateFlow.collectAsState()
          val isFailed by scenarioStateHolder.isFailed.collectAsState()

          val interactionSource = remember { MutableInteractionSource() }
          val isHovered by interactionSource.collectIsHoveredAsState()

          // Subtle background: selected = light gray, hovered = very light
          val bgColor = when {
            isSelected -> Color(0xFFEBEBEB)
            isHovered -> Color(0xFFF5F5F5)
            else -> Color.Transparent
          }

          Row(
            modifier = Modifier.fillMaxWidth()
              .padding(horizontal = 6.dp, vertical = 1.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(bgColor)
              .hoverable(interactionSource)
              .clickable { appStateHolder.selectedScenarioIndex.value = index }
              .padding(
                start = 10.dp + 12.dp * depth,
                top = 8.dp,
                end = 6.dp,
                bottom = 8.dp,
              ),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            // Expand/collapse chevron for parents
            if (hasChildren) {
              val isExpanded = expandedStates.getOrDefault(scenarioStateHolder, true)
              Icon(
                key = if (isExpanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                hint = Size(12),
                modifier = Modifier
                  .padding(end = 4.dp)
                  .clickable { expandedStates[scenarioStateHolder] = !isExpanded },
              )
            }

            // Status indicator
            if (isRunning) {
              CircularProgressIndicator(
                modifier = Modifier
                  .size(8.dp)
                  .testTag("scenario_running"),
              )
            } else {
              val statusColor = when {
                isAchieved -> JewelTheme.colorPalette.green(6)
                isFailed -> JewelTheme.colorPalette.red(6)
                else -> Color(0xFFBBBBBB)
              }
              Box(
                modifier = Modifier
                  .size(6.dp)
                  .clip(CircleShape)
                  .background(statusColor),
              )
            }

            // Scenario text
            Text(
              modifier = Modifier.weight(1f).padding(start = 8.dp),
              text = if (scenarioType.isScenario()) {
                goal.toString().ifBlank { "New scenario" }
              } else {
                val scenarioId by scenarioStateHolder.idStateFlow.collectAsState()
                scenarioId.ifBlank { "Execution" }
              },
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )

            // Action icons: visible on hover or when running/selected
            if (isRunning) {
              Icon(
                key = AllIconsKeys.Actions.Cancel,
                contentDescription = "Stop",
                hint = Size(14),
                modifier = Modifier
                  .padding(start = 4.dp)
                  .clickable {
                    appStateHolder.cancel()
                    scenarioStateHolder.cancel()
                  },
              )
            }

            if (isHovered || isSelected) {
              var removeDialogShowing by remember { mutableStateOf(false) }
              Icon(
                key = AllIconsKeys.General.Delete,
                contentDescription = "Delete",
                hint = Size(14),
                modifier = Modifier
                  .padding(start = 4.dp)
                  .clickable { removeDialogShowing = true },
              )
              if (removeDialogShowing) {
                Dialog(onDismissRequest = { removeDialogShowing = false }) {
                  Column(
                    modifier = Modifier
                      .background(JewelTheme.globalColors.panelBackground, RoundedCornerShape(8.dp))
                      .padding(16.dp)
                  ) {
                    Text("Remove this scenario?")
                    Spacer(Modifier.height(12.dp))
                    Row {
                      OutlinedButton(onClick = { removeDialogShowing = false }) {
                        Text("Cancel")
                      }
                      Spacer(Modifier.width(8.dp))
                      DefaultButton(onClick = {
                        removeDialogShowing = false
                        appStateHolder.removeScenario(scenarioStateHolder)
                      }) {
                        Text("Remove")
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }

      if (scenarioAndDepths.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
          Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
              text = "No scenarios yet",
              color = JewelTheme.globalColors.text.info,
            )
            Spacer(Modifier.height(8.dp))
            DefaultButton(
              onClick = { appStateHolder.addScenario() },
            ) {
              Text("New scenario")
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Tags(
  tags: Set<ArbigentTag>,
  onTagAdded: () -> Unit,
  onTagRemoved: (String) -> Unit,
  onTagChanged: (String, String) -> Unit
) {
  FlowRow {
    tags.forEach { tag ->
      val tagName by tag.nameStateFlow.collectAsState()
      Tag(
        tagName = tagName,
        onTagRemoved = onTagRemoved,
        onTagChanged = onTagChanged
      )
    }
    IconActionButton(
      onClick = { onTagAdded() },
      key = AllIconsKeys.General.Add,
      contentDescription = "Add a tag",
    )
  }
}

@Composable
fun Tag(
  tagName: String,
  onTagRemoved: (String) -> Unit,
  onTagChanged: (String, String) -> Unit,
) {
  var isEditingMode by remember { mutableStateOf(false) }
  Row(
    modifier = Modifier.padding(4.dp)
      .background(JewelTheme.colorPalette.purple(2), RoundedCornerShape(4.dp))
      .padding(horizontal = 2.dp)
  ) {
    if (isEditingMode) {
      val textFieldState = rememberTextFieldState(tagName)
      TextField(
        state = textFieldState,
        modifier = Modifier.padding(4.dp)
          .onKeyEvent { event ->
            if (event.key == Key.Enter) {
              onTagChanged(tagName, textFieldState.text.toString())
              isEditingMode = false
              true
            } else {
              false
            }
          },
        onKeyboardAction = {
          onTagChanged(tagName, textFieldState.text.toString())
          isEditingMode = false
        },
      )
      IconActionButton(
        onClick = {
          onTagRemoved(tagName)
          isEditingMode = false
        },
        key = AllIconsKeys.General.Remove,
        contentDescription = "Remove",
      )
    } else {
      Text(
        text = tagName,
        modifier = Modifier.padding(4.dp)
          .clickable { isEditingMode = !isEditingMode }
      )
    }
  }
}
