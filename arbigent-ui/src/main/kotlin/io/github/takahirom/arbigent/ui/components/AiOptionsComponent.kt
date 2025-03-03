package io.github.takahirom.arbigent.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentAiOptions
import io.github.takahirom.arbigent.ImageDetailLevel
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
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
}
