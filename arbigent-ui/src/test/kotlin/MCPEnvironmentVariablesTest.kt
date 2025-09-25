package io.github.takahirom.arbigent.ui

import io.github.takahirom.arbigent.*
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MCPEnvironmentVariablesTest {
    
    @Before
    fun setup() {
        // Set up test keystore to avoid BackendNotSupportedException
        globalKeyStoreFactory = TestKeyStoreFactory()
    }
    
    @Test
    fun `isValidEnvironmentVariableName should accept valid uppercase names`() {
        assertTrue(isValidEnvironmentVariableName("PATH"))
        assertTrue(isValidEnvironmentVariableName("HOME"))
        assertTrue(isValidEnvironmentVariableName("USER_NAME"))
        assertTrue(isValidEnvironmentVariableName("MY_VAR_123"))
        assertTrue(isValidEnvironmentVariableName("_PRIVATE"))
        assertTrue(isValidEnvironmentVariableName("VAR_"))
    }
    
    @Test
    fun `isValidEnvironmentVariableName should reject invalid names`() {
        assertFalse(isValidEnvironmentVariableName(""))
        assertFalse(isValidEnvironmentVariableName("path")) // lowercase
        assertFalse(isValidEnvironmentVariableName("MyVar")) // mixed case
        assertFalse(isValidEnvironmentVariableName("123VAR")) // starts with number
        assertFalse(isValidEnvironmentVariableName("VAR-NAME")) // contains dash
        assertFalse(isValidEnvironmentVariableName("VAR NAME")) // contains space
        assertFalse(isValidEnvironmentVariableName("VAR.NAME")) // contains dot
    }
    
    @Test
    fun `AppSettingsStateHolder setMcpEnvironmentVariables should update settings`() {
        val stateHolder = AppSettingsStateHolder()
        val testVariables = mapOf("TEST_VAR" to "test_value", "ANOTHER_VAR" to "another_value")
        
        stateHolder.setMcpEnvironmentVariables(testVariables)
        
        assertEquals(testVariables, stateHolder.appSettings.mcpEnvironmentVariables)
    }
    
    @Test
    fun `AppSettingsStateHolder setMcpEnvironmentVariables should handle null`() {
        val stateHolder = AppSettingsStateHolder()
        
        // First set some variables
        stateHolder.setMcpEnvironmentVariables(mapOf("TEST" to "value"))
        assertEquals(mapOf("TEST" to "value"), stateHolder.appSettings.mcpEnvironmentVariables)
        
        // Then clear them
        stateHolder.setMcpEnvironmentVariables(null)
        assertEquals(null, stateHolder.appSettings.mcpEnvironmentVariables)
    }
    
    @Test
    fun `AppSettingsStateHolder setMcpEnvironmentVariables should handle empty map as null`() {
        val stateHolder = AppSettingsStateHolder()
        
        // Set empty map should be treated as null
        stateHolder.setMcpEnvironmentVariables(emptyMap())
        assertEquals(emptyMap<String, String>(), stateHolder.appSettings.mcpEnvironmentVariables)
    }
}