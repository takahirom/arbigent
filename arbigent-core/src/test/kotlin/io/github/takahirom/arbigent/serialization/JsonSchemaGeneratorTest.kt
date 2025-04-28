package io.github.takahirom.arbigent.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonSchemaGeneratorTest {
    @Test
    fun testGenerateJsonSchema() {
        // Create a sample data class for testing
        @Serializable
        data class TestUser(
            val id: Int,
            val name: String,
            val email: String?,
            val roles: List<String> = listOf()
        )

        // Get the descriptor for the TestUser class
        val descriptor: SerialDescriptor = serializer<TestUser>().descriptor

        // Generate JSON Schema from the descriptor
        val jsonSchema = generateRootJsonSchema(descriptor)

        // Verify the basic structure of the generated schema
        assertEquals(JsonPrimitive("http://json-schema.org/draft-07/schema#"), jsonSchema["\$schema"])
        assertEquals(JsonPrimitive("io.github.takahirom.arbigent.serialization.JsonSchemaGeneratorTest.testGenerateJsonSchema.TestUser"), jsonSchema["title"])
        assertEquals(JsonPrimitive("object"), jsonSchema["type"])

        // Verify properties
        val properties = jsonSchema["properties"] as JsonObject
        assertTrue(properties.containsKey("id"))
        assertTrue(properties.containsKey("name"))
        assertTrue(properties.containsKey("email"))
        assertTrue(properties.containsKey("roles"))

        // Verify property types
        val idProperty = properties["id"] as JsonObject
        assertEquals(JsonPrimitive("integer"), idProperty["type"])

        val nameProperty = properties["name"] as JsonObject
        assertEquals(JsonPrimitive("string"), nameProperty["type"])

        val emailProperty = properties["email"] as JsonObject
        val emailType = emailProperty["type"]
        assertTrue(emailType.toString().contains("null"), "Email property should be nullable")

        val rolesProperty = properties["roles"] as JsonObject
        assertEquals(JsonPrimitive("array"), rolesProperty["type"])

        // Verify required properties
        val required = jsonSchema["required"] as JsonArray
        val requiredString = required.toString()
        assertTrue(requiredString.contains("id"), "Required properties should contain 'id'")
        assertTrue(requiredString.contains("name"), "Required properties should contain 'name'")
        assertTrue(!requiredString.contains("email"), "Required properties should not contain 'email' as it's nullable")
    }
}
