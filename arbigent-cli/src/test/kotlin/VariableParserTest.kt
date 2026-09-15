package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktError
import io.github.takahirom.arbigent.isArbigentVariableName
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
    fun `parseVariables with spaces around equals`() {
        val input = "key1 = value1 , key2=value2"
        val result = parseVariables(input)
        
        assertEquals(mapOf("key1" to "value1", "key2" to "value2"), result)
    }
    
    @Test
    fun `parseVariables with empty string returns empty map`() {
        val input = ""
        val result = parseVariables(input)
        
        assertEquals(emptyMap(), result)
    }
    
    @Test
    fun `parseVariables with blank string returns empty map`() {
        val input = "   "
        val result = parseVariables(input)
        
        assertEquals(emptyMap(), result)
    }
    
    @Test
    fun `parseVariables with single key-value pair`() {
        val input = "key=value"
        val result = parseVariables(input)
        
        assertEquals(mapOf("key" to "value"), result)
    }
    
    @Test
    fun `parseVariables with values containing special characters`() {
        val input = "url=https://example.com,path=/usr/local/bin"
        val result = parseVariables(input)
        
        assertEquals(mapOf("url" to "https://example.com", "path" to "/usr/local/bin"), result)
    }
    
    @Test
    fun `parseVariables throws on invalid variable name`() {
        val input = "my@var=value"
        
        val exception = assertFailsWith<CliktError> {
            parseVariables(input)
        }
        assert(exception.message?.contains("Invalid variable name") == true)
    }
    
    @Test
    fun `parseVariables accepts every name a placeholder can carry`() {
        // A name the project YAML can define must be a name --variables can supply, or the
        // unresolved-variable message would suggest a command that cannot be run.
        assertEquals(mapOf("my-var" to "value"), parseVariables("my-var=value"))
        assertEquals(mapOf("app.id" to "com.example.app"), parseVariables("app.id=com.example.app"))
        assertEquals(mapOf("my var" to "value"), parseVariables("my var=value"))
        assertEquals(mapOf("123start" to "value"), parseVariables("123start=value"))
    }
    
    @Test
    fun `parseVariables throws on missing equals sign`() {
        val input = "key1 value1"
        
        val exception = assertFailsWith<CliktError> {
            parseVariables(input)
        }
        assert(exception.message?.contains("Expected 'key=value'") == true)
    }
    
    @Test
    fun `parseVariables handles multiple equals signs gracefully`() {
        val input = "key=value=extra"
        val result = parseVariables(input)
        
        // Should handle this gracefully by treating everything after first = as value
        assertEquals(mapOf("key" to "value=extra"), result)
    }
    
    @Test
    fun `isValidVariableName with valid names`() {
        assert(isValidVariableName("validName"))
        assert(isValidVariableName("_privateVar"))
        assert(isValidVariableName("var123"))
        assert(isValidVariableName("CONSTANT_NAME"))
        assert(isValidVariableName("_"))
        assert(isValidVariableName("a"))
        // Accepted by the shared grammar, so a name the project YAML can define is suppliable.
        assert(isValidVariableName("123start"))
        assert(isValidVariableName("my-var"))
        assert(isValidVariableName("my.var"))
        assert(isValidVariableName("my var"))
    }
    
    @Test
    fun `isValidVariableName with invalid names`() {
        assert(!isValidVariableName(""))
        assert(!isValidVariableName("my@var"))
        assert(!isValidVariableName("my/var"))
        assert(!isValidVariableName("my:var"))
    }
    
    // Quote handling tests
    
    @Test
    fun `parseVariables with simple quoted values`() {
        val input = """key="value""""
        val result = parseVariables(input)
        
        assertEquals(mapOf("key" to "value"), result)
    }
    
    @Test
    fun `parseVariables with values containing spaces in quotes`() {
        val input = """key="value with spaces""""
        val result = parseVariables(input)
        
        assertEquals(mapOf("key" to "value with spaces"), result)
    }
    
    @Test
    fun `parseVariables with URLs containing query params in quotes`() {
        val input = """url="https://example.com?foo=1""""
        val result = parseVariables(input)
        
        assertEquals(mapOf("url" to "https://example.com?foo=1"), result)
    }
    
    @Test
    fun `parseVariables with single quotes`() {
        val input = """key='value'"""
        val result = parseVariables(input)
        
        assertEquals(mapOf("key" to "value"), result)
    }
    
    @Test
    fun `parseVariables with mixed quoted and non-quoted values`() {
        val input = """key1=value1,key2="value 2""""
        val result = parseVariables(input)
        
        assertEquals(mapOf("key1" to "value1", "key2" to "value 2"), result)
    }
    
    @Test
    fun `parseVariables throws on unmatched quotes`() {
        val input = """key="value"""
        
        val exception = assertFailsWith<CliktError> {
            parseVariables(input)
        }
        assert(exception.message?.contains("Unmatched quote") == true)
    }
    
    @Test
    fun `parseVariables with empty quoted values`() {
        val input = """key="""""
        val result = parseVariables(input)
        
        assertEquals(mapOf("key" to ""), result)
    }
    
    @Test
    fun `parseVariables with values containing commas in quotes`() {
        val input = """key="value,with,commas""""
        val result = parseVariables(input)
        
        assertEquals(mapOf("key" to "value,with,commas"), result)
    }
    
    @Test
    fun `parseVariables with multiple quoted values`() {
        val input = """key1="value 1",key2="value 2",key3="value 3""""
        val result = parseVariables(input)
        
        assertEquals(mapOf("key1" to "value 1", "key2" to "value 2", "key3" to "value 3"), result)
    }
    
    @Test
    fun `parseVariables with quoted values containing equals signs`() {
        val input = """equation="x=y+2",url="https://example.com?param=value""""
        val result = parseVariables(input)
        
        assertEquals(mapOf("equation" to "x=y+2", "url" to "https://example.com?param=value"), result)
    }
    
    @Test
    fun `parseVariables with mixed single and double quotes`() {
        val input = """key1="double quoted",key2='single quoted'"""
        val result = parseVariables(input)
        
        assertEquals(mapOf("key1" to "double quoted", "key2" to "single quoted"), result)
    }
    
    @Test
    fun `parseVariables with quotes in the middle of value`() {
        val input = """key=before"quoted"after"""
        val result = parseVariables(input)
        
        // This tests current behavior - might need adjustment based on requirements
        assertEquals(mapOf("key" to """before"quoted"after"""), result)
    }
}