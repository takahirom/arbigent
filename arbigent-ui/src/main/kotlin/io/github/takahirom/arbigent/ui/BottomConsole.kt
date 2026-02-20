package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentFiles
import io.github.takahirom.arbigent.ArbigentGlobalStatus
import io.github.takahirom.arbigent.ArbigentInternalApi
import java.time.format.DateTimeFormatter
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import java.awt.Desktop
import java.time.ZoneId

@OptIn(ArbigentInternalApi::class, ExperimentalFoundationApi::class)
@Composable
fun BottomConsole() {
  var isExpanded by remember { mutableStateOf(false) }
  val clipboardManager = LocalClipboardManager.current

  Column(Modifier.fillMaxWidth()) {
    Divider(
      orientation = Orientation.Horizontal,
      modifier = Modifier.fillMaxWidth(),
      thickness = 1.dp,
    )

    if (!isExpanded) {
      // Collapsed: single-line status bar
      val globalStatus by ArbigentGlobalStatus.status.collectAsState(ArbigentGlobalStatus.status())
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { isExpanded = true }
          .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          key = AllIconsKeys.General.ChevronUp,
          contentDescription = "Expand console",
          hint = Size(12),
          modifier = Modifier.padding(end = 6.dp),
        )
        Text(
          text = globalStatus,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          color = JewelTheme.globalColors.text.info,
        )
      }
    } else {
      // Expanded console
      var consoleHeight by remember { mutableStateOf(250.dp) }
      Column(
        modifier = Modifier.fillMaxWidth().height(consoleHeight),
      ) {
        // Drag handle to resize
        Divider(
          thickness = 4.dp,
          orientation = Orientation.Horizontal,
          modifier = Modifier.fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
              detectDragGestures { change, dragAmount ->
                change.consume()
                consoleHeight -= dragAmount.y.toDp()
              }
            }
        )

        // Console header + filter
        val queryState = rememberTextFieldState()
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            "Console",
            modifier = Modifier.padding(end = 8.dp),
          )
          TextField(
            state = queryState,
            modifier = Modifier.width(180.dp).padding(horizontal = 4.dp),
            placeholder = { Text("Filter...") },
          )
          Spacer(Modifier.weight(1f))
          IconActionButton(
            key = AllIconsKeys.Actions.Download,
            onClick = { Desktop.getDesktop().open(ArbigentFiles.logFile) },
            contentDescription = "Open log file",
            hint = Size(16),
          ) { Text("Open log file") }
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
            hint = Size(16),
          ) { Text("Copy log") }
          IconActionButton(
            key = AllIconsKeys.General.ChevronDown,
            onClick = { isExpanded = false },
            contentDescription = "Collapse",
            hint = Size(16),
          ) { Text("Collapse console") }
        }

        // Log entries
        val rawHistories by ArbigentGlobalStatus.console.collectAsState(ArbigentGlobalStatus.console())
        val histories by derivedStateOf {
          val hasFilter = queryState.text.isNotEmpty()
          rawHistories.filter {
            !hasFilter || it.second.contains(queryState.text)
          }
        }
        val lazyColumnState = rememberLazyListState()
        LaunchedEffect(histories) {
          lazyColumnState.scrollToItem(maxOf(histories.size - 1, 0))
        }
        LazyColumn(
          state = lazyColumnState,
          modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        ) {
          items(histories) { (instant, status) ->
            Row(
              Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable {
                  val timeText = instant.atZone(ZoneId.systemDefault()).format(
                    DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
                  )
                  clipboardManager.setText(
                    buildAnnotatedString {
                      append(timeText)
                      append(" ")
                      append(status)
                    }
                  )
                }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
              val timeText = instant.atZone(ZoneId.systemDefault()).format(
                DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
              )
              Text(
                text = timeText,
                color = JewelTheme.globalColors.text.info,
                modifier = Modifier.padding(end = 8.dp),
              )
              Text(
                text = status.replace(";base64,.*?\"".toRegex(), ";base64,[omitted]\""),
                modifier = Modifier.weight(1f),
              )
            }
          }
        }
      }
    }
  }
}
