package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

@Composable
fun UnsavedChangesDialog(
  onSave: () -> Unit,
  onDiscard: () -> Unit,
  onCancel: () -> Unit
) {
  TestCompatibleDialog(
    onCloseRequest = onCancel,
    title = "Unsaved Changes",
    resizable = false,
    width = 400.dp,
    height = 150.dp,
    content = {
      Column(
        modifier = Modifier
          .padding(16.dp)
          .width(350.dp)
      ) {
        Text("You have unsaved changes. Do you want to save before closing?")
        Spacer(Modifier.height(24.dp))
        Row(
          horizontalArrangement = Arrangement.End,
          modifier = Modifier.fillMaxWidth()
        ) {
          OutlinedButton(onClick = onDiscard) {
            Text("Don't Save")
          }
          Spacer(Modifier.width(8.dp))
          OutlinedButton(onClick = onCancel) {
            Text("Cancel")
          }
          Spacer(Modifier.width(8.dp))
          DefaultButton(onClick = onSave) {
            Text("Save")
          }
        }
      }
    }
  )
}
