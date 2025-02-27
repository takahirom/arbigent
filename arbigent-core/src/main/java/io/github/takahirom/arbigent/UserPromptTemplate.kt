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
        public const val COMMAND_TEMPLATES: String = "{{COMMAND_TEMPLATES}}"

        public val DEFAULT_TEMPLATE: String = """
            Goal:"$USER_INPUT_GOAL"

            Your step:$CURRENT_STEP
            Max step:$MAX_STEP

            What you did so far:
            $STEPS

            UI Index to Element Map:
            $UI_ELEMENTS
            $FOCUSED_TREE
            Based on the above, decide on the next action to achieve the goal. Please ensure not to repeat the same action. The action must be one of the following:
            $COMMAND_TEMPLATES
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
            COMMAND_TEMPLATES
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
        commandTemplates: String = ""
    ): String {
        return template
            .replace(USER_INPUT_GOAL, goal)
            .replace(CURRENT_STEP, currentStep.toString())
            .replace(MAX_STEP, maxStep.toString())
            .replace(STEPS, steps)
            .replace(UI_ELEMENTS, uiElements)
            .replace(FOCUSED_TREE, focusedTree)
            .replace(COMMAND_TEMPLATES, commandTemplates)
    }
}
