package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.PromptTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromptTemplateTest {
    @Test
    fun testValidTemplate() {
        val template = PromptTemplate("""
            Goal:"{{USER_INPUT_GOAL}}"
            
            Your step:{{CURRENT_STEP}}
            Max step:{{MAX_STEP}}
            
            What you did so far:
            {{STEPS}}
        """.trimIndent())

        val result = template.format(
            goal = "Test goal",
            currentStep = 1,
            maxStep = 10,
            steps = "Step 1: Did something"
        )

        assertEquals("""
            Goal:"Test goal"
            
            Your step:1
            Max step:10
            
            What you did so far:
            Step 1: Did something
        """.trimIndent(), result)
    }

    @Test
    fun testMissingPlaceholder() {
        assertFailsWith<IllegalArgumentException> {
            PromptTemplate("""
                Goal:"{{USER_INPUT_GOAL}}"
                
                Your step:{{CURRENT_STEP}}
                Max step:{{MAX_STEP}}
                
                What you did so far:
                // Missing STEPS placeholder
            """.trimIndent())
        }
    }

    @Test
    fun testDefaultTemplate() {
        val template = PromptTemplate(PromptTemplate.DEFAULT_TEMPLATE)
        
        val result = template.format(
            goal = "Test goal",
            currentStep = 2,
            maxStep = 5,
            steps = "Step 1: Action\nStep 2: Another action"
        )

        assertEquals("""
            Goal:"Test goal"

            Your step:2
            Max step:5

            What you did so far:
            Step 1: Action
            Step 2: Another action
        """.trimIndent(), result)
    }
}