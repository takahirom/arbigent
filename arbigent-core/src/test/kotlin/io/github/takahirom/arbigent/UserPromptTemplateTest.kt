package io.github.takahirom.arbigent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserPromptTemplateTest {
    @Test
    fun testValidTemplate() {
        val template = UserPromptTemplate("""
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
            UserPromptTemplate("""
                Goal:"{{USER_INPUT_GOAL}}"

                Your step:{{CURRENT_STEP}}
                Max step:{{MAX_STEP}}

                What you did so far:
                // Missing STEPS placeholder
            """.trimIndent())
        }
    }

    @Test
    fun testCommandTemplatesRejected() {
        val exception = assertFailsWith<IllegalArgumentException> {
            UserPromptTemplate("""
                Goal:"{{USER_INPUT_GOAL}}"
                Your step:{{CURRENT_STEP}}
                Max step:{{MAX_STEP}}
                What you did so far:
                {{STEPS}}
                Available commands:
                {{COMMAND_TEMPLATES}}
            """.trimIndent())
        }
        assertEquals(
            "Template contains unknown placeholders: COMMAND_TEMPLATES",
            exception.message
        )
    }

    @Test
    fun testDefaultTemplate() {
        val template = UserPromptTemplate(UserPromptTemplate.DEFAULT_TEMPLATE)

        val result = template.format(
            goal = "Test goal",
            currentStep = 2,
            maxStep = 5,
            steps = "Step 1: Action\nStep 2: Another action"
        )

        assertEquals("""
<GOAL>Test goal</GOAL>

<STEP>
Current step: 2
Max step: 5

<PREVIOUS_STEPS>
Step 1: Action
Step 2: Another action
</PREVIOUS_STEPS>
</STEP>

<UI_STATE>
Please refer to the image above.
<ELEMENTS>
index:element

</ELEMENTS>
<FOCUSED_TREE>

</FOCUSED_TREE>
</UI_STATE>

<INSTRUCTIONS>
Based on the above, decide on the next action to achieve the goal. Please ensure not to repeat the same action. The action must be one of the following:
</INSTRUCTIONS>

<ACTION_OPTIONS>

</ACTION_OPTIONS>
""".trimIndent(), result)
    }

    @Test
    fun showDefaultTemplate() {
        println(UserPromptTemplate.DEFAULT_TEMPLATE)
    }
}
