#!/usr/bin/env kotlin

@file:Repository("https://repo1.maven.org/maven2")
@file:DependsOn("io.modelcontextprotocol:kotlin-sdk:0.4.0")          // MCP SDK Core
@file:DependsOn("io.modelcontextprotocol:kotlin-sdk-jvm:0.4.0")       // MCP SDK JVM specifics
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1") // Coroutines for async operations
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-io-core:0.7.0")        // kotlinx-io for stream handling

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.*

// Use System.err for script-level logging to keep stdout clean for MCP.
System.err.println("Kotlin MCP Server Script Starting...")

val server = Server(
  serverInfo = Implementation(name = "kotlin-script-server", version = "1.0.0"),
  options = ServerOptions(
    // Define server capabilities. Here, indicating static tool list.
    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = null))
  )
)

// Define the 'get_weather' tool
server.addTool(
  name = "get_weather",
  description = "Retrieves the current weather condition for a specific city.",
  inputSchema = Tool.Input(
    properties = JsonObject(
      mapOf(
        "location" to JsonObject(
          mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("The city name (e.g., Tokyo, London)")
          )
        )
      )
    ),
    required = listOf("location")
  )
) { request ->
  // Tool execution logic
  val location = request.arguments["location"]?.jsonPrimitive?.content ?: "unknown location"
  System.err.println("Executing get_weather for $location")

  // Example: Hardcoded weather result
  val weatherCondition = "Snow"
  System.err.println("'get_weather' result: $weatherCondition")

  // Return result conforming to the MCP specification
  CallToolResult(content = listOf(TextContent(weatherCondition)))
}

System.err.println("Server configured. Setting up stdio transport...")

// Setup transport to use standard input and standard output.
val transport = StdioServerTransport(
  inputStream = System.`in`.asSource().buffered(),  // Read MCP requests from stdin
  outputStream = System.out.asSink().buffered()     // Write MCP responses to stdout
)

// Run the server asynchronously and wait for it to close.
runBlocking {
  System.err.println("Connecting MCP server via stdio...")
  server.connect(transport)
  System.err.println("MCP server connected and running.")

  // Create a Job to keep the main coroutine alive until the server closes.
  val serverJob = Job()
  server.onClose {
    System.err.println("MCP server connection closed.")
    serverJob.complete() // Signal that the server has stopped.
  }

  // Wait until the server connection closes.
  serverJob.join()
}

System.err.println("Kotlin MCP Server Script finished.")