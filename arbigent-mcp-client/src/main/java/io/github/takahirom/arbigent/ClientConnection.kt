package io.github.takahirom.arbigent

import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Represents a connection to an MCP server.
 *
 * @property serverName The name of the server.
 * @property command The command to start the server.
 * @property args The arguments for the command.
 * @property env The environment variables for the command.
 */
public class ClientConnection(
  public val serverName: String,
  public val command: String,
  public val args: List<String>,
  public val env: Map<String, String>
) {
  private val logger = LoggerFactory.getLogger(ClientConnection::class.java)
  private var mcpClient: Client? = null
  private var serverProcess: Process? = null

  /**
   * Connects to the MCP server.
   *
   * @return true if the connection was successful, false otherwise.
   */
  public suspend fun connect(): Boolean {
    try {
      logger.info("Starting MCP server: $serverName with command: $command ${args.joinToString(" ")}")

      // Build the command list
      val commandList = mutableListOf(command)
      commandList.addAll(args)

      try {
        // Start the server process
        val processBuilder = ProcessBuilder(commandList)

        // Add environment variables
        val processEnv = processBuilder.environment()
        env.forEach { (key, value) -> processEnv[key] = value }

        serverProcess = processBuilder.start()
        logger.info("Server process started (PID: ${serverProcess?.pid() ?: "unknown"})")

        // Initialize MCP client
        mcpClient = Client(clientInfo = Implementation(name = "arbigent-mcp-client", version = "1.0.0"))

        // Connect to the server process via standard input/output
        val transport = StdioClientTransport(
          input = serverProcess!!.inputStream.asSource().buffered(),
          output = serverProcess!!.outputStream.asSink().buffered()
        )

        mcpClient!!.connect(transport)
        logger.info("MCP connection established with server: $serverName")
        return true
      } catch (e: Exception) {
        logger.warn("Failed to start or connect to MCP server: ${e.message}, continuing without MCP")
        close() // Clean up resources if connection fails
        return false
      }
    } catch (e: Exception) {
      logger.warn("Error connecting to MCP server: ${e.message}, continuing without MCP")
      close() // Clean up resources if connection fails
      return false
    }
  }

  /**
   * Returns the list of tools available from the connected MCP server.
   *
   * @return List of available tools, or an empty list if not connected to an MCP server.
   */
  public suspend fun tools(): List<Tool> {
    if (mcpClient == null) {
      logger.warn("Not connected to an MCP server, returning empty tools list")
      return emptyList()
    }

    try {
      val toolsResponse = mcpClient!!.listTools()
      val tools = toolsResponse?.tools ?: emptyList()

      return tools.map { tool ->
        Tool(
          name = tool.name,
          description = tool.description,
          inputSchema = tool.inputSchema?.let { schema ->
            ToolSchema(
              properties = schema.properties ?: JsonObject(emptyMap()),
              required = schema.required ?: emptyList()
            )
          }
        )
      }
    } catch (e: Exception) {
      logger.warn("Error listing tools: ${e.message}, returning empty list")
      return emptyList()
    }
  }

  /**
   * Executes a tool with the given arguments.
   *
   * @param tool The tool to execute.
   * @param executeToolArgs The arguments for the tool.
   * @return The result of executing the tool, or a default result if not connected to an MCP server.
   */
  public suspend fun executeTool(tool: Tool, executeToolArgs: ExecuteToolArgs): ExecuteToolResult {
    if (mcpClient == null) {
      logger.warn("Not connected to an MCP server, returning default result for tool: ${tool.name}")
      return ExecuteToolResult(content = "[MCP server not available]")
    }

    try {
      val toolResult: CallToolResultBase? = mcpClient!!.callTool(tool.name, executeToolArgs.arguments)

      // Process the tool result
      val resultText = toolResult?.content?.joinToString("\n") { content ->
        when (content) {
          is TextContent -> content.text ?: "[Empty TextContent]"
          else -> "[Received non-text content: ${content::class.simpleName}]"
        }
      } ?: "[No content received from tool execution]"

      return ExecuteToolResult(content = resultText)
    } catch (e: Exception) {
      logger.warn("Error executing tool: ${tool.name}: ${e.message}, returning default result")
      return ExecuteToolResult(content = "[Error executing tool: ${e.message}]")
    }
  }

  /**
   * Closes the connection to the MCP server and cleans up resources.
   */
  public fun close() {
    // Close MCP Client
    try {
      mcpClient?.let { client ->
        runBlocking {
          client.close()
        }
      }
      logger.info("MCP Client closed successfully")
    } catch (e: Exception) {
      logger.error("Error closing MCP client", e)
    }

    // Terminate Server Process
    serverProcess?.let { proc ->
      if (proc.isAlive) {
        logger.info("Attempting to terminate MCP server process (PID: ${proc.pid()})")
        proc.destroy() // Request graceful termination first

        val terminatedGracefully = proc.waitFor(5, TimeUnit.SECONDS) // Wait briefly

        if (!terminatedGracefully && proc.isAlive) {
          logger.warn("Server process did not terminate gracefully, forcing termination")
          proc.destroyForcibly() // Force termination if needed
        }

        val exitCode = proc.waitFor() // Wait for process to fully exit
        logger.info("MCP server process terminated with exit code: $exitCode")
      } else {
        // Process already finished, just log its exit code if possible
        val exitCode = try {
          proc.exitValue()
        } catch (_: IllegalThreadStateException) {
          "N/A (already exited)"
        }
        logger.info("MCP server process had already terminated with exit code: $exitCode")
      }
    }

    // Clear references
    mcpClient = null
    serverProcess = null
  }

  /**
   * Checks if the client is connected to an MCP server.
   *
   * @return true if connected to an MCP server, false otherwise.
   */
  public fun isConnected(): Boolean {
    return mcpClient != null
  }
}