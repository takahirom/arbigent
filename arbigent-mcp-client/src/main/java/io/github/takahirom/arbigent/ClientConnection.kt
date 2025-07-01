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
import co.touchlab.kermit.Logger
import io.modelcontextprotocol.kotlin.sdk.Tool.Input
import kotlinx.serialization.json.*
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
      serverProcess?.let {
        if (it.isAlive) {
          logger.i { "MCP server process is already running (PID: ${it.pid()})" }
          return true
        } else {
          logger.i { "MCP server process has already terminated (PID: ${it.pid()})" }
        }
      }
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
  public enum class JsonSchemaType {
    OpenAI,
    GeminiOpenAICompatible;
  }

  /**
   * Returns the list of tools available from the connected MCP server,
   * adapting the schema based on the target AI platform's requirements.
   *
   * @param jsonSchemaType Specifies the target API (OpenAI or Gemini) to format the schema for.
   * @return List of available tools with adapted schemas, or an empty list if an error occurs or not connected.
   */
  public suspend fun tools(jsonSchemaType: JsonSchemaType): List<Tool> {
    // Disabled MCP check for standalone compilation - uncomment in your environment
    val mcpClient = mcpClient
     if (mcpClient == null) {
       logger.w { "Not connected to an MCP server, returning empty tools list" }
       return emptyList()
     }

    try {
      // Log MCP tools request
      logger.i { "MCP Tools - Requesting tools from server: $serverName" }
      logger.i { "MCP Tools - JsonSchema type: $jsonSchemaType" }
      
      // Replace with your actual client call
      val toolsResponse = mcpClient.listTools()
      // Use mapNotNull to safely handle potential null tools or schemas
      val mcpTools = toolsResponse?.tools ?: emptyList()

      logger.i { "MCP Tools - Received ${mcpTools.size} tools from server: $serverName" }

      return mcpTools.mapNotNull { mcpTool ->
        val originalSchema = mcpTool.inputSchema ?: run {
          logger.w { "MCP Tools - Skipping tool ${mcpTool.name}: no input schema" }
          return@mapNotNull null // Skip tool if it has no schema
        }

        logger.i { "MCP Tools - Processing tool: ${mcpTool.name}" }
        logger.i { "MCP Tools - Original description: ${mcpTool.description}" }
        logger.i { "MCP Tools - Original schema properties: ${originalSchema.properties}" }
        logger.i { "MCP Tools - Original schema required: ${originalSchema.required}" }

        // Extract the actual properties from the JSON Schema format
        val actualProperties = if (originalSchema.properties.containsKey("properties")) {
          // Standard JSON Schema format: {"type": "object", "properties": {...}}
          val propertiesElement = originalSchema.properties["properties"]
          if (propertiesElement is JsonObject) {
            propertiesElement
          } else {
            logger.w { "MCP Tools - 'properties' field is not a JsonObject: $propertiesElement" }
            JsonObject(emptyMap())
          }
        } else {
          // Direct properties format (legacy)
          originalSchema.properties
        }

        val actualRequired = originalSchema.required ?: emptyList()
        
        logger.i { "MCP Tools - Extracted actual properties: $actualProperties" }
        logger.i { "MCP Tools - Extracted actual required: $actualRequired" }

        // Transform properties and determine required list based on the target API type
        val (transformedProperties, finalRequiredList) = when (jsonSchemaType) {
          JsonSchemaType.GeminiOpenAICompatible -> transformSchemaForGemini(actualProperties, actualRequired)
          JsonSchemaType.OpenAI -> transformSchemaForOpenAI(actualProperties, actualRequired)
        }

        logger.i { "MCP Tools - Transformed properties: $transformedProperties" }
        logger.i { "MCP Tools - Final required list: $finalRequiredList" }

        // Create the final Tool object with the transformed schema
        val finalTool = Tool(
          name = mcpTool.name,
          description = mcpTool.description,
          inputSchema = ToolSchema(
            properties = transformedProperties,
            required = finalRequiredList
          )
        )

        logger.i { "MCP Tools - Final tool: ${finalTool.name} with ${finalTool.inputSchema?.required?.size ?: 0} required parameters" }
        finalTool
      }
    } catch (e: Exception) {
      logger.w { "MCP Tools - Error listing or transforming tools from server $serverName: ${e.message}" }
      logger.w { "MCP Tools - Exception type: ${e.javaClass.simpleName}" }
      logger.w { "MCP Tools - Stack trace: ${e.stackTraceToString()}" }
      return emptyList()
    }
  }

  /**
   * Transforms the tool schema to be compatible with Gemini API requirements.
   * - Adds 'format: "enum"' for string enums.
   * - Uses 'nullable: true' for optional parameters.
   * - Keeps the original 'required' list.
   */
  private fun transformSchemaForGemini(properties: JsonObject, required: List<String>): Pair<JsonObject, List<String>> {
    val transformedProperties = JsonObject(properties.mapValues { entry ->
      val propertyName = entry.key
      
      // Safely handle cases where entry.value might not be a JsonObject
      val originalProperties = when (val entryValue = entry.value) {
        is JsonObject -> entryValue
        else -> {
          logger.w { "MCP Tools - Property '$propertyName' is not a JsonObject (${entryValue::class.simpleName}): $entryValue" }
          // Create a simple string type schema as fallback
          JsonObject(mapOf("type" to JsonPrimitive("string")))
        }
      }
      
      // Use elvis operator here for safe check, assuming null means not required
      val isRequired = required.contains(propertyName)

      val hasEnum = originalProperties.containsKey("enum")
      val originalType = originalProperties["type"]
      val isStringType = originalType is JsonPrimitive && originalType.isString && originalType.content == "string"

      val newProps = originalProperties.toMutableMap()

      if (isStringType && hasEnum) {
        // --- Handle STRING properties WITH enums for Gemini ---
        newProps["format"] = JsonPrimitive("enum")
        if (!isRequired) {
          newProps["nullable"] = JsonPrimitive(true)
          newProps["type"] = JsonPrimitive("string") // Ensure base type
        } else {
          newProps.remove("nullable")
          newProps["type"] = JsonPrimitive("string") // Ensure base type
        }
      } else if (!isRequired) {
        // --- Handle OPTIONAL properties WITHOUT string enums for Gemini ---
        val originalTypeElement = originalProperties["type"] ?: JsonPrimitive("string") // Default if missing
        val baseType = getBaseType(originalTypeElement)
        newProps["type"] = baseType // Set single base type
        newProps["nullable"] = JsonPrimitive(true) // Add nullable: true
        newProps.remove("format") // Clean up format if not string+enum
      } else {
        // --- Handle REQUIRED properties WITHOUT string enums for Gemini ---
        val originalTypeElement = originalProperties["type"] ?: JsonPrimitive("string") // Default if missing
        newProps["type"] = getBaseType(originalTypeElement) // Ensure single base type
        newProps.remove("nullable") // Ensure not nullable
        if (!(isStringType && hasEnum)) { // Clean up format if not string+enum
          newProps.remove("format")
        }
      }
      JsonObject(newProps)
    })
    // Gemini uses the original required list.
    return Pair(transformedProperties, required)
  }

  /**
   * Transforms the tool schema to be compatible with OpenAI API requirements.
   * - Makes ALL parameters required.
   * - Represents optionality using 'type: ["<type>", "null"]'.
   * - Removes 'nullable: true' and 'format: "enum"'.
   */
  private fun transformSchemaForOpenAI(properties: JsonObject, required: List<String>): Pair<JsonObject, List<String>> {
    val transformedProperties = JsonObject(properties.mapValues { entry ->
      val propertyName = entry.key
      
      // Safely handle cases where entry.value might not be a JsonObject
      val originalProperties = when (val entryValue = entry.value) {
        is JsonObject -> entryValue
        else -> {
          logger.w { "MCP Tools - Property '$propertyName' is not a JsonObject (${entryValue::class.simpleName}): $entryValue" }
          // Create a simple string type schema as fallback
          JsonObject(mapOf("type" to JsonPrimitive("string")))
        }
      }
      
      // Use the required list for checking original optionality
      val isOriginallyRequired = required.contains(propertyName)

      val newProps = originalProperties.toMutableMap()
      val originalTypeElement = originalProperties["type"] ?: JsonPrimitive("string") // Default if missing
      val baseType = getBaseType(originalTypeElement) // Get the non-null base type

      if (!isOriginallyRequired) {
        // --- Handle ORIGINALLY OPTIONAL properties for OpenAI ---
        // Use type: [baseType, "null"] to represent optionality
        newProps["type"] = JsonArray(listOf(baseType, JsonPrimitive("null")))
      } else {
        // --- Handle ORIGINALLY REQUIRED properties for OpenAI ---
        // Ensure type is the single base type
        newProps["type"] = baseType
      }

      // Clean up fields not used or potentially problematic for OpenAI
      newProps.remove("nullable")
      newProps.remove("format")

      JsonObject(newProps)
    })
    // OpenAI requires ALL properties to be listed in 'required'
    val allPropertiesRequired = properties.keys.toList()
    return Pair(transformedProperties, allPropertiesRequired)
  }

  /**
   * Helper function to extract the base (non-null) primitive type from a JsonElement.
   * Handles cases where the input might be a primitive, an array (like ["string", "null"]),
   * null, or missing. Defaults to "string" as a fallback.
   */
  private fun getBaseType(originalTypeElement: JsonElement): JsonPrimitive {
    return when (originalTypeElement) {
      is JsonPrimitive -> if (originalTypeElement.contentOrNull == "null") JsonPrimitive("string") else originalTypeElement // If primitive is "null", fallback
      is JsonArray -> {
        // Find the first non-null primitive type in the array
        originalTypeElement.firstOrNull { it is JsonPrimitive && it.contentOrNull != "null" }?.jsonPrimitive
          ?: JsonPrimitive("string") // Fallback if only null or empty/invalid array
      }
      // Default to string for null, JsonObject, or other unexpected types.
      else -> JsonPrimitive("string")
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
      // Log the MCP request details
      logger.i { "MCP Request - Tool: ${tool.name}, Server: $serverName" }
      logger.i { "MCP Request - Arguments: ${executeToolArgs.arguments}" }
      
      val toolResult: CallToolResultBase? = mcpClient!!.callTool(tool.name, executeToolArgs.arguments)

      // Log the MCP response details
      logger.i { "MCP Response - Tool: ${tool.name}, Server: $serverName" }
      logger.i { "MCP Response - Result type: ${toolResult?.javaClass?.simpleName ?: "null"}" }
      logger.i { "MCP Response - Content count: ${toolResult?.content?.size ?: 0}" }

      // Process the tool result
      val resultText = toolResult?.content?.joinToString("\n") { content ->
        when (content) {
          is TextContent -> {
            logger.i { "MCP Response - TextContent: ${content.text}" }
            content.text ?: "[Empty TextContent]"
          }
          else -> {
            logger.i { "MCP Response - Non-text content: ${content::class.simpleName}" }
            "[Received non-text content: ${content::class.simpleName}]"
          }
        }
      } ?: "[No content received from tool execution]"

      logger.i { "MCP Response - Final result: $resultText" }
      return ExecuteToolResult(content = resultText)
    } catch (e: Exception) {
      logger.w { "MCP Error - Tool: ${tool.name}, Server: $serverName, Error: ${e.message}" }
      logger.w { "MCP Error - Exception type: ${e.javaClass.simpleName}" }
      logger.w { "MCP Error - Stack trace: ${e.stackTraceToString()}" }
      return ExecuteToolResult(content = "[Error executing tool: ${e.message}]")
    }
  }

  /**
   * Closes the connection to the MCP server and cleans up resources.
   */
  public suspend fun close() {
    // Close MCP Client
    try {
      mcpClient?.close()
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
