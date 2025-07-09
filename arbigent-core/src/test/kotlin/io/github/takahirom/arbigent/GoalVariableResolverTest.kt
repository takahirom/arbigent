package io.github.takahirom.arbigent

import org.junit.Test
import kotlin.test.assertEquals

class GoalVariableResolverTest {
    
    @Test
    fun `resolve should replace single variable`() {
        val goal = "Login with user {{user_id}}"
        val variables = mapOf("user_id" to "john.doe@example.com")
        
        val result = GoalVariableResolver.resolve(goal, variables)
        
        assertEquals("Login with user john.doe@example.com", result)
    }
    
    @Test
    fun `resolve should replace multiple variables`() {
        val goal = "Login with {{user_id}} and password {{password}} on {{environment}}"
        val variables = mapOf(
            "user_id" to "john.doe",
            "password" to "secret123",
            "environment" to "production"
        )
        
        val result = GoalVariableResolver.resolve(goal, variables)
        
        assertEquals("Login with john.doe and password secret123 on production", result)
    }
    
    @Test
    fun `resolve should keep unresolved variables when not found`() {
        val goal = "Login with {{user_id}} and {{undefined_var}}"
        val variables = mapOf("user_id" to "john.doe")
        
        val result = GoalVariableResolver.resolve(goal, variables)
        
        assertEquals("Login with john.doe and {{undefined_var}}", result)
    }
    
    @Test
    fun `resolve should handle escaped variables`() {
        val goal = "Display text \\{{user_id}} literally"
        val variables = mapOf("user_id" to "john.doe")
        
        val result = GoalVariableResolver.resolve(goal, variables)
        
        assertEquals("Display text {{user_id}} literally", result)
    }
    
    @Test
    fun `resolve should handle mixed escaped and unescaped variables`() {
        val goal = "User {{user_id}} wants to see \\{{user_id}} as text"
        val variables = mapOf("user_id" to "john.doe")
        
        val result = GoalVariableResolver.resolve(goal, variables)
        
        assertEquals("User john.doe wants to see {{user_id}} as text", result)
    }
    
    @Test
    fun `resolve should return original goal when variables is null`() {
        val goal = "Login with {{user_id}}"
        
        val result = GoalVariableResolver.resolve(goal, null)
        
        assertEquals("Login with {{user_id}}", result)
    }
    
    @Test
    fun `resolve should return original goal when variables is empty`() {
        val goal = "Login with {{user_id}}"
        val variables = emptyMap<String, String>()
        
        val result = GoalVariableResolver.resolve(goal, variables)
        
        assertEquals("Login with {{user_id}}", result)
    }
    
    @Test
    fun `resolve should handle goals without variables`() {
        val goal = "Login and verify dashboard"
        val variables = mapOf("user_id" to "john.doe")
        
        val result = GoalVariableResolver.resolve(goal, variables)
        
        assertEquals("Login and verify dashboard", result)
    }
    
    @Test
    fun `resolve should handle special characters in variable values`() {
        val goal = "Search for {{query}}"
        val variables = mapOf("query" to "test@example.com & special <chars>")
        
        val result = GoalVariableResolver.resolve(goal, variables)
        
        assertEquals("Search for test@example.com & special <chars>", result)
    }
    
    @Test
    fun `resolve should handle variables with spaces in names`() {
        val goal = "Use {{user name}} for login"
        val variables = mapOf("user name" to "John Doe")
        
        val result = GoalVariableResolver.resolve(goal, variables)
        
        assertEquals("Use John Doe for login", result)
    }
}