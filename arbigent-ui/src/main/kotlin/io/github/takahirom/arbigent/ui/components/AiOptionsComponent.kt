package io.github.takahirom.arbigent.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentAiOptions
import io.github.takahirom.arbigent.ImageDetailLevel
import io.github.takahirom.arbigent.ImageFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.theme.colorPalette
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import org.jetbrains.jewel.ui.theme.simpleListItemStyle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AiOptionsComponent(
    currentOptions: ArbigentAiOptions,
    onOptionsChanged: (ArbigentAiOptions) -> Unit,
    modifier: Modifier = Modifier
) {
    val updatedOptions by rememberUpdatedState(currentOptions)
    val updatedOptionsChanged by rememberUpdatedState(onOptionsChanged)
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = updatedOptions.temperature != null,
            onCheckedChange = { enabled: Boolean ->
                updatedOptionsChanged(
                    updatedOptions.copy(temperature = if (enabled) 0.7 else null)
                )
            }
        )
        Text("Use Temperature", modifier = Modifier.padding(start = 8.dp))
    }
    updatedOptions.temperature?.let { temp ->
        Text("Temperature (0.0 - 1.0)", modifier = Modifier.padding(horizontal = 8.dp))
        Slider(
            value = temp.toFloat(),
            onValueChange = { newTemperature ->
                updatedOptionsChanged(
                    updatedOptions.copy(temperature = newTemperature.toDouble())
                )
            },
            valueRange = 0f..1f,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Text(
            text = String.format("%.2f", temp),
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
    Row(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = updatedOptions.imageDetail != null,
            onCheckedChange = { enabled: Boolean ->
                updatedOptionsChanged(
                    updatedOptions.copy(imageDetail = if (enabled) ImageDetailLevel.HIGH else null)
                )
            },
            modifier = Modifier.testTag("image_detail_checkbox")
        )
        Text("Use Image Detail", modifier = Modifier.padding(start = 8.dp))
    }
    updatedOptions.imageDetail?.let { detail ->
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Image Detail Level", modifier = Modifier.padding(end = 8.dp))
            val uriHandler = LocalUriHandler.current
            IconActionButton(
                key = AllIconsKeys.General.Information,
                onClick = {
                      uriHandler.openUri(uri = "https://platform.openai.com/docs/guides/vision#low-or-high-fidelity-image-understanding")
                },
                modifier = Modifier.testTag("image_detail_level_info"),
                contentDescription = "Image Detail Level Info",
                hint = Size(16)
            ) {
                Text("The image detail level setting allows you to control how the model processes images and generates textual interpretations. For smartphone screenshots, this setting is frequently set to high by default, so adjustment is typically unnecessary.")
            }
            ComboBox(
                labelText = detail.name.lowercase(),
                maxPopupHeight = 150.dp,
                modifier = Modifier
                    .width(100.dp)
                    .testTag("image_detail_level_combo")
            ) {
                Column {
                    ImageDetailLevel.entries.forEach { item ->
                        val isSelected = item == detail
                        val isItemHovered = false
                        val isPreviewSelection = false
                        SimpleListItem(
                            text = item.name.lowercase(),
                            state = ListItemState(isSelected, isItemHovered, isPreviewSelection),
                            modifier = Modifier
                                .testTag("image_detail_level_item_${item.name.lowercase()}")
                                .clickable {
                                    updatedOptionsChanged(
                                        updatedOptions.copy(imageDetail = item)
                                    )
                                },
                            style = JewelTheme.simpleListItemStyle,
                            contentDescription = item.name.lowercase()
                        )
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = updatedOptions.imageFormat != null,
            onCheckedChange = { enabled: Boolean ->
                updatedOptionsChanged(
                    updatedOptions.copy(imageFormat = if (enabled) ImageFormat.PNG else null)
                )
            },
            modifier = Modifier.testTag("image_format_checkbox")
        )
        Text("Use Custom Image Format", modifier = Modifier.padding(start = 8.dp))
    }
    if (updatedOptions.imageFormat != null) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Image Format", modifier = Modifier.padding(end = 8.dp))
            ComboBox(
                labelText = updatedOptions.imageFormat?.name?.lowercase()?.replace("_", " ") ?: "png",
                maxPopupHeight = 150.dp,
                modifier = Modifier
                    .width(150.dp)
                    .testTag("image_format_combo")
            ) {
                Column {
                    listOf(ImageFormat.PNG, ImageFormat.WEBP, ImageFormat.LOSSY_WEBP).forEach { item ->
                        val isSelected = item == updatedOptions.imageFormat
                        val isItemHovered = false
                        val isPreviewSelection = false
                        SimpleListItem(
                            text = item.name.lowercase().replace("_", " "),
                            state = ListItemState(isSelected, isItemHovered, isPreviewSelection),
                            modifier = Modifier
                                .testTag("image_format_item_${item.name.lowercase()}")
                                .clickable {
                                    updatedOptionsChanged(
                                        updatedOptions.copy(imageFormat = item)
                                    )
                                },
                            style = JewelTheme.simpleListItemStyle,
                            contentDescription = item.name.lowercase().replace("_", " ")
                        )
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = updatedOptions.historicalStepLimit != null,
            onCheckedChange = { enabled: Boolean ->
                updatedOptionsChanged(
                    updatedOptions.copy(historicalStepLimit = if (enabled) 100 else null)
                )
            },
            modifier = Modifier.testTag("use_historical_step_limit")
        )
        Text("Use Historical Step Limit", modifier = Modifier.padding(start = 8.dp))
    }
    updatedOptions.historicalStepLimit?.let { limit ->
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Historical Step Limit", modifier = Modifier.padding(end = 8.dp))
            val textFieldState = rememberTextFieldState(limit.toString())
            LaunchedEffect(textFieldState.text) {
                textFieldState.text.toString().toIntOrNull()?.let { intValue ->
                    if (intValue > 0) {
                        updatedOptionsChanged(
                            updatedOptions.copy(historicalStepLimit = intValue)
                        )
                    }
                }
            }
            TextField(
                state = textFieldState,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .width(100.dp)
                    .testTag("historical_step_limit")
            )
        }
    }

    Row(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = updatedOptions.extraBody != null,
            onCheckedChange = { enabled: Boolean ->
                updatedOptionsChanged(
                    updatedOptions.copy(extraBody = if (enabled) JsonObject(emptyMap()) else null)
                )
            },
            modifier = Modifier.testTag("use_extra_request_params")
        )
        Text("Use Extra Body", modifier = Modifier.padding(start = 8.dp))
    }
    if (updatedOptions.extraBody != null) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Extra Body (JSON)", modifier = Modifier.padding(end = 8.dp))
            val uriHandler = LocalUriHandler.current
            IconActionButton(
                key = AllIconsKeys.General.Information,
                onClick = {
                    uriHandler.openUri(uri = "https://platform.openai.com/docs/api-reference/chat/create")
                },
                modifier = Modifier.testTag("extra_request_params_info"),
                contentDescription = "Extra Request Params Info",
                hint = Size(16)
            ) {
                Text("Add custom JSON fields to the API request body. For example: {\"reasoning_effort\": \"high\"} for OpenAI o3/5.1 models.")
            }
        }
        var jsonParseError by remember { mutableStateOf<String?>(null) }
        val textFieldState = rememberTextFieldState(
            updatedOptions.extraBody?.toString() ?: "{}"
        )
        // Sync TextField when extraBody changes externally (e.g., switching scenarios)
        LaunchedEffect(updatedOptions.extraBody) {
            val newText = updatedOptions.extraBody?.toString() ?: "{}"
            if (textFieldState.text.toString() != newText) {
                textFieldState.edit {
                    replace(0, length, newText)
                }
            }
        }
        LaunchedEffect(textFieldState.text) {
            val text = textFieldState.text.toString()
            if (text.isBlank() || text == "{}") {
                jsonParseError = null
                updatedOptionsChanged(
                    updatedOptions.copy(extraBody = JsonObject(emptyMap()))
                )
            } else {
                try {
                    val parsed = Json.parseToJsonElement(text)
                    if (parsed is JsonObject) {
                        jsonParseError = null
                        updatedOptionsChanged(
                            updatedOptions.copy(extraBody = parsed)
                        )
                    } else {
                        jsonParseError = "Expected a JSON object, not ${parsed::class.simpleName}"
                    }
                } catch (e: Exception) {
                    jsonParseError = e.message ?: "Invalid JSON"
                }
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                state = textFieldState,
                modifier = Modifier
                    .width(300.dp)
                    .testTag("extra_request_params")
            )
            jsonParseError?.let { error ->
                IconActionButton(
                    key = AllIconsKeys.General.Warning,
                    onClick = {},
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .testTag("extra_request_params_error"),
                    contentDescription = "JSON Parse Error",
                    hint = Size(16)
                ) {
                    Text(error)
                }
            }
        }
        if (jsonParseError != null) {
            Text(
                text = "Invalid JSON",
                color = JewelTheme.colorPalette.red(8),
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .testTag("extra_request_params_error_text")
            )
        }
    }
}
