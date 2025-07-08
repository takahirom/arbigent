package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktError
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VariableParserTest {
    
    @Test
    fun `parseVariables with simple key-value pairs`() {
        val input = "env=production,timeout=30"
        val result = parseVariables(input)
        
        assertEquals(mapOf("env" to "production", "timeout" to "30"), result)
    }
    
    @Test
    fun `parseVariables with quoted values`() {
        val input = """url="https://example.com",name="John Doe""""
        val result = parseVariables(input)
        
        assertEquals(mapOf("url" to "https://example.com", "name" to "John Doe"), result)
    }
    
    @Test
    fun `parseVariables with mixed quoted values`() {
        val input = """url='https://example.com',name="John Doe",id=123"""
        val result = parseVariables(input)
        
        assertEquals(mapOf("url" to "https://example.com", "name" to "John Doe", "id" to "123"), result)
    }
    
    @Test
    fun `parseVariables with JSON format`() {
        val input = """{"apiKey":"sk-123","userId":"user456","enabled":"true"}"""
        val result = parseVariables(input)
        
        assertEquals(mapOf("apiKey" to "sk-123", "userId" to "user456", "enabled" to "true"), result)
    }
    
    @Test
    fun `parseVariables with JSON containing spaces`() {
        val input = """{ "apiKey" : "sk-123" , "userId" : "user456" }"""
        val result = parseVariables(input)
        
        assertEquals(mapOf("apiKey" to "sk-123", "userId" to "user456"), result)
    }
    
    @Test
    fun `parseVariables with escaped quotes in values`() {
        val input = """message="Hello \"World\"",path='C:\\Users\\John'"""
        val result = parseVariables(input)
        
        assertEquals(mapOf("message" to """Hello "World"""", "path" to """C:\Users\John"""), result)
    }
    
    @Test
    fun `parseVariables with spaces around equals`() {
        val input = "key1 = value1 , key2=value2"
        val result = parseVariables(input)
        
        assertEquals(mapOf("key1" to "value1", "key2" to "value2"), result)
    }
    
    @Test
    fun `parseVariables with empty values`() {
        val input = """key1=,key2="",key3=''"""
        val result = parseVariables(input)
        
        assertEquals(mapOf("key1" to "", "key2" to "", "key3" to ""), result)
    }
    
    @Test
    fun `parseVariables with complex JSON values`() {
        val input = """{"config":{"retries":3},"array":[1,2,3]}"""
        val result = parseVariables(input)
        
        assertEquals(mapOf("config" to """{"retries":3}""", "array" to "[1,2,3]"), result)
    }
    
    @Test
    fun `parseVariables throws on invalid variable name`() {
        val input = "123invalid=value"
        
        val exception = assertFailsWith<CliktError> {
            parseVariables(input)
        }
        assert(exception.message?.contains("Invalid variable name") == true)
    }
    
    @Test
    fun `parseVariables throws on invalid variable name with special chars`() {
        val input = "my-var=value"
        
        val exception = assertFailsWith<CliktError> {
            parseVariables(input)
        }
        assert(exception.message?.contains("Invalid variable name") == true)
    }
    
    @Test
    fun `parseVariables throws on missing equals sign`() {
        val input = "key1 value1"
        
        val exception = assertFailsWith<CliktError> {
            parseVariables(input)
        }
        assert(exception.message?.contains("Expected '='") == true)
    }
    
    @Test
    fun `parseVariables throws on unterminated quoted value`() {
        val input = """key="unterminated"""
        
        val exception = assertFailsWith<CliktError> {
            parseVariables(input)
        }
        assert(exception.message?.contains("Unterminated quoted value") == true)
    }
    
    @Test
    fun `parseVariables throws on invalid JSON`() {
        val input = """{"key":"value",}"""
        
        val exception = assertFailsWith<CliktError> {
            parseVariables(input)
        }
        assert(exception.message?.contains("Invalid JSON format") == true)
    }
    
    @Test
    fun `parseVariables throws on JSON with invalid variable name`() {
        val input = """{"123key":"value"}"""
        
        val exception = assertFailsWith<CliktError> {
            parseVariables(input)
        }
        assert(exception.message?.contains("Invalid variable name") == true)
    }
    
    @Test
    fun `isValidVariableName with valid names`() {
        assert(isValidVariableName("validName"))
        assert(isValidVariableName("_privateVar"))
        assert(isValidVariableName("var123"))
        assert(isValidVariableName("CONSTANT_NAME"))
        assert(isValidVariableName("_"))
        assert(isValidVariableName("a"))
    }
    
    @Test
    fun `isValidVariableName with invalid names`() {
        assert(!isValidVariableName(""))
        assert(!isValidVariableName("123start"))
        assert(!isValidVariableName("my-var"))
        assert(!isValidVariableName("my.var"))
        assert(!isValidVariableName("my var"))
        assert(!isValidVariableName("my@var"))
    }
}