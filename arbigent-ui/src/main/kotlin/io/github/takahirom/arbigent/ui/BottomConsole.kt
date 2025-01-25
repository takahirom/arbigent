package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentFiles
import io.github.takahirom.arbigent.ArbigentGlobalStatus
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.arbigentLogFormatter
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import java.awt.Desktop
import java.time.ZoneId

@OptIn(ArbigentInternalApi::class)
@Composable
fun BottomConsole() {
  Row(
    modifier = Modifier.padding(8.dp).fillMaxWidth()
      .background(JewelTheme.globalColors.panelBackground)
  ) {
    var isExpanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    if (!isExpanded) {
      val globalStatus by ArbigentGlobalStatus.status.collectAsState(ArbigentGlobalStatus.status())
      Text(
        text = globalStatus,
        modifier = Modifier.weight(1f)
          .clickable { isExpanded = !isExpanded },
        maxLines = 1
      )
    } else {
      var consoleHeight by remember { mutableStateOf(300.dp) }
      Column(
        modifier = Modifier.weight(1f)
          .height(consoleHeight)
      ) {
        Divider(
          thickness = 8.dp,
          orientation = Orientation.Horizontal,
          modifier = Modifier.fillMaxWidth()
            .pointerInput(Unit) {
              detectDragGestures { change, dragAmount ->
                change.consume()
                consoleHeight -= dragAmount.y.toDp()
              }
            }
        )
        Row {
          Text(
            "Console",
            modifier = Modifier.weight(1f)
              .align(Alignment.CenterVertically)
          )
          IconActionButton(
            key = AllIconsKeys.Actions.Download,
            onClick = {
              Desktop.getDesktop().open(ArbigentFiles.logFile)
            },
            contentDescription = "Open log file",
            hint = Size(28)
          )
          IconActionButton(
            key = AllIconsKeys.Actions.Copy,
            onClick = {
              clipboardManager.setText(
                buildAnnotatedString {
                  append(ArbigentGlobalStatus.status())
                  append("\n")
                  ArbigentGlobalStatus.console().forEach {
                    append(it.first.toString())
                    append(" ")
                    append(it.second)
                    append("\n")
                  }
                }
              )
            },
            contentDescription = "Copy",
            hint = Size(28)
          )
          IconActionButton(
            key = AllIconsKeys.General.Close,
            onClick = { isExpanded = !isExpanded },
            contentDescription = "Close",
            hint = Size(28)
          )
        }
        val histories by ArbigentGlobalStatus.console.collectAsState(ArbigentGlobalStatus.console())
        val lazyColumnState = rememberLazyListState()
        LaunchedEffect(histories) {
          lazyColumnState.scrollToItem(histories.size - 1)
        }
        LazyColumn(
          state = lazyColumnState,
          modifier = Modifier.fillMaxSize()
        ) {
          items(histories) { (instant, status) ->
            Row(Modifier.padding(2.dp)) {
              Text(
                text = instant.atZone(ZoneId.systemDefault()).format(
                  arbigentLogFormatter
                ),
              )
              Text(
                text = "$status",
                modifier = Modifier.weight(1f)
                  .padding(start = 4.dp)
                  .clickable {
                    clipboardManager.setText(
                      buildAnnotatedString {
                        append(instant.atZone(ZoneId.systemDefault()).format(formatter))
                        append(" ")
                        append(status)
                      }
                    )
                  }
              )
            }
          }
        }
      }
    }
  }
}