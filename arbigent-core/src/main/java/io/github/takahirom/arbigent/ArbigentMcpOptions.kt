package io.github.takahirom.arbigent

import kotlinx.serialization.Serializable

/**
 * Configuration options for controlling MCP server behavior at the scenario level.
 */
@Serializable
public data class ArbigentMcpOptions(
    /**
     * List of enabled MCP servers for this scenario.
     * - null: All servers enabled (default, backward compatible)
     * - empty list: All MCP disabled
     * - non-empty list: Only listed servers are enabled
     */
    val enabledMcpServers: List<EnabledMcpServer>? = null
) {
    /**
     * Check if a specific server is enabled based on the configuration.
     */
    public fun isServerEnabled(serverName: String): Boolean = when {
        enabledMcpServers == null -> true   // null means all enabled
        enabledMcpServers.isEmpty() -> false // empty means all disabled
        else -> enabledMcpServers.any { it.name == serverName }
    }
}

/**
 * Represents an enabled MCP server configuration.
 * Using an object instead of a plain string for future extensibility
 * (e.g., adding timeout, custom environment variables per server).
 */
@Serializable
public data class EnabledMcpServer(
    val name: String
)
