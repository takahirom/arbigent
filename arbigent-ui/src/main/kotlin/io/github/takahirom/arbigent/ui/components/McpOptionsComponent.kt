package io.github.takahirom.arbigent.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentMcpOptions
import io.github.takahirom.arbigent.McpServerOption
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.ListItemState
import org.jetbrains.jewel.ui.component.SimpleListItem
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.ComboBox
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.painter.hints.Size
import org.jetbrains.jewel.ui.theme.simpleListItemStyle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun McpOptionsComponent(
  currentOptions: ArbigentMcpOptions?,
  availableServers: List<String>,
  onOptionsChanged: (ArbigentMcpOptions?) -> Unit,
  modifier: Modifier = Modifier
) {
  val overrides = currentOptions?.mcpServerOptions ?: emptyList()

  // Servers that haven't been added as overrides yet
  val availableToAdd = availableServers.filter { serverName ->
    overrides.none { it.name == serverName }
  }

  var showAddRow by remember { mutableStateOf(false) }
  var selectedServerToAdd by remember { mutableStateOf<String?>(null) }

  Column(modifier = modifier) {
    // Info text
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(bottom = 4.dp)
    ) {
      Text("Override project defaults", modifier = Modifier.padding(end = 4.dp))
      IconActionButton(
        key = AllIconsKeys.General.Information,
        onClick = {},
        contentDescription = "MCP override info",
        hint = Size(16)
      ) {
        Text("Servers not listed here use project defaults from MCP JSON 'enabled' field.")
      }
    }

    // List of current overrides
    if (overrides.isNotEmpty()) {
      Column(
        modifier = Modifier
          .padding(vertical = 4.dp)
          .background(JewelTheme.globalColors.panelBackground)
      ) {
        overrides.forEach { option ->
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
              .padding(horizontal = 8.dp, vertical = 2.dp)
              .testTag("mcp_override_row_${option.name}")
          ) {
            // Server name
            Text(
              text = option.name,
              modifier = Modifier.width(100.dp)
            )

            // Enable checkbox
            CheckboxRow(
              checked = option.enabled,
              onCheckedChange = { newEnabled ->
                val newOverrides = overrides.map {
                  if (it.name == option.name) it.copy(enabled = newEnabled) else it
                }
                onOptionsChanged(ArbigentMcpOptions(mcpServerOptions = newOverrides))
              },
              modifier = Modifier.testTag("mcp_override_enabled_${option.name}")
            ) {
              Text(if (option.enabled) "Enabled" else "Disabled")
            }

            // Delete button
            IconActionButton(
              key = AllIconsKeys.General.Delete,
              onClick = {
                val newOverrides = overrides.filter { it.name != option.name }
                onOptionsChanged(
                  if (newOverrides.isEmpty()) null
                  else ArbigentMcpOptions(mcpServerOptions = newOverrides)
                )
              },
              contentDescription = "Remove override for ${option.name}",
              hint = Size(16),
              modifier = Modifier.testTag("mcp_override_delete_${option.name}")
            )
          }
        }
      }
    }

    // Add new override section
    if (availableToAdd.isNotEmpty()) {
      if (showAddRow) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(top = 8.dp)
        ) {
          // Server selection combo box
          ComboBox(
            labelText = selectedServerToAdd ?: "Select server",
            maxPopupHeight = 150.dp,
            modifier = Modifier
              .width(120.dp)
              .testTag("mcp_add_server_combo")
          ) {
            Column {
              availableToAdd.forEach { serverName ->
                val isSelected = serverName == selectedServerToAdd
                SimpleListItem(
                  text = serverName,
                  state = ListItemState(isSelected, false, false),
                  modifier = Modifier
                    .testTag("mcp_add_server_item_$serverName")
                    .clickable {
                      selectedServerToAdd = serverName
                    },
                  style = JewelTheme.simpleListItemStyle,
                  contentDescription = serverName
                )
              }
            }
          }

          // Add button (adds as disabled by default - override to disable)
          IconActionButton(
            key = AllIconsKeys.General.Add,
            onClick = {
              selectedServerToAdd?.let { serverName ->
                val newOverrides = overrides + McpServerOption(name = serverName, enabled = false)
                onOptionsChanged(ArbigentMcpOptions(mcpServerOptions = newOverrides))
                selectedServerToAdd = null
                showAddRow = false
              }
            },
            contentDescription = "Add override",
            hint = Size(20),
            modifier = Modifier
              .padding(start = 8.dp)
              .testTag("mcp_add_confirm_button"),
            enabled = selectedServerToAdd != null
          )

          // Cancel button
          IconActionButton(
            key = AllIconsKeys.Actions.Cancel,
            onClick = {
              showAddRow = false
              selectedServerToAdd = null
            },
            contentDescription = "Cancel",
            hint = Size(20),
            modifier = Modifier
              .padding(start = 4.dp)
              .testTag("mcp_add_cancel_button")
          )
        }
      } else {
        // Show "+ Add override" button
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .padding(top = 8.dp)
            .clickable { showAddRow = true }
            .testTag("mcp_add_override_button")
        ) {
          IconActionButton(
            key = AllIconsKeys.General.Add,
            onClick = { showAddRow = true },
            contentDescription = "Add override",
            hint = Size(16)
          )
          Text("Add override", modifier = Modifier.padding(start = 4.dp))
        }
      }
    }

    // Show message when no servers available
    if (availableServers.isEmpty()) {
      Text(
        "No MCP servers configured",
        modifier = Modifier.padding(vertical = 8.dp)
      )
    }
  }
}
