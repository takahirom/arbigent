package io.github.takahirom.arbigent.ui

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
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentTag
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette

@Composable
internal fun LeftScenariosPanel(
  scenarioAndDepths: List<Pair<ArbigentScenarioStateHolder, Int>>,
  scenariosWidth: Dp,
  selectedScenarioIndex: Int,
  appStateHolder: ArbigentAppStateHolder
) {
  Box(
    Modifier
      .run {
        if (scenarioAndDepths.isEmpty()) {
          fillMaxSize()
        } else {
          width(scenariosWidth)
        }
      },
  ) {
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
    LazyColumn(
      state = lazyColumnState,
      modifier = Modifier.fillMaxSize()
    ) {
      itemsIndexed(scenarioAndDepths) { index, (scenarioStateHolder, depth) ->
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
            val runningInfo by scenarioStateHolder.arbigentScenarioRunningInfo.collectAsState()
            Text(
              modifier = Modifier.weight(1f),
              text = if (scenarioStateHolder.scenarioType().isScenario()) {
                "Goal: $goal"
              } else {
                "Execution"
              } + "\n" + runningInfo?.toString().orEmpty()
            )
            val isAchieved by scenarioStateHolder.isAchieved.collectAsState()
            if (isAchieved) {
              PassedMark(
                Modifier.padding(8.dp)
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