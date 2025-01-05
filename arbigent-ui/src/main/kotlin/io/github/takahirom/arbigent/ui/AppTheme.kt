package io.github.takahirom.arbigent.ui

import androidx.compose.runtime.Composable
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.styling.TitleBarStyle


@Composable
fun AppTheme(content: @Composable () -> Unit) {
  IntUiTheme(
    theme = JewelTheme.lightThemeDefinition(),
    styling = ComponentStyling.default().decoratedWindow(
      titleBarStyle = TitleBarStyle.lightWithLightHeader()
    ),
    swingCompatMode = true,
  ) {
    content()
  }
}