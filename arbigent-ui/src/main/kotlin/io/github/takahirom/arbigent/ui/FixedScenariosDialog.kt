package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.FixedScenario
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalFoundationApi::class, ExperimentalUuidApi::class)
@Composable
fun FixedScenariosDialog(
    appStateHolder: ArbigentAppStateHolder,
    onCloseRequest: () -> Unit,
    onScenarioSelected: (String) -> Unit
) {
    val fixedScenarios = appStateHolder.fixedScenariosFlow.collectAsState().value
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedScenarioId by remember { mutableStateOf<String?>(null) }
    var selectedScenarioTitle by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current

    TestCompatibleDialog(
        onCloseRequest = onCloseRequest,
        title = "Maestro YAML Scenarios",
        content = {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth().fillMaxHeight(0.8f)
            ) {
                // List of fixed scenarios
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    items(fixedScenarios) { scenario ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButtonRow(
                                text = "",
                                selected = scenario.id == selectedScenarioId,
                                onClick = {
                                    selectedScenarioId = scenario.id
                                    selectedScenarioTitle = scenario.title
                                },
                                modifier = Modifier.testTag("select_scenario_${scenario.id}")
                            )
                            Column(
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            ) {
                                Text(
                                    text = scenario.title,
                                    modifier = Modifier.testTag("scenario_title_${scenario.id}")
                                )
                                Text(
                                    text = scenario.description,
                                    modifier = Modifier.testTag("scenario_description_${scenario.id}")
                                )
                            }
                            IconActionButton(
                                key = AllIconsKeys.Actions.Copy,
                                onClick = { 
                                    clipboardManager.setText(buildAnnotatedString { append(scenario.yamlText) })
                                },
                                contentDescription = "Copy YAML",
                                hint = Size(16),
                                modifier = Modifier.testTag("copy_yaml_${scenario.id}")
                            )
                            IconActionButton(
                                key = AllIconsKeys.General.Delete,
                                onClick = { appStateHolder.removeFixedScenario(scenario.id) },
                                contentDescription = "Delete scenario",
                                hint = Size(16),
                                modifier = Modifier.testTag("delete_scenario_${scenario.id}")
                            )
                        }
                        Divider(orientation = Orientation.Horizontal)
                    }
                }

                // Buttons
                Row(
                    modifier = Modifier.padding(8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ActionButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.testTag("add_scenario_button")
                    ) {
                        Text("Add Scenario")
                    }

                    Row {
                        ActionButton(
                            onClick = {
                                selectedScenarioId?.let { onScenarioSelected(it) }
                                onCloseRequest()
                            },
                            enabled = selectedScenarioId != null,
                            modifier = Modifier.padding(end = 8.dp).testTag("select_button")
                        ) {
                            Text("Select")
                        }

                        ActionButton(
                            onClick = onCloseRequest,
                            modifier = Modifier.testTag("close_button")
                        ) {
                            Text("Close")
                        }
                    }
                }
            }

            // Add scenario dialog
            if (showAddDialog) {
                AddFixedScenarioDialog(
                    onCloseRequest = { showAddDialog = false },
                    onAdd = { title, description, yamlText ->
                        val newScenario = FixedScenario(
                            id = Uuid.random().toString(),
                            title = title,
                            description = description,
                            yamlText = yamlText
                        )
                        appStateHolder.addFixedScenario(newScenario)
                        showAddDialog = false
                    }
                )
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AddFixedScenarioDialog(
    onCloseRequest: () -> Unit,
    onAdd: (title: String, description: String, yamlText: String) -> Unit
) {
    val titleState = remember { TextFieldState("") }
    val descriptionState = remember { TextFieldState("") }
    val yamlTextState = remember { TextFieldState("") }

    TestCompatibleDialog(
        onCloseRequest = onCloseRequest,
        title = "Add Maestro YAML Scenario",
        content = {
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    // Title
                    GroupHeader("Title")
                    TextField(
                        state = titleState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .testTag("scenario_title_input"),
                        placeholder = { Text("Enter scenario title") }
                    )

                    // Description
                    GroupHeader("Description")
                    TextField(
                        state = descriptionState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .testTag("scenario_description_input"),
                        placeholder = { Text("Enter scenario description") }
                    )

                    // YAML Text
                    GroupHeader("YAML Text")
                    TextArea(
                        state = yamlTextState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(8.dp)
                            .testTag("scenario_yaml_input"),
                        placeholder = { Text("Enter Maestro YAML content") }
                    )
                }

                // Buttons
                Row(
                    modifier = Modifier.padding(8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    ActionButton(
                        onClick = {
                            onAdd(
                                titleState.text.toString(),
                                descriptionState.text.toString(),
                                yamlTextState.text.toString()
                            )
                        },
                        enabled = titleState.text.isNotEmpty() && yamlTextState.text.isNotEmpty(),
                        modifier = Modifier.padding(end = 8.dp).testTag("add_button")
                    ) {
                        Text("Add")
                    }

                    ActionButton(
                        onClick = onCloseRequest,
                        modifier = Modifier.testTag("cancel_button")
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    )
}
