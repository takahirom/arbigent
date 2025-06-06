package io.github.takahirom.arbigent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.takahirom.arbigent.*
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.github.takahirom.arbigent.ui.components.AiOptionsComponent
import io.github.takahirom.arbigent.result.StepFeedback
import io.github.takahirom.arbigent.result.StepFeedbackEvent
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
import org.jetbrains.jewel.ui.component.TextArea
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
  stepFeedbacks: Set<StepFeedback>,
  dependencyScenarioMenu: MenuScope.() -> Unit,
  onAddSubScenario: (ArbigentScenarioStateHolder) -> Unit,
  scenarioCountById: (String) -> Int,
  onStepFeedback: (StepFeedbackEvent) -> Unit,
  onExecute: (ArbigentScenarioStateHolder) -> Unit,
  onDebugExecute: (ArbigentScenarioStateHolder) -> Unit,
  onCancel: (ArbigentScenarioStateHolder) -> Unit,
  onRemove: (ArbigentScenarioStateHolder) -> Unit,
) {
  val arbigentScenarioExecutor: ArbigentScenarioExecutor? by scenarioStateHolder.arbigentScenarioExecutorStateFlow.collectAsState()
  val scenarioType by scenarioStateHolder.scenarioTypeStateFlow.collectAsState()
  val goal = scenarioStateHolder.goalState
  Column(
    modifier = Modifier.padding(8.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically
    ) {
      TextArea(
        modifier = Modifier.weight(1f).padding(4.dp).testTag("goal").height(48.dp),
        enabled = scenarioType.isScenario(),
        state = goal,
        placeholder = { Text("Goal") },
        textStyle = JewelTheme.editorTextStyle,
        decorationBoxModifier = Modifier.padding(horizontal = 8.dp)
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
          text = "Run with the dependent scenarios",
        )
      }
      IconActionButton(
        key = AllIconsKeys.Actions.StartDebugger,
        onClick = {
          onDebugExecute(scenarioStateHolder)
        },
        contentDescription = "Debug Run",
        hint = Size(28)
      ) {
        Text(
          text = "Run only this scenario",
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
    BoxWithConstraints {
      ExpandableSection(
        title = "Options",
        modifier = Modifier.fillMaxWidth()
          .heightIn(max = maxHeight * 0.7f)
          .verticalScroll(rememberScrollState())
      ) {
        Column(
          modifier = Modifier.testTag("scenario_options")
            .wrapContentHeight(unbounded = true)
        ) {
          ScenarioOptions(scenarioStateHolder, scenarioCountById, dependencyScenarioMenu)
        }
      }
    }
    arbigentScenarioExecutor?.let { arbigentScenarioExecutor ->
      val taskToAgents: List<List<ArbigentTaskAssignment>> by arbigentScenarioExecutor.taskAssignmentsHistoryFlow.collectAsState(
        arbigentScenarioExecutor.taskAssignmentsHistory()
      )
      if (taskToAgents.isNotEmpty()) {
        ContentPanel(taskToAgents, stepFeedbacks, onStepFeedback, modifier = Modifier.weight(1f))
      }
    }
  }
}


@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ScenarioFundamentalOptions(
  scenarioStateHolder: ArbigentScenarioStateHolder,
  scenarioCountById: (String) -> Int,
  dependencyScenarioMenu: MenuScope.() -> Unit
) {
  val updatedScenarioStateHolder by rememberUpdatedState(scenarioStateHolder)
  FlowRow {
    // ID
    Column(
      modifier = Modifier.padding(8.dp).widthIn(min = 80.dp, max = 400.dp).width(IntrinsicSize.Min)
    ) {
      GroupHeader("Scenario ID")
      val idTextFieldState = rememberTextFieldState(updatedScenarioStateHolder.id)
      var duplicated by remember { mutableStateOf(false) }
      LaunchedEffect(updatedScenarioStateHolder.id) {
        idTextFieldState.edit {
          replace(0, idTextFieldState.text.length, updatedScenarioStateHolder.id)
        }
      }
      LaunchedEffect(Unit) {
        snapshotFlow { idTextFieldState.text.toString() }.collect { textFieldScenarioId ->
          if (updatedScenarioStateHolder.id == textFieldScenarioId) {
            duplicated = false
          } else if (scenarioCountById(textFieldScenarioId) == 0 && textFieldScenarioId.isNotBlank()) {
            updatedScenarioStateHolder.onScenarioIdChanged(textFieldScenarioId)
            duplicated = false
          } else {
            duplicated = true
          }
        }
      }
      TextField(
        state = idTextFieldState,
        modifier = Modifier.padding(4.dp).testTag("scenario_id"),
        placeholder = { Text("Scenario ID") },
      )
      if (duplicated) {
        Text(
          text = "This id is already used.",
          color = Color.Red
        )
      }
    }
    // Note
    Column(
      modifier = Modifier.padding(8.dp).widthIn(min = 80.dp, max = 400.dp).width(IntrinsicSize.Min)
    ) {
      GroupHeader("Note for humans")
      TextArea(
        modifier = Modifier
          .padding(4.dp)
          .height(80.dp)
          .testTag("note_for_humans"),
        textStyle = JewelTheme.editorTextStyle,
        state = updatedScenarioStateHolder.noteForHumans,
        placeholder = { Text("Note for humans") },
        decorationBoxModifier = Modifier.padding(horizontal = 8.dp),
      )
    }
    // Dependency
    Column(
      modifier = Modifier.padding(8.dp).width(160.dp)
    ) {
      GroupHeader("Scenario dependency")
      val dependency by updatedScenarioStateHolder.dependencyScenarioStateHolderStateFlow.collectAsState()

      Dropdown(
        modifier = Modifier
          .testTag("dependency_dropdown")
          .padding(4.dp),
        menuContent = dependencyScenarioMenu
      ) {
        Text(dependency?.goal ?: "Select dependency")
      }
    }
    // Scenario type
    Column(
      modifier = Modifier.padding(8.dp).width(160.dp)
    ) {
      GroupHeader {
        Text("Scenario type")
        IconActionButton(
          key = AllIconsKeys.General.Information,
          onClick = {},
          contentDescription = "Scenario type",
          hint = Size(16),
        ) {
          Text(
            text = "Scenario: The agent will try to achieve the goal. \n" +
              "Execution: Just execute the initializations and image assertions.",
          )
        }
      }
      val inputActionType by updatedScenarioStateHolder.scenarioTypeStateFlow.collectAsState()
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButtonRow(
          text = "Scenario",
          selected = inputActionType == ArbigentScenarioType.Scenario,
          onClick = {
            updatedScenarioStateHolder.scenarioTypeStateFlow.value = ArbigentScenarioType.Scenario
          }
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButtonRow(
          text = "Execution",
          selected = inputActionType == ArbigentScenarioType.Execution,
          onClick = {
            updatedScenarioStateHolder.scenarioTypeStateFlow.value = ArbigentScenarioType.Execution
          }
        )
      }
    }
    // Form factor
    Column(
      modifier = Modifier.padding(8.dp).width(160.dp)
    ) {
      val inputActionType by updatedScenarioStateHolder.deviceFormFactorStateFlow.collectAsState()
      GroupHeader("Device form factors")
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButtonRow(
          text = "Mobile",
          selected = inputActionType.isMobile(),
          onClick = {
            updatedScenarioStateHolder.deviceFormFactorStateFlow.value =
              ArbigentScenarioDeviceFormFactor.Mobile
          }
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButtonRow(
          text = "TV",
          selected = inputActionType.isTv(),
          onClick = {
            updatedScenarioStateHolder.deviceFormFactorStateFlow.value =
              ArbigentScenarioDeviceFormFactor.Tv
          }
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButtonRow(
          text = "Unspecified",
          selected = inputActionType.isUnspecified(),
          onClick = {
            updatedScenarioStateHolder.deviceFormFactorStateFlow.value =
              ArbigentScenarioDeviceFormFactor.Unspecified
          }
        )
      }
    }
    // Max retry and step count
    Column(
      modifier = Modifier.padding(8.dp).width(80.dp)
    ) {
      GroupHeader("Max retry count")
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        TextField(
          state = updatedScenarioStateHolder.maxRetryState,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier
            .padding(4.dp),
        )
      }
      GroupHeader("Max step count")
      Row(
        verticalAlignment = Alignment.CenterVertically
      ) {
        TextField(
          modifier = Modifier
            .padding(4.dp),
          state = updatedScenarioStateHolder.maxStepState,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ScenarioOptions(
  scenarioStateHolder: ArbigentScenarioStateHolder,
  scenarioCountById: (String) -> Int,
  dependencyScenarioMenu: MenuScope.() -> Unit
) {
  val updatedScenarioStateHolder by rememberUpdatedState(scenarioStateHolder)
  GroupHeader("Fundamental options")
  ScenarioFundamentalOptions(scenarioStateHolder, scenarioCountById, dependencyScenarioMenu)
  GroupHeader("Other options")
  FlowRow(modifier = Modifier.padding(4.dp)) {
    Column(
      modifier = Modifier.padding(8.dp).width(240.dp)
    ) {
      GroupHeader("AI Options")
      val aiOptions by updatedScenarioStateHolder.aiOptionsFlow.collectAsState()
      val currentOptions = aiOptions ?: ArbigentAiOptions()
      AiOptionsComponent(
        currentOptions = currentOptions,
        onOptionsChanged = updatedScenarioStateHolder::onAiOptionsChanged
      )
    }
    Column(
      modifier = Modifier.padding(8.dp).width(160.dp)
    ) {
      GroupHeader("Cache Options")
      val cacheOptions by updatedScenarioStateHolder.cacheOptionsFlow.collectAsState()
      Column {
        CheckboxRow(
          modifier = Modifier.padding(start = 16.dp),
          text = "Force disable Cache for this scenario",
          checked = cacheOptions?.forceCacheDisabled == true,
          onCheckedChange = { disabled ->
            updatedScenarioStateHolder.onOverrideCacheForceDisabledChanged(disabled)
          }
        )
      }
    }
    Column(
      modifier = Modifier.padding(8.dp).width(240.dp)
    ) {
      GroupHeader {
        Text("Initialization methods")
        // Add button
        IconActionButton(
          key = AllIconsKeys.General.Add,
          onClick = {
            updatedScenarioStateHolder.onAddInitializationMethod()
          },
          contentDescription = "Add initialization method",
          hint = Size(16),
        ) {
          Text(
            text = "Add initialization method",
          )
        }
      }
    }
    val initializeMethods by updatedScenarioStateHolder.initializationMethodStateFlow.collectAsState()
    initializeMethods.forEachIndexed { index, initializeMethod ->
      InitializationOptions(initializeMethod, updatedScenarioStateHolder, initializeMethods, index)
    }
    Column(
      modifier = Modifier.padding(8.dp).width(320.dp)
    ) {
      GroupHeader {
        Text("Image assertion")
        // Add button
        IconActionButton(
          key = AllIconsKeys.General.Add,
          onClick = {
            updatedScenarioStateHolder.onAddImageAssertion()
          },
          contentDescription = "Add image assertion",
          hint = Size(16),
        ) {
          Text(
            text = "Add image assertion",
          )
        }
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
      Column {
        Text(
          modifier = Modifier
            .padding(4.dp),
          text = "History count"
        )
        TextField(
          modifier = Modifier
            .padding(4.dp),
          placeholder = { Text("History count") },
          state = updatedScenarioStateHolder.imageAssertionsHistoryCountState,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      }
      val imageAssertions by updatedScenarioStateHolder.imageAssertionsStateFlow.collectAsState()
      imageAssertions.forEachIndexed { index, imageAssertion: ArbigentImageAssertion ->
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(
            Modifier
              .weight(1f)
          ) {
            Text(
              modifier = Modifier.padding(4.dp),
              text = "Image assertion ${index + 1}"
            )
            TextField(
              modifier = Modifier
                .padding(4.dp)
                .testTag("image_assertion"),
              placeholder = { Text("XX button should exist") },
              value = imageAssertion.assertionPrompt,
              onValueChange = {
                val newImageAssertions = imageAssertions.toMutableList()
                newImageAssertions[index] = imageAssertion.copy(assertionPrompt = it)
                updatedScenarioStateHolder.imageAssertionsStateFlow.value = newImageAssertions
              },
            )
          }

          IconActionButton(
            key = AllIconsKeys.General.Delete,
            onClick = {
              val newImageAssertions = imageAssertions.toMutableList()
              newImageAssertions.removeAt(index)
              updatedScenarioStateHolder.imageAssertionsStateFlow.value = newImageAssertions
            },
            contentDescription = "Remove",
            hint = Size(16),
          ) {
            Text(
              text = "Remove",
            )
          }
        }
      }
    }
  }
}

enum class InitializationMethodMenu(
  val type: String,
  val defaultContent: ArbigentScenarioContent.InitializationMethod
) {
  Noop("NoOp", ArbigentScenarioContent.InitializationMethod.Noop),
  Wait("Wait", ArbigentScenarioContent.InitializationMethod.Wait(0)),
  CleanupData("Cleanup app data", ArbigentScenarioContent.InitializationMethod.CleanupData("")),
  Back("Back", ArbigentScenarioContent.InitializationMethod.Back(1)),
  OpenLink("Open link", ArbigentScenarioContent.InitializationMethod.OpenLink("")),
  LaunchApp("Launch app", ArbigentScenarioContent.InitializationMethod.LaunchApp("", emptyMap()))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InitializationOptions(
  initializeMethod: ArbigentScenarioContent.InitializationMethod,
  scenarioStateHolder: ArbigentScenarioStateHolder,
  initializeMethods: List<ArbigentScenarioContent.InitializationMethod>,
  index: Int
) {
  Column(
    modifier = Modifier.padding(8.dp).width(240.dp)
      .testTag("initialization_method")
  ) {
    GroupHeader {
      Text("Initialization method ${index + 1}")
      IconActionButton(
        key = AllIconsKeys.General.Delete,
        onClick = {
          scenarioStateHolder.onRemoveInitializationMethod(index)
        },
        contentDescription = "Remove initialization method",
        hint = Size(16),
      ) {
        Text(
          text = "Remove initialization method",
        )
      }
    }
    Dropdown(
      modifier = Modifier.padding(4.dp),
      menuContent = {
        InitializationMethodMenu.entries.forEach { menu ->
          selectableItem(
            selected = initializeMethod::class == menu.defaultContent::class,
            onClick = {
              val newInitializeMethods = initializeMethods.toMutableList()
              newInitializeMethods[index] = menu.defaultContent
              scenarioStateHolder.onInitializationMethodChanged(index, menu.defaultContent)
            }
          ) {
            Text(menu.type)
          }
        }
      }
    ) {
      Text(
        InitializationMethodMenu.entries.firstOrNull { it.defaultContent::class == initializeMethod::class }?.type
          ?: "Select type"
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (initializeMethod is ArbigentScenarioContent.InitializationMethod.CleanupData) {
        TextField(
          modifier = Modifier
            .testTag("cleanup_pacakge")
            .padding(4.dp),
          value = (initializeMethod as? ArbigentScenarioContent.InitializationMethod.CleanupData)?.packageName
            ?: "",
          onValueChange = {
            scenarioStateHolder.onInitializationMethodChanged(
              index,
              ArbigentScenarioContent.InitializationMethod.CleanupData(it)
            )
          }
        )
      }
      Column {
        if (initializeMethod is ArbigentScenarioContent.InitializationMethod.Back) {
          var editingText by remember(initializeMethod) {
            mutableStateOf(
              (initializeMethod as? ArbigentScenarioContent.InitializationMethod.Back)?.times.toString()
            )
          }
          TextField(
            modifier = Modifier
              .padding(4.dp),
            value = editingText,
            placeholder = { Text("Times") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            onValueChange = {
              editingText = it
              scenarioStateHolder.onInitializationMethodChanged(
                index,
                ArbigentScenarioContent.InitializationMethod.Back(it.toIntOrNull() ?: 1)
              )
            },
          )
        }
      }
      Row {
        if (initializeMethod is ArbigentScenarioContent.InitializationMethod.Wait) {
          var editingText by remember(initializeMethod) {
            mutableStateOf(
              (initializeMethod as? ArbigentScenarioContent.InitializationMethod.Wait)?.durationMs.toString()
            )
          }
          TextField(
            modifier = Modifier
              .padding(4.dp),
            value = editingText,
            placeholder = { Text("Duration(ms)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            onValueChange = {
              editingText = it
              scenarioStateHolder.onInitializationMethodChanged(
                index,
                ArbigentScenarioContent.InitializationMethod.Wait(it.toLongOrNull() ?: 0)
              )
            },
          )
          Text(
            "ms",
            modifier = Modifier.align(Alignment.CenterVertically)
          )
        }
      }
      Column {
        if (initializeMethod is ArbigentScenarioContent.InitializationMethod.OpenLink) {
          TextField(
            modifier = Modifier
              .padding(4.dp),
            value = (initializeMethod as? ArbigentScenarioContent.InitializationMethod.OpenLink)?.link
              ?: "",
            onValueChange = {
              scenarioStateHolder.onInitializationMethodChanged(
                index,
                ArbigentScenarioContent.InitializationMethod.OpenLink(it)
              )
            },
          )
        }
      }
      var editingText by remember(initializeMethod) {
        mutableStateOf(
          (initializeMethod as? ArbigentScenarioContent.InitializationMethod.LaunchApp)?.packageName
            ?: ""
        )
      }
      LaunchAppInitializationSetting(
        initializeMethod = initializeMethod,
        editingText = editingText,
        onPackageChange = {
          editingText = it
          scenarioStateHolder.onInitializationMethodChanged(
            index,
            ArbigentScenarioContent.InitializationMethod.LaunchApp(
              packageName = it,
              launchArguments = (initializeMethod as? ArbigentScenarioContent.InitializationMethod.LaunchApp)?.launchArguments
                ?: emptyMap()
            )
          )
        },
        onArgumentsChange = { newArguments ->
          scenarioStateHolder.onInitializationMethodChanged(
            index,
            ArbigentScenarioContent.InitializationMethod.LaunchApp(
              packageName = editingText,
              launchArguments = newArguments
            )
          )
        },
        onArgumentRemove = { key ->
          val newArguments =
            (initializeMethod as? ArbigentScenarioContent.InitializationMethod.LaunchApp)?.launchArguments
              ?.toMutableMap() ?: mutableMapOf()
          newArguments.remove(key)
          scenarioStateHolder.onInitializationMethodChanged(
            index,
            ArbigentScenarioContent.InitializationMethod.LaunchApp(
              packageName = editingText,
              launchArguments = newArguments
            )
          )
        }
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LaunchAppInitializationSetting(
  initializeMethod: ArbigentScenarioContent.InitializationMethod,
  editingText: String,
  onPackageChange: (String) -> Unit,
  onArgumentsChange: (Map<String, ArbigentScenarioContent.InitializationMethod.LaunchApp.ArgumentValue>) -> Unit,
  onArgumentRemove: (String) -> Unit
) {
  Column {
    if (initializeMethod is ArbigentScenarioContent.InitializationMethod.LaunchApp) {
      TextField(
        modifier = Modifier.padding(4.dp).testTag("launch_app_package"),
        value = editingText,
        placeholder = { Text("Package name") },
        onValueChange = onPackageChange
      )
      val arguments by rememberUpdatedState(
        (initializeMethod as? ArbigentScenarioContent.InitializationMethod.LaunchApp)?.launchArguments
          ?: emptyMap()
      )
      arguments.forEach { (key, value: ArbigentScenarioContent.InitializationMethod.LaunchApp.ArgumentValue) ->
        Row {
          TextField(
            modifier = Modifier.weight(1f).padding(4.dp),
            value = key,
            placeholder = { Text("key") },
            onValueChange = { newKey ->
              val newArguments = arguments.toMutableMap()
              newArguments.remove(key)
              newArguments[newKey] = value
              onArgumentsChange(newArguments)
            }
          )
          Dropdown(
            modifier = Modifier.weight(1f).padding(4.dp),
            menuContent = {
              listOf("String", "Boolean", "Int").forEach { type ->
                selectableItem(
                  selected = value::class.simpleName == type,
                  onClick = {
                    val newArguments = arguments.toMutableMap()
                    newArguments[key] = when (type) {
                      "String" -> ArbigentScenarioContent.InitializationMethod.LaunchApp.ArgumentValue.StringVal(
                        ""
                      )

                      "Boolean" -> ArbigentScenarioContent.InitializationMethod.LaunchApp.ArgumentValue.BooleanVal(
                        false
                      )

                      "Int" -> ArbigentScenarioContent.InitializationMethod.LaunchApp.ArgumentValue.IntVal(
                        0
                      )

                      else -> value
                    }
                    onArgumentsChange(newArguments)
                  }
                ) {
                  Text(type)
                }
              }
            }
          ) {
            Text(value.value::class.simpleName ?: "Select type")
          }
          if (value is ArbigentScenarioContent.InitializationMethod.LaunchApp.ArgumentValue.BooleanVal) {
            CheckboxRow(
              text = "Value",
              checked = value.value,
              onCheckedChange = {
                val newArguments = arguments.toMutableMap()
                newArguments[key] =
                  ArbigentScenarioContent.InitializationMethod.LaunchApp.ArgumentValue.BooleanVal(it)
                onArgumentsChange(newArguments)
              }
            )
          } else {
            TextField(
              modifier = Modifier.weight(1f).padding(4.dp),
              value = value.value.toString(),
              placeholder = { Text("value") },
              onValueChange = { newValue ->
                val newArguments = arguments.toMutableMap()
                newArguments[key] = when (value) {
                  is ArbigentScenarioContent.InitializationMethod.LaunchApp.ArgumentValue.StringVal -> ArbigentScenarioContent.InitializationMethod.LaunchApp.ArgumentValue.StringVal(
                    newValue
                  )

                  is ArbigentScenarioContent.InitializationMethod.LaunchApp.ArgumentValue.IntVal -> ArbigentScenarioContent.InitializationMethod.LaunchApp.ArgumentValue.IntVal(
                    newValue.toIntOrNull() ?: 0
                  )

                  else -> value
                }
                onArgumentsChange(newArguments)
              }
            )
          }
          IconActionButton(
            key = AllIconsKeys.General.Delete,
            onClick = {
              onArgumentRemove(key)
            },
            contentDescription = "Remove argument",
            hint = Size(16)
          ) {
            Text("Remove argument")
          }
        }
      }
      IconActionButton(
        key = AllIconsKeys.General.Add,
        onClick = {
          val newArguments = arguments.toMutableMap()
          newArguments[""] =
            ArbigentScenarioContent.InitializationMethod.LaunchApp.ArgumentValue.StringVal("")
          onArgumentsChange(newArguments)
        },
        contentDescription = "Add argument",
        hint = Size(16)
      ) {
        Text("Add argument")
      }
    }
  }
}

data class ScenarioSection(val goal: String, val isRunning: Boolean, val steps: List<StepItem>) {
  fun isAchieved(): Boolean {
    return steps.any { it.isAchieved() }
  }
}

data class StepItem(val step: ArbigentContextHolder.Step) {
  fun isAchieved(): Boolean {
    return step.agentAction is GoalAchievedAgentAction
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

private fun formatTimestamp(timestamp: Long): String {
  val date = java.time.Instant.ofEpochMilli(timestamp)
  return date.toString().replace('T', ' ').substringBefore('.')
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContentPanel(
  tasksToAgentHistory: List<List<ArbigentTaskAssignment>>,
  stepFeedbacks: Set<StepFeedback>,
  onStepFeedback: (StepFeedbackEvent) -> Unit,
  modifier: Modifier
) {
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
              if (section.isAchieved()) {
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
                  text = "Step ${stepIndex + 1} (${formatTimestamp(step.timestamp)})",
                )
                if (step.cacheHit) {
                  Text(
                    "Cache hit",
                    modifier = Modifier.padding(4.dp)
                      .background(JewelTheme.colorPalette.purple(8))
                  )
                }
                if (step.agentAction is ExecuteMcpToolAgentAction) {
                  Icon(
                    key = AllIconsKeys.Run.Widget.Build,
                    contentDescription = "MCP",
                    modifier = Modifier.padding(4.dp).align(Alignment.CenterVertically),
                    hint = Size(12),
                    tint = JewelTheme.colorPalette.purple(1)
                  )
                  Text(
                    "MCP",
                    modifier = Modifier.padding(4.dp)
                      .background(JewelTheme.colorPalette.purple(8))
                  )
                }
                if (step.isFailed()) {
                  Icon(
                    key = AllIconsKeys.General.Error,
                    contentDescription = "Failed",
                    modifier = Modifier.padding(4.dp).align(Alignment.CenterVertically),
                    hint = Size(12)
                  )
                } else if (item.isAchieved()) {
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
              // AI feedback button
              if (step.apiCallJsonLFilePath != null) {
                Row {
                  // Good feedback button
                  val isGood =
                    stepFeedbacks.any { it is StepFeedback.Good && it.stepId == step.stepId }
                  val feedbackHintText =
                    "Feedback data is stored locally in project result files(result.yaml). You can later use these evaluations to:\n" +
                      "         • Fine-tune AI models\n" +
                      "         • Optimize prompt sequences\n" +
                      "         • Analyze response patterns \n" +
                      "No data leaves your environment."
                  IconActionButton(
                    key = AllIconsKeys.Ide.Like,
                    onClick = {
                      onStepFeedback(
                        if (isGood) {
                          StepFeedbackEvent.RemoveGood(step.stepId)
                        } else {
                          StepFeedback.Good(step.stepId)
                        }
                      )
                    },
                    colorFilter = if (isGood) {
                      ColorFilter.tint(JewelTheme.colorPalette.green(8))
                    } else {
                      null
                    },
                    contentDescription = feedbackHintText,
                    hint = Size(16),
                  ) {
                    Text(
                      text = feedbackHintText,
                    )
                  }
                  // Bad feedback button
                  val isBad =
                    stepFeedbacks.any { it is StepFeedback.Bad && it.stepId == step.stepId }
                  IconActionButton(
                    key = AllIconsKeys.Ide.Dislike,
                    onClick = {
                      onStepFeedback(
                        if (isBad) {
                          StepFeedbackEvent.RemoveBad(step.stepId)
                        } else {
                          StepFeedback.Bad(step.stepId)
                        }
                      )
                    },
                    colorFilter = if (isBad) {
                      ColorFilter.tint(JewelTheme.colorPalette.red(8))
                    } else {
                      null
                    },
                    contentDescription = feedbackHintText,
                    hint = Size(16),
                  ) {
                    Text(
                      text = feedbackHintText,
                    )
                  }
                }
              }
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
            ExpandableSection(
              "All UI Tree(length=${it.allTreeString.length})",
              modifier = Modifier.fillMaxWidth()
            ) {
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
            ExpandableSection(
              "Optimized UI Tree(length=${it.optimizedTreeString.length})",
              modifier = Modifier.fillMaxWidth()
            ) {
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
          val filePath = File(step.screenshotFilePath).getAnnotatedFilePath()
          if (File(filePath).exists()) {
            ExpandableSection(
              title = "Annotated Screenshot",
              defaultExpanded = true,
              modifier = Modifier.fillMaxWidth()
            ) {
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
    contentDescription = "Achieved",
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
          contentDescription = "Collapse $title",
          hint = Size(28)
        )
      } else {
        Icon(
          key = AllIconsKeys.General.ArrowRight,
          contentDescription = "Expand $title",
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
