# Arbignet MCP specification

## What users can do
1. Launch Arbigent UI
2. Edit json string in UI(ProjectSettingsDialog) . Users can directly edit json string.
3. Save json string in the project yaml field
4. Connect to MCP server
5. Get tool list
6. Pass tool list to ArbigentAI
7. Parse tool from LLM response
8. Execute tool
9. Get tool result

## Classes in arbigent-mcp-client

You need to implement class like this

```kotlin
class MCPClient(
    val jsonString: String
) {
  fun connect()
  fun tools(): List<Tool>
  fun executeTool(tool: Tool, executeToolArgs: ExecuteToolArgs): ExecuteToolResult
  fun close()
}
```

Example json format

```json
{
  "mcpServers": {
    "cosense-mcp-server": {
      "command": "npx",
      "args": ["/path/to/cosense-mcp-server"],
      "env": {
        "COSENSE_PROJECT_NAME": "your_project_name",
        "COSENSE_SID": "your_sid"
      }
    }
  }
}
```

## How to save json file

Please save the json String in ArbigentProjectFileContent.settings.mcpJson

## Sample implementation for implementing MCP client

```kotlin
package com.example.mcpclient

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

// --- Configuration & Globals ---

// Retrieve API key from environment variable, exit if not found.
val apiKey: String = System.getenv("LLM_API_KEY") ?: run {
  System.err.println("Error: LLM_API_KEY not found in environment variables.")
  exitProcess(1)
}

// Shared JSON configuration for serialization/deserialization.
val json = Json {
  ignoreUnknownKeys = true // Be lenient with unexpected fields from the API.
  prettyPrint = true       // Useful for debugging JSON payloads.
  encodeDefaults = true    // Ensure all fields are present in serialized output.
}

// --- LLM Data Structures ---

@Serializable
data class LLMMessage(val role: String, val content: String)

@Serializable
data class LLMRequest(
  val messages: List<LLMMessage>,
  val model: String = "llama3-70b-8192", // Default model
  val temperature: Double = 0.1,         // Controls randomness (lower = more deterministic)
  val max_tokens: Int = 1024,            // Max response length
  val stream: Boolean = false            // Streaming is not used in this example
)

@Serializable
data class LLMChoice(val message: LLMMessage)

@Serializable
data class LLMResponse(val choices: List<LLMChoice>) {
  // Helper to easily extract the content of the first message.
  fun firstMessageContent(): String? = choices.firstOrNull()?.message?.content
}

// Represents the expected JSON structure when the LLM decides to call a tool.
@Serializable
data class LLMToolCall(
  val tool_name: String,
  val arguments: JsonObject
)

// Represents the parsed outcome of an LLM response: either plain text or a tool call request.
sealed class ParsedLLMResponse {
  data class TextResponse(val text: String) : ParsedLLMResponse()
  data class ToolCallResponse(val toolCall: LLMToolCall) : ParsedLLMResponse()
}

// --- LLM API Client ---

class LLMClient(apiKey: String) : AutoCloseable {
  private val logger = LoggerFactory.getLogger(LLMClient::class.java)

  // Configured Ktor HTTP client for interacting with the LLM API.
  private val client = HttpClient(CIO) {
    install(ContentNegotiation) { // Enable JSON serialization/deserialization
      json(json)
    }
    defaultRequest { // Apply common headers to all requests
      headers {
        append(HttpHeaders.Authorization, "Bearer $apiKey")
        append(HttpHeaders.ContentType, ContentType.Application.Json)
      }
    }
    install(HttpTimeout) { // Set reasonable timeouts
      requestTimeoutMillis = 60_000 // 60 seconds
    }
    // Optional: Enable detailed Ktor request/response logging during development
    // install(Logging) { level = LogLevel.INFO }
  }

  /**
   * Sends messages to the LLM API and parses the response.
   * @return ParsedLLMResponse indicating either a direct text answer or a tool call request.
   * @throws IOException if the API request fails or the response is invalid.
   */
  suspend fun getResponse(messages: List<LLMMessage>): ParsedLLMResponse {
    val url = "https://api.groq.com/openai/v1/chat/completions" // Target LLM API endpoint
    val payload = LLMRequest(messages = messages)

    logger.debug("Sending LLM request to {}", url)

    try {
      val response: LLMResponse = client.post(url) {
        setBody(payload)
      }.body() // Execute POST request and deserialize JSON response

      logger.debug("Received LLM response.")

      val content = response.firstMessageContent() ?: run {
        logger.error("LLM response content is missing or invalid. Response: {}", response)
        throw IOException("Invalid LLM response format: missing content.")
      }

      // Attempt to parse the content as a ToolCall JSON.
      // If parsing fails (SerializationException or invalid structure), treat it as plain text.
      return try {
        // Trim whitespace which might interfere with JSON parsing
        val toolCall = json.decodeFromString<LLMToolCall>(content.trim())
        ParsedLLMResponse.ToolCallResponse(toolCall)
      } catch (e: SerializationException) {
        // Expected case for non-tool-call responses
        ParsedLLMResponse.TextResponse(content)
      } catch (e: IllegalArgumentException) {
        // Catch cases where it's JSON but not the expected ToolCall structure
        logger.warn("Could not parse content as ToolCall JSON, treating as text: {}", content)
        ParsedLLMResponse.TextResponse(content)
      }

    } catch (e: ClientRequestException) {
      // Handle HTTP errors (4xx, 5xx)
      val errorBody = runCatching { e.response.body<String>() }.getOrDefault("(could not read error body)")
      logger.error("LLM HTTP Client Error: {} - Response Body: {}", e.response.status, errorBody, e)
      throw IOException("LLM API request failed with status ${e.response.status}", e)
    } catch (e: Exception) {
      // Handle other potential errors (network issues, unexpected exceptions)
      logger.error("Error during LLM API call: {}", e.message, e)
      throw IOException("Generic error during LLM call", e)
    }
  }

  // Closes the underlying Ktor HTTP client.
  override fun close() {
    client.close()
    logger.info("LLM Client closed.")
  }
}

// --- Tool Formatting Utility ---

/**
 * Formats the list of available MCP tools into a string suitable for an LLM system prompt.
 * Describes the tools, their parameters, and how the LLM should format a tool call request.
 */
fun formatToolsForLlm(toolsResult: ListToolsResult?): String {
  val logger = LoggerFactory.getLogger("ToolFormatter")
  val sb = StringBuilder("You have access to the following tools. Use them when appropriate:\n\n")
  val actualTools = toolsResult?.tools ?: emptyList()

  if (actualTools.isEmpty()) {
    sb.append("No tools are available.\n")
  } else {
    actualTools.forEach { tool ->
      val toolName = tool.name
      val toolDesc = tool.description?.replace("\\s+".toRegex(), " ")?.trim() ?: "No description provided."
      val schema = tool.inputSchema
      val properties = schema?.properties ?: JsonObject(emptyMap())
      val requiredParams = schema?.required ?: emptyList()

      sb.append("## Tool: `${toolName}`\n") // Use Markdown for clarity
      sb.append("**Description:** $toolDesc\n")

      if (properties.isEmpty()) {
        sb.append("**Parameters:** None\n")
      } else {
        sb.append("**Parameters:**\n")
        properties.forEach { (name, detailsJsonElement) ->
          // Extract type information, default to 'any' if parsing fails
          val type = (detailsJsonElement as? JsonObject)?.get("type")?.jsonPrimitive?.content ?: "any"
          val description = (detailsJsonElement as? JsonObject)?.get("description")?.jsonPrimitive?.content
          val isRequired = name in requiredParams

          sb.append("  - `$name` ($type)")
          if (isRequired) sb.append(" (required)")
          if (description != null) sb.append(": $description")
          sb.append("\n")
        }
      }
      sb.append("---\n") // Separator between tools
    }
  }

  // Crucial instructions for the LLM on how to request a tool call
  sb.append("\n**How to Use Tools:**\n")
  sb.append("If you need to use a tool, respond ONLY with a single JSON object like this:\n")
  sb.append("```json\n")
  sb.append("{\n")
  sb.append("  \"tool_name\": \"<name_of_tool_to_use>\",\n")
  sb.append("  \"arguments\": { \"<param_name>\": \"<param_value>\", ... }\n")
  sb.append("}\n")
  sb.append("```\n")
  sb.append("Make sure the `arguments` object contains all required parameters for the chosen tool.\n")
  sb.append("If you do not need to use a tool, respond with your answer directly as plain text.\n")

  val formattedPrompt = sb.toString()
  logger.debug("Formatted tools prompt for LLM:\n{}", formattedPrompt) // Log the full prompt at debug level
  return formattedPrompt
}

// --- Main Application Logic ---

fun main(args: Array<String>) = runBlocking {
  val logger = LoggerFactory.getLogger("Main")
  logger.info("Starting Kotlin MCP Client Example...")

  // Determine the path to the server script (allow override via args)
  val serverScriptPath = args.firstOrNull() ?: "server.main.kts" // Default path
  val serverScriptFile = File(serverScriptPath).absoluteFile
  if (!serverScriptFile.exists() || !serverScriptFile.isFile) {
    logger.error("Error: Server script not found or is not a file at '{}'", serverScriptFile.path)
    exitProcess(1)
  }
  logger.info("Using server script: {}", serverScriptFile.path)

  // Initialize clients within a try block to ensure cleanup
  var llmClient: LLMClient? = null
  var mcpClient: Client? = null
  var serverProcess: Process? = null

  try {
    // Initialize LLM client (can be done outside process scope)
    llmClient = LLMClient(apiKey)

    // Initialize MCP client information
    mcpClient = Client(clientInfo = Implementation(name = "kotlin-mcp-client-example", version = "1.0.0"))

    // Start the MCP server script as a separate process
    logger.info("Starting MCP server process: kotlin {}", serverScriptFile.path)
    val command = listOf("kotlin", serverScriptFile.path) // Assuming 'kotlin' command runs .kts files
    serverProcess = ProcessBuilder(command)
//      .redirectErrorStream(true) // Redirect server's stderr to this process's input stream (for logging)
      .start()
    logger.info("Server process started (PID: {}). Waiting for MCP connection...", serverProcess.pid())

    // Connect to the server process via standard input/output
    val transport = StdioClientTransport(
      input = serverProcess.inputStream.asSource().buffered(), // Read from server's stdout
      output = serverProcess.outputStream.asSink().buffered()  // Write to server's stdin
    )
    mcpClient.connect(transport)
    logger.info("MCP connection established with server.")

    // Get the list of tools from the connected MCP server
    logger.info("Listing available tools from MCP server...")
    val toolsResponse = mcpClient.listTools()
    val toolsPrompt = formatToolsForLlm(toolsResponse)
    logger.info("Tool information retrieved. {} tools available.", toolsResponse?.tools?.size ?: 0)

    // --- Core Interaction Loop ---
    val userQuestion = "What is the weather in London?" // Example user query
    logger.info("User question: '{}'", userQuestion)

    val messages = mutableListOf(
      LLMMessage("system", toolsPrompt), // Provide tool context to the LLM
      LLMMessage("user", userQuestion)
    )

    logger.info("Sending initial request to LLM...")
    val llmResponse = llmClient.getResponse(messages)

    var finalResponseText: String

    when (llmResponse) {
      is ParsedLLMResponse.TextResponse -> {
        // LLM answered directly without needing a tool
        logger.info("LLM provided a direct text response.")
        finalResponseText = llmResponse.text
      }
      is ParsedLLMResponse.ToolCallResponse -> {
        // LLM requested a tool call
        val toolCall = llmResponse.toolCall
        logger.info("LLM requested tool call: Name='{}', Args='{}'", toolCall.tool_name, toolCall.arguments)

        // Add the LLM's request to the message history (as 'assistant')
        messages.add(LLMMessage("assistant", json.encodeToString(LLMToolCall.serializer(), toolCall)))

        // Execute the tool call via MCP
        logger.info("Executing tool '{}' via MCP...", toolCall.tool_name)
        val toolResult: CallToolResultBase? = mcpClient.callTool(toolCall.tool_name, toolCall.arguments)

        // Process the tool result (handle potential nulls or different content types)
        val toolResultText = toolResult?.content?.joinToString("\n") { content ->
          when (content) {
            is TextContent -> content.text ?: "[Empty TextContent]"
            else -> "[Received non-text content: ${content::class.simpleName}]"
          }
        } ?: "[No content received from tool execution]"
        logger.info("Tool '{}' executed. Result: '{}'", toolCall.tool_name, toolResultText)

        // Add the tool result to the message history (as 'user' or 'tool' role - 'user' is common)
        // Provide clear context about which tool was run and what it returned.
        messages.add(
          LLMMessage(
            "user", // Or use "tool" role if supported and preferred by the LLM
            "The tool '${toolCall.tool_name}' was executed and returned the following result:\n$toolResultText\n\nPlease use this information to answer the original question."
          )
        )

        // Ask the LLM again, now with the tool result in context
        logger.info("Sending follow-up request to LLM with tool results...")
        val finalLlmResponse = llmClient.getResponse(messages)

        // Get the final text answer from the second LLM response
        finalResponseText = when (finalLlmResponse) {
          is ParsedLLMResponse.TextResponse -> finalLlmResponse.text
          is ParsedLLMResponse.ToolCallResponse -> {
            // LLM responded with *another* tool call, which is unusual here.
            // Log a warning and provide the raw JSON as the 'final' answer for debugging.
            logger.warn("LLM unexpectedly requested another tool call after receiving tool results. Raw response: {}", finalLlmResponse.toolCall)
            "LLM requested another tool call unexpectedly: ${json.encodeToString(LLMToolCall.serializer(), finalLlmResponse.toolCall)}"
          }
        }
      }
    }

    // Output the final result
    logger.info("Received final response from LLM.")
    println("\n=== Final Answer ===")
    println(finalResponseText)
    println("====================")

  } catch (e: Exception) {
    // Catch any unexpected errors during the main execution
    logger.error("\n--- An unhandled error occurred in the main client logic ---", e)
    System.err.println("\nClient Runtime Error: ${e::class.simpleName} - ${e.message}")
    // Consider exiting with a non-zero code on failure
    // exitProcess(2)
  } finally {
    // --- Cleanup ---
    logger.info("\nInitiating cleanup...")

    // Close MCP Client
    try {
      mcpClient?.close()
      logger.info("MCP Client closed successfully.")
    } catch (e: Exception) {
      logger.error("Error closing MCP client", e)
    }

    // Close LLM Client
    try {
      llmClient?.close()
      // Logger message is inside LLMClient.close()
    } catch (e: Exception) {
      logger.error("Error closing LLM client", e)
    }

    // Terminate Server Process
    serverProcess?.let { proc ->
      if (proc.isAlive) {
        logger.info("Attempting to terminate MCP server process (PID: {})...", proc.pid())
        proc.destroy() // Request graceful termination first
        val terminatedGracefully = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) // Wait briefly

        if (!terminatedGracefully && proc.isAlive) {
          logger.warn("Server process did not terminate gracefully, forcing termination.")
          proc.destroyForcibly() // Force termination if needed
        }

        val exitCode = proc.waitFor() // Wait for process to fully exit
        logger.info("MCP server process terminated with exit code: {}.", exitCode)
      } else {
        // Process already finished, just log its exit code if possible
        val exitCode = try { proc.exitValue() } catch (_: IllegalThreadStateException) { "N/A (already exited)" }
        logger.info("MCP server process had already terminated with exit code: {}.", exitCode)
      }
    }
    logger.info("Client finished execution.")
  }
}
```

```kts:build.gradle.kts
plugins {
  kotlin("jvm") version "2.1.20" // Use appropriate Kotlin version
  kotlin("plugin.serialization") version "2.1.20" // Match Kotlin version
  application // For running the main function easily
}

repositories {
  mavenCentral()
}

dependencies {
  // Kotlin Coroutines
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1") // Use latest compatible version

  // Ktor Client Core and Engine (e.g., CIO)
  implementation("io.ktor:ktor-client-core:3.1.2")
  implementation("io.ktor:ktor-client-cio:3.1.2") // Or ktor-client-okhttp, etc.

  // Ktor Content Negotiation and Kotlinx Serialization
  implementation("io.ktor:ktor-client-content-negotiation:3.1.2")
  implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.2")

  // Kotlinx Serialization JSON runtime
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

  // MCP Kotlin SDK
  implementation("io.modelcontextprotocol:kotlin-sdk:0.4.0") // Use the version from docs

  // Kotlinx IO (for stream conversion)
  implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.7.0") // Check for latest compatible version

  // SLF4J API and a Logging Implementation (e.g., Logback)
  implementation("org.slf4j:slf4j-api:2.0.9")
  runtimeOnly("ch.qos.logback:logback-classic:1.4.14") // Example implementation

  // Optional: Dotenv for configuration
  // implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")

  // Testing dependencies (if needed)
  testImplementation(kotlin("test"))
}

application {
  mainClass.set("com.example.mcpclient.ClientKt") // Adjust package/class name if needed
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(11)) // Set to your desired Java version
  }
}

kotlin {
  jvmToolchain {
    this.languageVersion.set(JavaLanguageVersion.of(11)) // Set to your desired Java version
  }
}
```