package io.github.takahirom.arbigent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `test executeTool`() {
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
        
        // This should throw an IllegalStateException because we haven't connected
        assertFailsWith<IllegalStateException> {
            mcpClient.executeTool(tool, executeToolArgs)
        }
    }
}