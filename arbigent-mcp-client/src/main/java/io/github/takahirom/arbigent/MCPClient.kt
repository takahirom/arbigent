package io.github.takahirom.arbigent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Client for interacting with Model Context Protocol (MCP) servers.
 *
 * @property jsonString The JSON configuration string for MCP servers.
 */
public class MCPClient(
  public val jsonString: String
) {
  private val logger = LoggerFactory.getLogger(MCPClient::class.java)
  private val json = Json { ignoreUnknownKeys = true }
  private val connections = mutableListOf<ClientConnection>()

  /**
   * Connects to the MCP servers specified in the JSON configuration.
   *
   * If no MCP servers are available, logs a warning and returns without throwing an exception.
   */
  public suspend fun connect(): Unit {
    try {
      // Parse the JSON configuration
      val config = json.parseToJsonElement(jsonString).jsonObject
      val mcpServers = config["mcpServers"]?.jsonObject

      if (mcpServers == null) {
        logger.warn("No mcpServers found in configuration, skipping MCP connection")
        return
      }

      if (mcpServers.isEmpty()) {
        logger.warn("No MCP servers defined in configuration, skipping MCP connection")
        return
      }

      // Process each server in the configuration
      for ((serverName, serverConfig) in mcpServers.entries) {
        val serverConfigObj = serverConfig.jsonObject

        val command = serverConfigObj["command"]?.jsonPrimitive?.content
        if (command == null) {
          logger.warn("No command specified for server $serverName, skipping")
          continue
        }

        val args = serverConfigObj["args"]?.jsonObject?.values?.map {
          it.jsonPrimitive.content
        } ?: emptyList()

        val env = serverConfigObj["env"]?.jsonObject?.entries?.associate { (key, value) ->
          key to value.jsonPrimitive.content
        } ?: emptyMap()

        // Create a new client connection
        val clientConnection = ClientConnection(
          serverName = serverName,
          command = command,
          args = args,
          env = env
        )

        try {
          // Connect to the server
          val connected = clientConnection.connect()
          if (connected) {
            connections.add(clientConnection)
            logger.info("Added connection to server: $serverName")
          } else {
            logger.warn("Failed to connect to server: $serverName, skipping")
          }
        } catch (e: Exception) {
          logger.warn("Error connecting to server: $serverName: ${e.message}, skipping")
          clientConnection.close() // Clean up resources if connection fails
        }
      }

      if (connections.isEmpty()) {
        logger.warn("No MCP servers connected, continuing without MCP")
      } else {
        logger.info("Connected to ${connections.size} MCP servers")
      }
    } catch (e: Exception) {
      logger.warn("Error parsing MCP configuration: ${e.message}, continuing without MCP")
      close() // Clean up resources if connection fails
      throw e
    }
  }

  /**
   * Returns the list of tools available from all connected MCP servers.
   * Each tool is wrapped in an MCPTool that includes the server name.
   *
   * @return List of available tools with server information, or an empty list if not connected to any MCP server.
   */
  public suspend fun tools(): List<MCPTool> {
    if (connections.isEmpty()) {
      logger.warn("Not connected to any MCP server, returning empty tools list")
      return emptyList()
    }

    val allTools = mutableListOf<MCPTool>()

    // Collect tools from all connections and wrap them with server information
    for (connection in connections) {
      try {
        val tools = connection.tools()
        // Wrap each tool with the server name
        val mcpTools = tools.map { tool -> MCPTool(tool, connection.serverName) }
        allTools.addAll(mcpTools)
      } catch (e: Exception) {
        logger.warn("Error listing tools from server ${connection.serverName}: ${e.message}")
      }
    }

    return allTools
  }

  /**
   * Executes a tool with the given arguments on the specified MCP server.
   *
   * @param mcpTool The tool to execute, including server information.
   * @param executeToolArgs The arguments for the tool.
   * @return The result of executing the tool, or a default result if not connected to the specified MCP server.
   */
  public suspend fun executeTool(mcpTool: MCPTool, executeToolArgs: ExecuteToolArgs): ExecuteToolResult {
    if (connections.isEmpty()) {
      logger.warn("Not connected to any MCP server, returning default result for tool: ${mcpTool.name}")
      return ExecuteToolResult(content = "[MCP server not available]")
    }

    // Find the connection for the specified server
    val connection = connections.find { it.serverName == mcpTool.serverName }
    if (connection == null) {
      logger.warn("No connection found for server: ${mcpTool.serverName}, returning default result for tool: ${mcpTool.name}")
      return ExecuteToolResult(content = "[MCP server not available: ${mcpTool.serverName}]")
    }

    // Execute the tool on the specified connection
    try {
      return connection.executeTool(mcpTool.tool, executeToolArgs)
    } catch (e: Exception) {
      logger.warn("Error executing tool ${mcpTool.name} on server ${mcpTool.serverName}: ${e.message}")
      return ExecuteToolResult(content = "[Error executing tool on server ${mcpTool.serverName}: ${e.message}]")
    }
  }

  /**
   * Executes a tool with the given arguments.
   * This method is provided for backward compatibility.
   * It will try to execute the tool on each connection until one succeeds.
   *
   * @param tool The tool to execute.
   * @param executeToolArgs The arguments for the tool.
   * @return The result of executing the tool, or a default result if not connected to any MCP server.
   */
  public suspend fun executeTool(tool: Tool, executeToolArgs: ExecuteToolArgs): ExecuteToolResult {
    if (connections.isEmpty()) {
      logger.warn("Not connected to any MCP server, returning default result for tool: ${tool.name}")
      return ExecuteToolResult(content = "[MCP server not available]")
    }

    // Try each connection until one succeeds
    for (connection in connections) {
      try {
        val result = connection.executeTool(tool, executeToolArgs)
        // If we got a result that's not the default error message, return it
        if (result.content != "[MCP server not available]") {
          return result
        }
      } catch (e: Exception) {
        logger.warn("Error executing tool ${tool.name} on server ${connection.serverName}: ${e.message}")
      }
    }

    // If we get here, all connections failed
    return ExecuteToolResult(content = "[Error executing tool: No available MCP server could execute the tool]")
  }

  /**
   * Closes all connections to MCP servers and cleans up resources.
   */
  public fun close(): Unit {
    // Close all connections
    for (connection in connections) {
      try {
        connection.close()
        logger.info("Closed connection to server: ${connection.serverName}")
      } catch (e: Exception) {
        logger.error("Error closing connection to server ${connection.serverName}", e)
      }
    }

    // Clear the connections list
    connections.clear()
  }

  /**
   * Checks if the client is connected to any MCP server.
   *
   * @return true if connected to at least one MCP server, false otherwise.
   */
  private fun isConnected(): Boolean {
    return connections.any { it.isConnected() }
  }
}
