package io.github.takahirom.arbigent

import kotlin.test.Test
import kotlin.test.assertEquals

class ArbigentContextHolderTest {
    private val defaultAiOptions = ArbigentAiOptions(
        temperature = null,
        imageDetail = null,
        imageFormat = null,
        historicalStepLimit = null
    )
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

        val stepsText = contextHolder.getStepsText(defaultAiOptions)

        // Verify step text contains all components
        assertEquals(true, stepsText.contains("Step 1"))
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
            aiOptions = defaultAiOptions
        )

        // Verify basic content
        assertEquals(true, prompt.contains("<GOAL>Test Goal</GOAL>"))
        assertEquals(true, prompt.contains("Current step: 1"))
        assertEquals(true, prompt.contains("Step limit: 5"))

        // Verify UI elements
        assertEquals(true, prompt.contains("Button1: Click me"))
        assertEquals(true, prompt.contains("Tree Structure"))
    }

    @Test
    fun testGetStepsTextWithLastStepCount() {
        val contextHolder = ArbigentContextHolder(
            goal = "Test Goal",
            maxStep = 5
        )

        // Add three test steps
        for (i in 1..3) {
            val step = ArbigentContextHolder.Step(
                stepId = "test_step_$i",
                action = "Test Action $i",
                feedback = "Test Feedback $i",
                memo = "Test Memo $i",
                imageDescription = "Test Image $i",
                cacheKey = "test_cache_$i",
                screenshotFilePath = "test_screenshot_$i.png"
            )
            contextHolder.addStep(step)
        }

        // Test without lastStepCount (should show all steps)
        val allStepsText = contextHolder.getStepsText(defaultAiOptions)
        assertEquals(true, allStepsText.contains("Step 1"))
        assertEquals(true, allStepsText.contains("Test Feedback 1"))
        assertEquals(true, allStepsText.contains("Step 3"))
        assertEquals(true, allStepsText.contains("Test Feedback 3"))

        // Test with lastStepCount = 2 (should show only last 2 steps)
        val limitedAiOptions = ArbigentAiOptions(
            temperature = null,
            imageDetail = null,
            imageFormat = null,
            historicalStepLimit = 2
        )
        val lastTwoStepsText = contextHolder.getStepsText(limitedAiOptions)
        assertEquals(false, lastTwoStepsText.contains("Test Feedback 1"))
        assertEquals(true, lastTwoStepsText.contains("Test Feedback 2"))
        assertEquals(true, lastTwoStepsText.contains("Test Feedback 3"))
    }

    @Test
    fun testPromptWithLastStepCount() {
        val contextHolder = ArbigentContextHolder(
            goal = "Test Goal",
            maxStep = 5
        )

        // Add three test steps
        for (i in 1..3) {
            val step = ArbigentContextHolder.Step(
                stepId = "test_step_$i",
                action = "Test Action $i",
                feedback = "Test Feedback $i",
                cacheKey = "test_cache_$i",
                screenshotFilePath = "test_screenshot_$i.png"
            )
            contextHolder.addStep(step)
        }

        val limitedAiOptions = ArbigentAiOptions(
            temperature = null,
            imageDetail = null,
            imageFormat = null,
            historicalStepLimit = 2
        )
        val prompt = contextHolder.prompt(
            uiElements = "Button1: Click me",
            focusedTree = "Tree Structure",
            aiOptions = limitedAiOptions
        )

        // Verify that only last 2 steps are included
        assertEquals(false, prompt.contains("Test Feedback 1"))
        assertEquals(true, prompt.contains("Test Feedback 2"))
        assertEquals(true, prompt.contains("Test Feedback 3"))
    }

    @Test
    fun testContextWithLastStepCount() {
        val contextHolder = ArbigentContextHolder(
            goal = "Test Goal",
            maxStep = 5
        )

        // Add three test steps
        for (i in 1..3) {
            val step = ArbigentContextHolder.Step(
                stepId = "test_step_$i",
                action = "Test Action $i",
                feedback = "Test Feedback $i",
                cacheKey = "test_cache_$i",
                screenshotFilePath = "test_screenshot_$i.png"
            )
            contextHolder.addStep(step)
        }

        val limitedAiOptions = ArbigentAiOptions(
            temperature = null,
            imageDetail = null,
            imageFormat = null,
            historicalStepLimit = 2
        )
        val context = contextHolder.context(limitedAiOptions)

        // Verify that only last 2 steps are included
        assertEquals(false, context.contains("Test Feedback 1"))
        assertEquals(true, context.contains("Test Feedback 2"))
        assertEquals(true, context.contains("Test Feedback 3"))
    }

    @Test
    fun testCountMeaningfulActions() {
        val contextHolder = ArbigentContextHolder(
            goal = "Test Goal",
            maxStep = 5
        )

        // Initially no meaningful actions
        assertEquals(0, contextHolder.countMeaningfulActions())

        // Add step without agentAction (context only)
        val contextStep = ArbigentContextHolder.Step(
            stepId = "context_step",
            action = "Context Action",
            feedback = "Context Feedback",
            cacheKey = "context_cache",
            screenshotFilePath = "context_screenshot.png"
        )
        contextHolder.addStep(contextStep)
        assertEquals(0, contextHolder.countMeaningfulActions())

        // Add step with meaningful action
        val meaningfulStep = ArbigentContextHolder.Step(
            stepId = "meaningful_step",
            agentAction = ClickWithIndex(1),
            action = "Click Action",
            feedback = "Click Feedback",
            cacheKey = "click_cache",
            screenshotFilePath = "click_screenshot.png"
        )
        contextHolder.addStep(meaningfulStep)
        assertEquals(1, contextHolder.countMeaningfulActions())

        // Add step with FailedAgentAction (should not count)
        val failedStep = ArbigentContextHolder.Step(
            stepId = "failed_step",
            agentAction = FailedAgentAction(),
            action = "Failed Action",
            feedback = "Failed Feedback",
            cacheKey = "failed_cache",
            screenshotFilePath = "failed_screenshot.png"
        )
        contextHolder.addStep(failedStep)
        assertEquals(1, contextHolder.countMeaningfulActions())

        // Add another meaningful action
        val anotherMeaningfulStep = ArbigentContextHolder.Step(
            stepId = "another_meaningful_step",
            agentAction = InputTextAgentAction("test input"),
            action = "Input Action",
            feedback = "Input Feedback",
            cacheKey = "input_cache",
            screenshotFilePath = "input_screenshot.png"
        )
        contextHolder.addStep(anotherMeaningfulStep)
        assertEquals(2, contextHolder.countMeaningfulActions())

        // Add GoalAchievedAgentAction (should count)
        val goalAchievedStep = ArbigentContextHolder.Step(
            stepId = "goal_achieved_step",
            agentAction = GoalAchievedAgentAction(),
            action = "Goal Achieved Action",
            feedback = "Goal Achieved Feedback",
            cacheKey = "goal_cache",
            screenshotFilePath = "goal_screenshot.png"
        )
        contextHolder.addStep(goalAchievedStep)
        assertEquals(3, contextHolder.countMeaningfulActions())
    }

    @Test
    fun testPromptUsesCountMeaningfulActions() {
        val contextHolder = ArbigentContextHolder(
            goal = "Test Goal",
            maxStep = 5
        )

        // Add context step and meaningful action
        val contextStep = ArbigentContextHolder.Step(
            stepId = "context_step",
            action = "Context Action",
            feedback = "Context Feedback",
            cacheKey = "context_cache",
            screenshotFilePath = "context_screenshot.png"
        )
        contextHolder.addStep(contextStep)
        
        val meaningfulStep = ArbigentContextHolder.Step(
            stepId = "meaningful_step",
            agentAction = ClickWithIndex(1),
            action = "Click Action",
            feedback = "Click Feedback",
            cacheKey = "click_cache",
            screenshotFilePath = "click_screenshot.png"
        )
        contextHolder.addStep(meaningfulStep)
        
        val failedStep = ArbigentContextHolder.Step(
            stepId = "failed_step",
            agentAction = FailedAgentAction(),
            action = "Failed Action",
            feedback = "Failed Feedback",
            cacheKey = "failed_cache",
            screenshotFilePath = "failed_screenshot.png"
        )
        contextHolder.addStep(failedStep)

        val prompt = contextHolder.prompt(
            uiElements = "Button1: Click me",
            focusedTree = "Tree Structure",
            aiOptions = defaultAiOptions
        )

        // Should show currentStep as 2 (1 meaningful action + 1 for next step)
        // Not 4 (3 total steps + 1 for next step)
        assertEquals(true, prompt.contains("Current step: 2"))
        assertEquals(true, prompt.contains("Step limit: 5"))
    }
}
