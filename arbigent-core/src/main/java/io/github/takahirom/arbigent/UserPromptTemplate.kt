package io.github.takahirom.arbigent

public class UserPromptTemplate(
    private val template: String
) {
    public companion object {
        public const val USER_INPUT_GOAL: String = "{{USER_INPUT_GOAL}}"
        public const val CURRENT_STEP: String = "{{CURRENT_STEP}}"
        public const val MAX_STEP: String = "{{MAX_STEP}}"
        public const val STEPS: String = "{{STEPS}}"
        public const val UI_ELEMENTS: String = "{{UI_ELEMENTS}}"
        public const val FOCUSED_TREE: String = "{{FOCUSED_TREE}}"
        public const val ACTION_TEMPLATES: String = "{{ACTION_TEMPLATES}}"
        public const val APP_HINTS: String = "{{APP_HINTS}}"

        public val DEFAULT_TEMPLATE: String = """
<GOAL>$USER_INPUT_GOAL</GOAL>
$APP_HINTS
<STEP>
Current step: $CURRENT_STEP
Step limit: $MAX_STEP

<PREVIOUS_STEPS>
$STEPS
</PREVIOUS_STEPS>
</STEP>

<UI_STATE>
Please refer to the image.
<ELEMENTS>
index:element
$UI_ELEMENTS
</ELEMENTS>
<FOCUSED_TREE>
$FOCUSED_TREE
</FOCUSED_TREE>
</UI_STATE>

<INSTRUCTIONS>
Based on the above, decide on the next action to achieve the goal. Please ensure not to repeat the same action.
</INSTRUCTIONS>
""".trimIndent()
    }

    init {
        validate()
    }

    private fun validate() {
        val requiredPlaceholders = listOf(
            USER_INPUT_GOAL,
            CURRENT_STEP,
            MAX_STEP,
            STEPS
        )
        val optionalPlaceholders = listOf(
            UI_ELEMENTS,
            FOCUSED_TREE,
            APP_HINTS
        )
        val missingRequiredPlaceholders = requiredPlaceholders.filter { !template.contains(it) }
        if (missingRequiredPlaceholders.isNotEmpty()) {
            throw IllegalArgumentException(
                "Template must contain all required placeholders. Missing: ${missingRequiredPlaceholders.joinToString(", ")}"
            )
        }
        val placeholderRegex = """\{\{([^}]+)\}\}""".toRegex()
        val unknownPlaceholders = placeholderRegex.findAll(template)
            .map { it.groupValues[1] }
            .filter { placeholder -> 
                !(requiredPlaceholders + optionalPlaceholders).contains("{{$placeholder}}")
            }
            .toList()
        if (unknownPlaceholders.isNotEmpty()) {
            throw IllegalArgumentException(
                "Template contains unknown placeholders: ${unknownPlaceholders.joinToString(", ")}"
            )
        }
    }

    public fun format(
        goal: String,
        currentStep: Int,
        maxStep: Int,
        steps: String,
        uiElements: String = "",
        focusedTree: String = "",
        appHints: List<String> = emptyList(),
    ): String {
        val appHintsText = if (appHints.isNotEmpty()) {
            "\n<HINT_FROM_APP>\n${appHints.joinToString("\n") { "- $it" }}\n</HINT_FROM_APP>"
        } else ""
        return template
            .replace(USER_INPUT_GOAL, goal)
            .replace(CURRENT_STEP, currentStep.toString())
            .replace(MAX_STEP, maxStep.toString())
            .replace(STEPS, steps)
            .replace(UI_ELEMENTS, uiElements)
            .replace(FOCUSED_TREE, focusedTree)
            .replace(APP_HINTS, appHintsText)
    }
}
