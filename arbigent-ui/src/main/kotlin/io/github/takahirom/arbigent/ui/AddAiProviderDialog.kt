package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.intui.standalone.styling.light
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.component.styling.TextFieldStyle

@Composable
fun AddAiProviderDialog(
  aiSettingStateHolder: AiSettingStateHolder,
  onCloseRequest: () -> Unit
) {
  TestCompatibleDialog(
    onCloseRequest = onCloseRequest,
    title = "Add New AI Provider",
    content = {
      val scrollState = rememberScrollState()
      Column {
        Column(
          modifier = Modifier
            .padding(16.dp)
            .weight(1F)
            .verticalScroll(scrollState)
        ) {
          GroupHeader("AI Provider Type")

          var selectedType by remember { mutableStateOf("OpenAi") }

          Column(
            modifier = Modifier.padding(8.dp)
          ) {
            Row(
              verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
              RadioButtonRow(
                text = "OpenAI",
                selected = selectedType == "OpenAi",
                onClick = {
                  selectedType = "OpenAi"
                }
              )
            }
            Row(
              verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
              RadioButtonRow(
                text = "Gemini",
                selected = selectedType == "Gemini",
                onClick = {
                  selectedType = "Gemini"
                }
              )
            }
            Row(
              verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
              RadioButtonRow(
                text = "Custom OpenAI API Based AI",
                selected = selectedType == "CustomOpenAiApiBasedAi",
                onClick = {
                  selectedType = "CustomOpenAiApiBasedAi"
                }
              )
            }
            Row(
              verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
              RadioButtonRow(
                text = "Azure OpenAI",
                selected = selectedType == "AzureOpenAi",
                onClick = {
                  selectedType = "AzureOpenAi"
                }
              )
            }
          }

          GroupHeader("Provider ID")
          val idState = remember { TextFieldState(selectedType) }

          // Update ID state when selected type changes
          LaunchedEffect(selectedType) {
            idState.edit { replace(0, length, selectedType) }
          }

          TextField(
            state = idState,
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            placeholder = { Text("Enter a unique ID for this provider") }
          )

          // Check if ID already exists
          val existingIds = aiSettingStateHolder.aiSetting.aiSettings.map { it.id }
          val idText = idState.text.toString()
          val isIdEmpty = idText.isEmpty()
          val isIdDuplicate = existingIds.contains(idText)
          val isIdValid = !isIdEmpty && !isIdDuplicate

          if (!isIdValid && !isIdEmpty) {
            Text(
              text = if (isIdDuplicate) "Error: ID already exists. Please choose a different ID." else "ID must not be empty",
              color = Color.Red,
              modifier = Modifier.padding(8.dp)
            )
          }

          GroupHeader("Model Name")
          val modelNameState = remember { TextFieldState("") }
          TextField(
            state = modelNameState,
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            placeholder = { Text("Enter model name (e.g., gpt-4o-mini)") }
          )

          GroupHeader("API Key")
          val apiKeyState = remember { TextFieldState("") }
          BasicSecureTextField(
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            decorator = {
              Box(
                Modifier.background(color = TextFieldStyle.light().colors.background)
                  .padding(8.dp)
                  .clip(RoundedCornerShape(4.dp))
              ) {
                if (apiKeyState.text.isEmpty()) {
                  Text("Enter API Key (Saved in Keychain on Mac)")
                }
                it()
              }
            },
            state = apiKeyState,
          )

          // Additional fields based on selected type
          when (selectedType) {
            "CustomOpenAiApiBasedAi" -> {
              GroupHeader("Base URL")
              val baseUrlState = remember { TextFieldState("") }
              TextField(
                state = baseUrlState,
                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                placeholder = { Text("Enter base URL (e.g., http://localhost:11434/v1/)") }
              )

              // Add button
              OutlinedButton(
                onClick = {
                  if (isIdValid && modelNameState.text.isNotEmpty() && baseUrlState.text.isNotEmpty()) {
                    val newProvider = AiProviderSetting.CustomOpenAiApiBasedAi(
                      id = idState.text.toString(),
                      apiKey = apiKeyState.text.toString(),
                      modelName = modelNameState.text.toString(),
                      baseUrl = baseUrlState.text.toString()
                    )
                    aiSettingStateHolder.addAiProvider(newProvider)
                    onCloseRequest()
                  }
                },
                enabled = isIdValid && modelNameState.text.isNotEmpty() && baseUrlState.text.isNotEmpty(),
                modifier = Modifier.padding(8.dp)
              ) {
                Text("Add Provider")
              }
            }

            "AzureOpenAi" -> {
              GroupHeader("Endpoint")
              val endpointState = remember { TextFieldState("") }
              TextField(
                state = endpointState,
                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                placeholder = { Text("Enter endpoint URL") }
              )

              GroupHeader("API Version")
              val apiVersionState = remember { TextFieldState("2025-01-01-preview") }
              TextField(
                state = apiVersionState,
                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                placeholder = { Text("Enter API version") }
              )

              // Add button
              OutlinedButton(
                onClick = {
                  if (isIdValid && modelNameState.text.isNotEmpty() &&
                    endpointState.text.isNotEmpty() && apiVersionState.text.isNotEmpty()
                  ) {
                    val newProvider = AiProviderSetting.AzureOpenAi(
                      id = idState.text.toString(),
                      apiKey = apiKeyState.text.toString(),
                      modelName = modelNameState.text.toString(),
                      endpoint = endpointState.text.toString(),
                      apiVersion = apiVersionState.text.toString()
                    )
                    aiSettingStateHolder.addAiProvider(newProvider)
                    onCloseRequest()
                  }
                },
                enabled = isIdValid && modelNameState.text.isNotEmpty() &&
                  endpointState.text.isNotEmpty() && apiVersionState.text.isNotEmpty(),
                modifier = Modifier.padding(8.dp)
              ) {
                Text("Add Provider")
              }
            }

            else -> { // OpenAi or Gemini
              // Add button
              OutlinedButton(
                onClick = {
                  if (isIdValid && modelNameState.text.isNotEmpty()) {
                    val newProvider = if (selectedType == "OpenAi") {
                      AiProviderSetting.OpenAi(
                        id = idState.text.toString(),
                        apiKey = apiKeyState.text.toString(),
                        modelName = modelNameState.text.toString()
                      )
                    } else {
                      AiProviderSetting.Gemini(
                        id = idState.text.toString(),
                        apiKey = apiKeyState.text.toString(),
                        modelName = modelNameState.text.toString()
                      )
                    }
                    aiSettingStateHolder.addAiProvider(newProvider)
                    onCloseRequest()
                  }
                },
                enabled = isIdValid && modelNameState.text.isNotEmpty(),
                modifier = Modifier.padding(8.dp)
              ) {
                Text("Add Provider")
              }
            }
          }
        }

        // Cancel button
        Row(
          modifier = Modifier.padding(8.dp),
          horizontalArrangement = Arrangement.End
        ) {
          OutlinedButton(
            onClick = onCloseRequest,
            modifier = Modifier.padding(8.dp)
          ) {
            Text("Cancel")
          }
        }
      }
    }
  )
}
