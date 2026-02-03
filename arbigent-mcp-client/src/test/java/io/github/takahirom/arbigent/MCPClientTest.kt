package io.github.takahirom.arbigent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MCPClientTest {
    @Test
    fun `test MCPClient constructor`() {
        // Test that the MCPClient can be instantiated with a valid JSON string
        val jsonString = """
        {
          "mcpServers": {
            "test-server": {
              "command": "echo",
              "args": ["Hello, World!"],
              "env": {
                "TEST_ENV": "test_value"
              }
            }
          }
        }
        """.trimIndent()
        

        val mcpClient = MCPClient(jsonString, DefaultArbigentAppSettings)
        assertEquals(jsonString, mcpClient.jsonString)
    }

    @Test
    fun `test executeTool when not connected`() {
        // Create a tool and execute it
        val tool = MCPTool(Tool(
            name = "test-tool",
            description = "A test tool",
            inputSchema = ToolSchema(
                properties = JsonObject(mapOf("param" to JsonPrimitive("value"))),
                required = listOf("param")
            )
        ), "test-server")

        val executeToolArgs = ExecuteToolArgs(
            arguments = JsonObject(mapOf("param" to JsonPrimitive("value")))
        )

        val mcpClient = MCPClient()

        // This should return a default result because we haven't connected
        val result = runBlocking {
            mcpClient.executeTool(tool, executeToolArgs)
        }
        assertEquals("[MCP server not available]", result.content)
    }

    @Test
    fun `test tools when not connected`() {
        val mcpClient = MCPClient()

        // This should return an empty list because we haven't connected
        val tools = runBlocking { mcpClient.tools(ClientConnection.JsonSchemaType.OpenAI) }
        assertEquals(emptyList<MCPTool>(), tools)
    }

    @Test
    fun `test executeTool with MCPTool when not connected`() {
        // Create a tool and wrap it in an MCPTool
        val tool = Tool(
            name = "test-tool",
            description = "A test tool",
            inputSchema = ToolSchema(
                properties = JsonObject(mapOf("param" to JsonPrimitive("value"))),
                required = listOf("param")
            )
        )
        val mcpTool = MCPTool(tool, "test-server")

        val executeToolArgs = ExecuteToolArgs(
            arguments = JsonObject(mapOf("param" to JsonPrimitive("value")))
        )

        val mcpClient = MCPClient()

        // This should return a default result because we haven't connected
        val result = runBlocking {
            mcpClient.executeTool(mcpTool, executeToolArgs)
        }
        assertEquals("[MCP server not available]", result.content)
    }

    @Test
    fun `test getDefaultEnabledServerNames returns null when all servers enabled`() {
        val jsonString = """
        {
          "mcpServers": {
            "filesystem": { "command": "npx", "args": ["-y", "@anthropic/mcp-filesystem"] },
            "github": { "command": "npx", "args": ["-y", "@anthropic/mcp-github"] }
          }
        }
        """.trimIndent()

        val mcpClient = MCPClient(jsonString, DefaultArbigentAppSettings)
        val result = mcpClient.getDefaultEnabledServerNames()

        // null means all servers are enabled (no filtering needed)
        assertNull(result)
    }

    @Test
    fun `test getDefaultEnabledServerNames returns enabled servers when some disabled`() {
        val jsonString = """
        {
          "mcpServers": {
            "filesystem": { "command": "npx", "args": ["-y", "@anthropic/mcp-filesystem"] },
            "github": { "command": "npx", "args": ["-y", "@anthropic/mcp-github"], "enabled": false }
          }
        }
        """.trimIndent()

        val mcpClient = MCPClient(jsonString, DefaultArbigentAppSettings)
        val result = mcpClient.getDefaultEnabledServerNames()

        // Should return only enabled servers
        assertEquals(listOf("filesystem"), result)
    }

    @Test
    fun `test getDefaultEnabledServerNames handles explicit enabled true`() {
        val jsonString = """
        {
          "mcpServers": {
            "filesystem": { "command": "npx", "args": [], "enabled": true },
            "github": { "command": "npx", "args": [], "enabled": false }
          }
        }
        """.trimIndent()

        val mcpClient = MCPClient(jsonString, DefaultArbigentAppSettings)
        val result = mcpClient.getDefaultEnabledServerNames()

        assertEquals(listOf("filesystem"), result)
    }

    @Test
    fun `test getDefaultEnabledServerNames returns empty when all disabled`() {
        val jsonString = """
        {
          "mcpServers": {
            "filesystem": { "command": "npx", "args": [], "enabled": false },
            "github": { "command": "npx", "args": [], "enabled": false }
          }
        }
        """.trimIndent()

        val mcpClient = MCPClient(jsonString, DefaultArbigentAppSettings)
        val result = mcpClient.getDefaultEnabledServerNames()

        // Empty list = all servers disabled by default
        assertEquals(emptyList<String>(), result)
    }

}
