package io.github.takahirom.arbigent.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.ArbigentMcpOptions
import io.github.takahirom.arbigent.EnabledMcpServer
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text

enum class McpMode {
  UseAllServers,
  SelectSpecific,
  DisableAll
}

@Composable
fun McpOptionsComponent(
  currentOptions: ArbigentMcpOptions?,
  availableServers: List<String>,
  onOptionsChanged: (ArbigentMcpOptions?) -> Unit,
  modifier: Modifier = Modifier
) {
  // Determine current mode from options
  val currentMode = when {
    currentOptions == null -> McpMode.UseAllServers
    currentOptions.enabledMcpServers == null -> McpMode.UseAllServers
    currentOptions.enabledMcpServers?.isEmpty() == true -> McpMode.DisableAll
    else -> McpMode.SelectSpecific
  }

  val selectedServers = currentOptions?.enabledMcpServers?.map { it.name }?.toSet() ?: emptySet()

  Column(modifier = modifier.selectableGroup()) {
    // Radio: Use all servers (default)
    RadioButtonRow(
      selected = currentMode == McpMode.UseAllServers,
      onClick = { onOptionsChanged(null) },
      modifier = Modifier.padding(vertical = 2.dp)
    ) {
      Text("Use all servers (default)")
    }

    // Radio: Select specific servers
    RadioButtonRow(
      selected = currentMode == McpMode.SelectSpecific,
      onClick = {
        // When switching to SelectSpecific, enable all servers by default
        onOptionsChanged(
          ArbigentMcpOptions(
            enabledMcpServers = availableServers.map { EnabledMcpServer(it) }
          )
        )
      },
      modifier = Modifier.padding(vertical = 2.dp)
    ) {
      Text("Select specific servers:")
    }

    // Server checkboxes (only enabled when SelectSpecific is selected)
    if (availableServers.isNotEmpty() && currentMode == McpMode.SelectSpecific) {
      Column(modifier = Modifier.padding(start = 32.dp)) {
        availableServers.forEach { serverName ->
          CheckboxRow(
            checked = selectedServers.contains(serverName),
            onCheckedChange = { isChecked ->
              val newServers = if (isChecked) {
                selectedServers + serverName
              } else {
                selectedServers - serverName
              }
              onOptionsChanged(
                ArbigentMcpOptions(
                  enabledMcpServers = newServers.map { EnabledMcpServer(it) }
                )
              )
            },
            modifier = Modifier.padding(vertical = 2.dp)
          ) {
            Text(serverName)
          }
        }
      }
    }

    // Radio: Disable all MCP
    RadioButtonRow(
      selected = currentMode == McpMode.DisableAll,
      onClick = {
        onOptionsChanged(ArbigentMcpOptions(enabledMcpServers = emptyList()))
      },
      modifier = Modifier.padding(vertical = 2.dp)
    ) {
      Text("Disable all MCP")
    }
  }
}
