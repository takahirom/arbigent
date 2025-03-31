package io.github.takahirom.arbigent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals

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

        val mcpClient = MCPClient(jsonString)
        assertEquals(jsonString, mcpClient.jsonString)
    }

    @Test
    fun `test executeTool when not connected`() {
        // Create a tool and execute it
        val tool = Tool(
            name = "test-tool",
            description = "A test tool",
            inputSchema = ToolSchema(
                properties = JsonObject(mapOf("param" to JsonPrimitive("value"))),
                required = listOf("param")
            )
        )

        val executeToolArgs = ExecuteToolArgs(
            arguments = JsonObject(mapOf("param" to JsonPrimitive("value")))
        )

        val mcpClient = MCPClient("{}")

        // This should return a default result because we haven't connected
        val result = runBlocking {
            mcpClient.executeTool(tool, executeToolArgs)
        }
        assertEquals("[MCP server not available]", result.content)
    }

    @Test
    fun `test tools when not connected`() {
        val mcpClient = MCPClient("{}")

        // This should return an empty list because we haven't connected
        val tools = runBlocking { mcpClient.tools() }
        assertEquals(emptyList(), tools)
    }
}
