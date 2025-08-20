package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentTag
import io.github.takahirom.arbigent.ui.ArbigentAppStateHolder.ProjectDialogState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
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
  // Map to manage expanded/collapsed state (scenario index -> expanded state)
  // Default to expanded for new items
  val expandedStates = remember { mutableStateMapOf<Int, Boolean>() }
  Column(
    Modifier
      .run {
        if (scenarioAndDepths.isEmpty()) {
          fillMaxSize()
        } else {
          width(scenariosWidth)
        }
      },
  ) {
    Row(
      modifier = Modifier.padding(8.dp)
    ) {
      IconActionButton(
        key = AllIconsKeys.FileTypes.AddAny,
        onClick = {
          appStateHolder.addScenario()
        },
        contentDescription = "Add",
        hint = Size(28),
        modifier = Modifier.padding(end = 8.dp)
      ) {
        Text("Add scenario")
      }

      IconActionButton(
        key = AllIconsKeys.Diff.MagicResolve,
        onClick = {
          appStateHolder.projectDialogState.value = ProjectDialogState.ShowGenerateScenarioDialog
        },
        contentDescription = "Generate",
        hint = Size(28)
      ) {
        Text("Generate scenario")
      }
    }
    Box {
      if (scenarioAndDepths.isNotEmpty()) {
        (0..scenarioAndDepths.maxOf { it.second }).forEach {
          Divider(
            orientation = Orientation.Vertical,
            modifier = Modifier.padding(start = 4.dp + 12.dp * it)
              .fillMaxHeight()
              .background(JewelTheme.colorPalette.purple(8))
              .width(2.dp)
          )
        }
      }
      val lazyColumnState = rememberLazyListState()
      // Pre-compute visibility for all items to avoid O(n²) complexity
      val visibleIndices = mutableSetOf<Int>()
      val ancestorStack = mutableListOf<Pair<Int, Boolean>>() // (index, isExpanded)
      
      scenarioAndDepths.forEachIndexed { index, (_, depth) ->
        // Pop ancestors that are at same or deeper level
        while (ancestorStack.isNotEmpty()) {
          val (ancestorIndex, _) = ancestorStack.last()
          if (scenarioAndDepths[ancestorIndex].second >= depth) {
            ancestorStack.removeLast()
          } else {
            break
          }
        }
        
        // Check if all ancestors are expanded
        val allAncestorsExpanded = ancestorStack.all { (ancestorIndex, _) ->
          expandedStates.getOrDefault(ancestorIndex, true)
        }
        
        if (allAncestorsExpanded) {
          visibleIndices.add(index)
        }
        
        // Add current item to ancestor stack for its potential children
        val isCurrentExpanded = expandedStates.getOrDefault(index, true)
        ancestorStack.add(index to isCurrentExpanded)
      }
      
      LazyColumn(
        state = lazyColumnState,
        modifier = Modifier.fillMaxSize()
      ) {
        itemsIndexed(scenarioAndDepths) { index, (scenarioStateHolder, depth) ->
          if (!visibleIndices.contains(index)) {
            return@itemsIndexed
          }
          
          // Check if this scenario has children (next item has greater depth)
          val hasChildren = index < scenarioAndDepths.size - 1 && 
            scenarioAndDepths[index + 1].second > depth
          
          val goal = scenarioStateHolder.goalState.text
          Column(
            modifier = Modifier.fillMaxWidth()
              .padding(
                start = 8.dp + 12.dp * depth,
                top = if (depth == 0) 8.dp else 0.dp,
                end = 8.dp,
                bottom = 4.dp
              )
              .background(
                if (index == selectedScenarioIndex) {
                  JewelTheme.colorPalette.purple(9)
                } else {
                  Color.White
                }
              )
              .clickable { appStateHolder.selectedScenarioIndex.value = index },
          ) {
            Row(
              modifier = Modifier.padding(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              // Show expand/collapse button if this scenario has children
              if (hasChildren) {
                val isExpanded = expandedStates.getOrDefault(index, true)
                IconActionButton(
                  onClick = {
                    expandedStates[index] = !isExpanded
                  },
                  key = if (isExpanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                  contentDescription = if (isExpanded) "Collapse" else "Expand",
                  hint = Size(16),
                  modifier = Modifier.padding(end = 4.dp)
                )
              } else {
                // Show spacer if no children (matching button size)
                Box(modifier = Modifier.size(20.dp).padding(end = 4.dp))
              }
              
              val runningInfo by scenarioStateHolder.arbigentScenarioRunningInfo.collectAsState()
              val scenarioType by scenarioStateHolder.scenarioTypeStateFlow.collectAsState()
              Text(
                modifier = Modifier.weight(1f),
                text = if (scenarioType.isScenario()) {
                  "Goal: $goal"
                } else {
                  val scenarioId by scenarioStateHolder.idStateFlow.collectAsState()
                  "Execution: $scenarioId"
                } + "\n" + runningInfo?.toString().orEmpty()
              )
              val isAchieved by scenarioStateHolder.isAchieved.collectAsState()
              if (isAchieved) {
                PassedMark(
                  Modifier.padding(8.dp)
                )
              }
              val isNewlyGenerated by scenarioStateHolder.isNewlyGenerated.collectAsState()
              if (isNewlyGenerated) {
                Icon(
                  key = AllIconsKeys.Diff.MagicResolve,
                  contentDescription = "isNewlyGenerated",
                  modifier = Modifier
                    .size(32.dp)
                    .clip(
                      CircleShape
                    )
                    .background(JewelTheme.colorPalette.purple(8))
                )
              }
              val isRunning by scenarioStateHolder.isRunning.collectAsState()
              if (isRunning) {
                CircularProgressIndicator(
                  modifier = Modifier.padding(8.dp)
                    .size(32.dp)
                    .testTag("scenario_running")
                )
              }
            }
            val tags by scenarioStateHolder.tags.collectAsState()
            Tags(
              tags,
              onTagAdded = {
                scenarioStateHolder.addTag()
              },
              onTagRemoved = {
                scenarioStateHolder.removeTag(it)
              },
              onTagChanged = { tag, newName ->
                scenarioStateHolder.onTagChanged(tag, newName)
              }
            )
          }
        }
      }
      if (scenarioAndDepths.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(8.dp)) {
          DefaultButton(
            modifier = Modifier.align(Alignment.Center),
            onClick = {
              appStateHolder.addScenario()
            },
          ) {
            Text("Add a scenario")
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
      onClick = {
        onTagAdded()
      },
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
      .background(JewelTheme.colorPalette.purple(8))
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
          .clickable {
            isEditingMode = !isEditingMode
          }
      )
    }
  }
}
