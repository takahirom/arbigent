package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.ArbigentScenarioContent.InitializationMethod
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReconnectInitializationMethodTest {
    
    @Test
    fun `Reconnect initialization method is a singleton object`() {
        val reconnect1 = InitializationMethod.Reconnect
        val reconnect2 = InitializationMethod.Reconnect
        assertEquals(reconnect1, reconnect2)
    }
    
    @Test
    fun `Reconnect initialization method serialization test`() {
        val projectSerializer = ArbigentProjectSerializer()
        val yamlContent = """
scenarios:
  - id: test-reconnect
    goal: "Test with reconnect"
    initializationMethods:
      - type: Reconnect
""".trimIndent()
        
        val projectFileContent = projectSerializer.load(yamlContent)
        val scenario = projectFileContent.scenarioContents.first()
        val reconnectMethod = scenario.initializationMethods.first()
        
        assertNotNull(reconnectMethod)
        assertTrue(reconnectMethod is InitializationMethod.Reconnect, "Expected Reconnect initialization method")
    }
}