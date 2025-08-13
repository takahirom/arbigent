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
  editingProvider: AiProviderSetting? = null,
  onCloseRequest: () -> Unit
) {
  val isEditMode = editingProvider != null
  TestCompatibleDialog(
    onCloseRequest = onCloseRequest,
    title = if (isEditMode) "Edit AI Provider" else "Add New AI Provider",
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

          var selectedType by remember { 
            mutableStateOf(
              when (editingProvider) {
                is AiProviderSetting.OpenAi -> "OpenAi"
                is AiProviderSetting.Gemini -> "Gemini"
                is AiProviderSetting.CustomOpenAiApiBasedAi -> "CustomOpenAiApiBasedAi"
                is AiProviderSetting.AzureOpenAi -> "AzureOpenAi"
                else -> "OpenAi"
              }
            )
          }

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
                  if (!isEditMode) selectedType = "OpenAi"
                },
                enabled = !isEditMode
              )
            }
            Row(
              verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
              RadioButtonRow(
                text = "Gemini",
                selected = selectedType == "Gemini",
                onClick = {
                  if (!isEditMode) selectedType = "Gemini"
                },
                enabled = !isEditMode
              )
            }
            Row(
              verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
              RadioButtonRow(
                text = "Custom OpenAI API Based AI",
                selected = selectedType == "CustomOpenAiApiBasedAi",
                onClick = {
                  if (!isEditMode) selectedType = "CustomOpenAiApiBasedAi"
                },
                enabled = !isEditMode
              )
            }
            Row(
              verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
              RadioButtonRow(
                text = "Azure OpenAI",
                selected = selectedType == "AzureOpenAi",
                onClick = {
                  if (!isEditMode) selectedType = "AzureOpenAi"
                },
                enabled = !isEditMode
              )
            }
          }

          GroupHeader("Provider ID")
          val idState = remember { 
            TextFieldState(editingProvider?.id ?: selectedType) 
          }

          // Update ID state when selected type changes (only in add mode)
          LaunchedEffect(selectedType) {
            if (!isEditMode) {
              idState.edit { replace(0, length, selectedType) }
            }
          }

          TextField(
            state = idState,
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            placeholder = { Text("Enter a unique ID for this provider") },
            enabled = !isEditMode
          )

          // Check if ID already exists
          val existingIds = aiSettingStateHolder.aiSetting.aiSettings.map { it.id }
          val idText = idState.text.toString()
          val isIdEmpty = idText.isEmpty()
          val isIdDuplicate = if (isEditMode) {
            // In edit mode, only check for duplicates with other providers (exclude current provider)
            existingIds.filter { it != editingProvider?.id }.contains(idText)
          } else {
            existingIds.contains(idText)
          }
          val isIdValid = !isIdEmpty && !isIdDuplicate

          if (!isIdValid && !isIdEmpty) {
            Text(
              text = if (isIdDuplicate) "Error: ID already exists. Please choose a different ID." else "ID must not be empty",
              color = Color.Red,
              modifier = Modifier.padding(8.dp)
            )
          }

          GroupHeader("Model Name")
          val modelNameState = remember { 
            TextFieldState(
              when (editingProvider) {
                is AiProviderSetting.OpenAi -> editingProvider.modelName
                is AiProviderSetting.Gemini -> editingProvider.modelName
                is AiProviderSetting.CustomOpenAiApiBasedAi -> editingProvider.modelName
                is AiProviderSetting.AzureOpenAi -> editingProvider.modelName
                else -> ""
              }
            )
          }
          TextField(
            state = modelNameState,
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            placeholder = { Text("Enter model name (e.g., gpt-4.1)") }
          )

          GroupHeader("API Key")
          val apiKeyState = remember { 
            TextFieldState(
              when (editingProvider) {
                is AiProviderSetting.OpenAi -> editingProvider.apiKey
                is AiProviderSetting.Gemini -> editingProvider.apiKey
                is AiProviderSetting.CustomOpenAiApiBasedAi -> editingProvider.apiKey
                is AiProviderSetting.AzureOpenAi -> editingProvider.apiKey
                else -> ""
              }
            )
          }
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
              val baseUrlState = remember { 
                TextFieldState(
                  (editingProvider as? AiProviderSetting.CustomOpenAiApiBasedAi)?.baseUrl ?: ""
                )
              }
              val baseUrlText = baseUrlState.text.toString()
              val isBaseUrlEndsWithSlash = baseUrlText.isEmpty() || baseUrlText.endsWith("/")

              TextField(
                state = baseUrlState,
                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                placeholder = { Text("Enter base URL (e.g., http://localhost:11434/v1/)") }
              )

              if (!isBaseUrlEndsWithSlash) {
                Text(
                  text = "Warning: URL should end with a slash (/)",
                  color = Color(0xFFF57C00), // Orange warning color
                  modifier = Modifier.padding(horizontal = 8.dp)
                )
              }

              // Add/Update button
              OutlinedButton(
                onClick = {
                  if (isIdValid && modelNameState.text.isNotEmpty() && baseUrlState.text.isNotEmpty()) {
                    // Ensure URL ends with a slash
                    var baseUrl = baseUrlState.text.toString()
                    if (!baseUrl.endsWith("/")) {
                      baseUrl += "/"
                    }

                    val provider = AiProviderSetting.CustomOpenAiApiBasedAi(
                      id = idState.text.toString(),
                      apiKey = apiKeyState.text.toString(),
                      modelName = modelNameState.text.toString(),
                      baseUrl = baseUrl
                    )
                    
                    if (isEditMode) {
                      aiSettingStateHolder.updateAiProvider(provider)
                    } else {
                      aiSettingStateHolder.addAiProvider(provider)
                    }
                    onCloseRequest()
                  }
                },
                enabled = isIdValid && modelNameState.text.isNotEmpty() && baseUrlState.text.isNotEmpty(),
                modifier = Modifier.padding(8.dp)
              ) {
                Text(if (isEditMode) "Update Provider" else "Add Provider")
              }
            }

            "AzureOpenAi" -> {
              GroupHeader("Endpoint")
              val endpointState = remember { 
                TextFieldState(
                  (editingProvider as? AiProviderSetting.AzureOpenAi)?.endpoint ?: ""
                )
              }
              val endpointText = endpointState.text.toString()
              val isEndpointEndsWithSlash = endpointText.isEmpty() || endpointText.endsWith("/")

              TextField(
                state = endpointState,
                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                placeholder = { Text("Enter endpoint URL (e.g., https://{endpoint}/openai/deployments/{deployment-id}/)") }
              )

              if (!isEndpointEndsWithSlash) {
                Text(
                  text = "Warning: URL should end with a slash (/)",
                  color = Color(0xFFF57C00), // Orange warning color
                  modifier = Modifier.padding(horizontal = 8.dp)
                )
              }

              GroupHeader("API Version")
              val apiVersionState = remember { 
                TextFieldState(
                  (editingProvider as? AiProviderSetting.AzureOpenAi)?.apiVersion ?: "2025-01-01-preview"
                )
              }
              TextField(
                state = apiVersionState,
                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                placeholder = { Text("Enter API version") }
              )

              // Add/Update button
              OutlinedButton(
                onClick = {
                  if (isIdValid && modelNameState.text.isNotEmpty() &&
                    endpointState.text.isNotEmpty() && apiVersionState.text.isNotEmpty()
                  ) {
                    // Ensure URL ends with a slash
                    var endpoint = endpointState.text.toString()
                    if (!endpoint.endsWith("/")) {
                      endpoint += "/"
                    }

                    val provider = AiProviderSetting.AzureOpenAi(
                      id = idState.text.toString(),
                      apiKey = apiKeyState.text.toString(),
                      modelName = modelNameState.text.toString(),
                      endpoint = endpoint,
                      apiVersion = apiVersionState.text.toString()
                    )
                    
                    if (isEditMode) {
                      aiSettingStateHolder.updateAiProvider(provider)
                    } else {
                      aiSettingStateHolder.addAiProvider(provider)
                    }
                    onCloseRequest()
                  }
                },
                enabled = isIdValid && modelNameState.text.isNotEmpty() &&
                  endpointState.text.isNotEmpty() && apiVersionState.text.isNotEmpty(),
                modifier = Modifier.padding(8.dp)
              ) {
                Text(if (isEditMode) "Update Provider" else "Add Provider")
              }
            }

            else -> { // OpenAi or Gemini
              // Add/Update button
              OutlinedButton(
                onClick = {
                  if (isIdValid && modelNameState.text.isNotEmpty()) {
                    val provider = if (selectedType == "OpenAi") {
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
                    
                    if (isEditMode) {
                      aiSettingStateHolder.updateAiProvider(provider)
                    } else {
                      aiSettingStateHolder.addAiProvider(provider)
                    }
                    onCloseRequest()
                  }
                },
                enabled = isIdValid && modelNameState.text.isNotEmpty(),
                modifier = Modifier.padding(8.dp)
              ) {
                Text(if (isEditMode) "Update Provider" else "Add Provider")
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
