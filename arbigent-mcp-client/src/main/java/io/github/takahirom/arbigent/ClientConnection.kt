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
import co.touchlab.kermit.Logger
import java.io.File
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
  public val env: Map<String, String>,
  public val appSettings: ArbigentAppSettings
) {
  private val logger = Logger.withTag("ClientConnection")
  private var mcpClient: Client? = null
  private var serverProcess: Process? = null

  /**
   * Connects to the MCP server.
   *
   * @return true if the connection was successful, false otherwise.
   */
  public suspend fun connect(): Boolean {
    try {
      logger.i { "Starting MCP server: $serverName with command: $command ${args.joinToString(" ")}" }

      // Build the command list
      val commandList = mutableListOf(command)
      commandList.addAll(args)

      try {
        // Start the server process
        val processBuilder = ProcessBuilder(commandList)

        // Add environment variables
        val processEnv = processBuilder.environment()
        env.forEach { (key, value) -> processEnv[key] = value }

        // Set PATH if provided in appSettings
        try {
          val path = appSettings.path
          if (!path.isNullOrBlank()) {
            logger.i { "Setting PATH: $path" }
            processEnv["PATH"] = path + File.pathSeparator + processEnv["PATH"]
          }
        } catch (e: Exception) {
          logger.w { "Failed to get PATH from appSettings: ${e.message}" }
        }

        // Set working directory if provided in appSettings
        try {
          // Use the interface method directly
          val workingDirectory = appSettings.workingDirectory
          if (!workingDirectory.isNullOrBlank()) {
            val workingDirectoryFile = File(workingDirectory)
            if (workingDirectoryFile.exists() && workingDirectoryFile.isDirectory) {
              processBuilder.directory(workingDirectoryFile)
              logger.i { "Setting working directory: $workingDirectory" }
            } else {
              logger.w { "Working directory does not exist or is not a directory: $workingDirectory" }
            }
          }
        } catch (e: Exception) {
          logger.w { "Failed to get working directory from appSettings: ${e.message}" }
        }

        serverProcess = processBuilder.start()
        logger.i { "Server process started (PID: ${serverProcess?.pid() ?: "unknown"})" }

        // Capture and log error output from the process
        val errorReader = Thread {
          try {
            val errorStream = serverProcess!!.errorStream
            val reader = errorStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
              logger.w { "MCP server error output: $line" }
            }
          } catch (e: Exception) {
            logger.e { "Error reading from process error stream: ${e.message}" }
          }
        }
        errorReader.isDaemon = true
        errorReader.start()
        serverProcess?.onExit()
          ?.thenAccept { exit ->
            logger.i { "MCP server process exited with code: ${exit.exitValue()}" }
            errorReader.interrupt()
          }

        // Initialize MCP client
        mcpClient = Client(clientInfo = Implementation(name = "arbigent-mcp-client", version = "1.0.0"))

        // Connect to the server process via standard input/output
        val transport = StdioClientTransport(
          input = serverProcess!!.inputStream.asSource().buffered(),
          output = serverProcess!!.outputStream.asSink().buffered()
        )

        mcpClient!!.connect(transport)
        logger.i { "MCP connection established with server: $serverName" }
        return true
      } catch (e: Exception) {
        logger.w { "Failed to start or connect to MCP server: ${e.message}, continuing without MCP" }
        close() // Clean up resources if connection fails
        throw e
        return false
      }
    } catch (e: Exception) {
      logger.w { "Error connecting to MCP server: ${e.message}, continuing without MCP" }
      close() // Clean up resources if connection fails
      throw e
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
      logger.w { "Not connected to an MCP server, returning empty tools list" }
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
      logger.w { "Error listing tools: ${e.message}, returning empty list" }
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
      logger.w { "Not connected to an MCP server, returning default result for tool: ${tool.name}" }
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
      logger.w { "Error executing tool: ${tool.name}: ${e.message}, returning default result" }
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
      logger.i { "MCP Client closed successfully" }
    } catch (e: Exception) {
      logger.e(e) { "Error closing MCP client" }
    }

    // Terminate Server Process
    serverProcess?.let { proc ->
      if (proc.isAlive) {
        logger.i { "Attempting to terminate MCP server process (PID: ${proc.pid()})" }
        proc.destroy() // Request graceful termination first

        val terminatedGracefully = proc.waitFor(5, TimeUnit.SECONDS) // Wait briefly

        if (!terminatedGracefully && proc.isAlive) {
          logger.w { "Server process did not terminate gracefully, forcing termination" }
          proc.destroyForcibly() // Force termination if needed
        }

        val exitCode = proc.waitFor() // Wait for process to fully exit
        logger.i { "MCP server process terminated with exit code: $exitCode" }
      } else {
        // Process already finished, just log its exit code if possible
        val exitCode = try {
          proc.exitValue()
        } catch (_: IllegalThreadStateException) {
          "N/A (already exited)"
        }
        logger.i { "MCP server process had already terminated with exit code: $exitCode" }
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
