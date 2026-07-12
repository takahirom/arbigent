package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Value/callback-style text field on top of Jewel's state-based [TextField] (the
 * `value`/`onValueChange` overload was removed from Jewel). Edits flow one way, from the
 * field into [onValueChange]; [resetKey] recreates the field when the backing model is
 * replaced from outside (e.g. a Browse pick changing the row's target).
 */
@Composable
internal fun ValueTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String? = null,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  resetKey: Any? = null,
) {
  val textFieldState = remember(resetKey) { TextFieldState(value) }
  val currentValue by rememberUpdatedState(value)
  val currentOnValueChange by rememberUpdatedState(onValueChange)
  LaunchedEffect(textFieldState) {
    snapshotFlow { textFieldState.text.toString() }.collect { newValue ->
      if (newValue != currentValue) {
        currentOnValueChange(newValue)
      }
    }
  }
  TextField(
    state = textFieldState,
    placeholder = placeholder?.let { { Text(it) } },
    keyboardOptions = keyboardOptions,
    modifier = modifier,
  )
}
