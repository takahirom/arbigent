package io.github.takahirom.arbigent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Represents a tool that can be executed by the MCP client.
 */
@Serializable
public data class Tool(
    /**
     * The name of the tool.
     */
    public val name: String,
    
    /**
     * The description of the tool.
     */
    public val description: String? = null,
    
    /**
     * The input schema of the tool.
     */
    public val inputSchema: ToolSchema? = null
)

/**
 * Represents the schema of a tool.
 */
@Serializable
public data class ToolSchema(
    /**
     * The properties of the schema.
     */
    public val properties: JsonObject = JsonObject(emptyMap()),
    
    /**
     * The required properties of the schema.
     */
    public val required: List<String> = emptyList()
)

/**
 * Arguments for executing a tool.
 */
@Serializable
public data class ExecuteToolArgs(
    /**
     * The arguments for the tool.
     */
    public val arguments: JsonObject
)

/**
 * Result of executing a tool.
 */
@Serializable
public data class ExecuteToolResult(
    /**
     * The content of the result.
     */
    public val content: String
)