package io.github.takahirom.arbigent

public class UserPromptTemplate(
    private val template: String
) {
    public companion object {
        public const val USER_INPUT_GOAL: String = "{{USER_INPUT_GOAL}}"
        public const val CURRENT_STEP: String = "{{CURRENT_STEP}}"
        public const val MAX_STEP: String = "{{MAX_STEP}}"
        public const val STEPS: String = "{{STEPS}}"

        public val DEFAULT_TEMPLATE: String = """
            Goal:"$USER_INPUT_GOAL"

            Your step:$CURRENT_STEP
            Max step:$MAX_STEP

            What you did so far:
            $STEPS
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
        val missingPlaceholders = requiredPlaceholders.filter { !template.contains(it) }
        if (missingPlaceholders.isNotEmpty()) {
            throw IllegalArgumentException(
                "Template must contain all required placeholders. Missing: ${missingPlaceholders.joinToString(", ")}"
            )
        }
    }

    public fun format(
        goal: String,
        currentStep: Int,
        maxStep: Int,
        steps: String
    ): String {
        return template
            .replace(USER_INPUT_GOAL, goal)
            .replace(CURRENT_STEP, currentStep.toString())
            .replace(MAX_STEP, maxStep.toString())
            .replace(STEPS, steps)
    }
}