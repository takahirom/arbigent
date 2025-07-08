package io.github.takahirom.arbigent

/**
 * Resolves variables in goal strings by replacing {{variable_name}} patterns
 * with their corresponding values from the provided variables map.
 */
public object GoalVariableResolver {
    private val delegate = DefaultGoalVariableResolver()
    
    /**
     * Resolves variables in the goal string.
     * Example: "Login with {{user_id}}" -> "Login with john.doe@example.com"
     * Escaped variables (\{{user_id}}) are converted to {{user_id}}
     */
    public fun resolve(goal: String, variables: Map<String, String>?): String = 
        delegate.resolve(goal, variables)
}

/**
 * Interface for resolving variables in goal strings.
 */
public interface GoalVariableResolverInterface {
    /**
     * Resolves variables in the goal string.
     */
    public fun resolve(goal: String, variables: Map<String, String>?): String
}

/** Factory for creating GoalVariableResolver instances. */
public object GoalVariableResolverFactory {
    /** Creates a default implementation of GoalVariableResolverInterface */
    public fun create(): GoalVariableResolverInterface = DefaultGoalVariableResolver()
}

/** Default implementation of GoalVariableResolverInterface. */
internal class DefaultGoalVariableResolver : GoalVariableResolverInterface {
    companion object {
        // Allow alphanumeric, underscore, dash, dot, and space in variable names
        // Disallow only dangerous characters like }, {, <, >, &, |, ;, $, `, \, etc.
        private val VALID_VARIABLE_NAME = """^[a-zA-Z0-9_.\-\s]+$""".toRegex()
        private const val MAX_VARIABLE_VALUE_LENGTH = 10_000
        private const val MAX_GOAL_LENGTH = 100_000
        
        // Pre-compiled regex patterns for performance
        private val VARIABLE_PATTERN = """\{\{([^}]+)\}\}""".toRegex()
        private val ESCAPED_VARIABLE_PATTERN = """\\\{\{([^}]+)\}\}""".toRegex()
        
        // Escape sequences for temporary replacement
        private const val TEMP_PREFIX = "\u0000ESCAPED_"
        private const val TEMP_SUFFIX = "_ESCAPED\u0000"
    }

    override fun resolve(goal: String, variables: Map<String, String>?): String {
        // Validate goal length
        if (goal.length > MAX_GOAL_LENGTH) {
            throw SecurityException("Goal string exceeds maximum length of $MAX_GOAL_LENGTH characters")
        }
        
        // Early return for empty variables
        if (variables.isNullOrEmpty()) {
            return handleEscapedVariables(goal)
        }
        
        // Validate all variables upfront for better performance
        validateVariables(variables)
        
        // Process the goal string
        return processGoal(goal, variables)
    }
    
    private fun validateVariables(variables: Map<String, String>) {
        variables.forEach { (name, value) ->
            // Validate variable name
            if (!VALID_VARIABLE_NAME.matches(name)) {
                throw IllegalArgumentException(
                    "Invalid variable name: '$name'. " +
                    "Variable names must contain only alphanumeric characters, underscores, dashes, dots, and spaces."
                )
            }
            
            // Validate variable value length
            if (value.length > MAX_VARIABLE_VALUE_LENGTH) {
                throw SecurityException(
                    "Variable '$name' value exceeds maximum length of $MAX_VARIABLE_VALUE_LENGTH characters"
                )
            }
        }
    }
    
    private fun processGoal(goal: String, variables: Map<String, String>): String {
        // First handle escaped variables by temporarily replacing them
        val goalWithTempMarkers = ESCAPED_VARIABLE_PATTERN.replace(goal) { matchResult ->
            val variableName = matchResult.groupValues[1]
            "$TEMP_PREFIX$variableName$TEMP_SUFFIX"
        }
        
        // Then replace non-escaped variables
        var substitutionCount = 0
        val substitutedVariables = mutableMapOf<String, String>()
        
        val resolvedGoal = VARIABLE_PATTERN.replace(goalWithTempMarkers) { matchResult ->
            val variableName = matchResult.groupValues[1].trim()
            
            // Check if variable name is valid
            if (!VALID_VARIABLE_NAME.matches(variableName)) {
                arbigentWarnLog("Invalid variable name format in goal: '$variableName'. Keeping original placeholder.")
                return@replace matchResult.value
            }
            
            // Replace with value or keep original
            variables[variableName]?.let { value ->
                substitutionCount++
                substitutedVariables[variableName] = value
                value
            } ?: run {
                arbigentDebugLog("Variable '$variableName' not found in variables map. Keeping original placeholder.")
                matchResult.value
            }
        }
        
        // Log substitution info if any variables were replaced
        if (substitutionCount > 0) {
            arbigentInfoLog("Goal variables substituted: $substitutedVariables")
        }
        
        // Finally, restore escaped variables
        return resolvedGoal
            .replace(TEMP_PREFIX, "{{")
            .replace(TEMP_SUFFIX, "}}")
    }
    
    private fun handleEscapedVariables(goal: String): String {
        // Only process escaped variables when no variables are provided
        return ESCAPED_VARIABLE_PATTERN.replace(goal) { "{{${it.groupValues[1]}}}" }
    }
}

