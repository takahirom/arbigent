package io.github.takahirom.arbigent

import kotlin.test.Test
import kotlin.test.assertEquals

class ArbigentContextHolderTest {
    @Test
    fun testGetStepsText() {
        val contextHolder = ArbigentContextHolder(
            goal = "Test Goal",
            maxStep = 5
        )

        // Add a test step
        val step = ArbigentContextHolder.Step(
            stepId = "test_step",
            action = "Test Action",
            feedback = "Test Feedback",
            memo = "Test Memo",
            imageDescription = "Test Image",
            cacheKey = "test_cache",
            screenshotFilePath = "test_screenshot.png"
        )
        contextHolder.addStep(step)

        val stepsText = contextHolder.getStepsText()

        // Verify step text contains all components
        assertEquals(true, stepsText.contains("Step 1"))
        assertEquals(true, stepsText.contains("Test Action"))
        assertEquals(true, stepsText.contains("Test Feedback"))
        assertEquals(true, stepsText.contains("Test Memo"))
        assertEquals(true, stepsText.contains("Test Image"))
    }

    @Test
    fun testPromptWithUIElements() {
        val contextHolder = ArbigentContextHolder(
            goal = "Test Goal",
            maxStep = 5
        )

        val prompt = contextHolder.prompt(
            uiElements = "Button1: Click me",
            focusedTree = "Tree Structure",
            commandTemplates = "Command1\nCommand2"
        )

        // Verify basic content
        assertEquals(true, prompt.contains("<GOAL>Test Goal</GOAL>"))
        assertEquals(true, prompt.contains("Current step: 1"))
        assertEquals(true, prompt.contains("Max step: 5"))

        // Verify UI elements
        assertEquals(true, prompt.contains("Button1: Click me"))
        assertEquals(true, prompt.contains("Tree Structure"))
        assertEquals(true, prompt.contains("Command1"))
        assertEquals(true, prompt.contains("Command2"))
    }
}
