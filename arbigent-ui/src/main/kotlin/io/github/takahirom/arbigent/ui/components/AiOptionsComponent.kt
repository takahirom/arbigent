package io.github.takahirom.arbigent.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentAiOptions
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text

@Composable
fun AiOptionsComponent(
    currentOptions: ArbigentAiOptions,
    onOptionsChanged: (ArbigentAiOptions) -> Unit,
    modifier: Modifier = Modifier
) {
    val updatedOptions by rememberUpdatedState(currentOptions)
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = updatedOptions.temperature != null,
            onCheckedChange = { enabled: Boolean ->
                onOptionsChanged(
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
                onOptionsChanged(
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
}