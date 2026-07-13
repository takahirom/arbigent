package io.github.takahirom.arbigent.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.ListItemState
import org.jetbrains.jewel.ui.component.SimpleListItem
import org.jetbrains.jewel.ui.theme.simpleListItemStyle

/**
 * A selectable item inside a ComboBox popup. Carries contentDescription semantics,
 * which Jewel's SimpleListItem no longer exposes as a parameter.
 */
@Composable
fun ComboBoxItem(
  text: String,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  SimpleListItem(
    text = text,
    state = ListItemState(isSelected),
    modifier = modifier
      .semantics { contentDescription = text }
      .clickable(onClick = onClick),
    style = JewelTheme.simpleListItemStyle,
  )
}
