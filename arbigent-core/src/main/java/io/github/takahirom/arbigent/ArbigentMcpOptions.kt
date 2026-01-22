package io.github.takahirom.arbigent

import kotlinx.serialization.Serializable

/**
 * Configuration options for controlling MCP server behavior at the scenario level.
 * Uses an override approach: only servers listed here are overridden from project defaults.
 */
@Serializable
public data class ArbigentMcpOptions(
    /**
     * List of MCP server options that override project defaults.
     * - null or empty: Use project defaults for all servers
     * - non-empty: Override listed servers with specified enable/disable state
     */
    val mcpServerOptions: List<McpServerOption>? = null
) {
    /**
     * Check if a specific server is enabled based on the configuration.
     * If the server is not in the override list, returns null (use default).
     */
    public fun getServerOverride(serverName: String): Boolean? {
        return mcpServerOptions?.find { it.name == serverName }?.enable
    }
}

/**
 * Represents an MCP server option override.
 */
@Serializable
public data class McpServerOption(
    val name: String,
    val enable: Boolean
)
