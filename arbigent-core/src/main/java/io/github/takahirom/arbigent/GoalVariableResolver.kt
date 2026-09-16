package io.github.takahirom.arbigent

/**
 * Resolves variables in goal strings by replacing {{variable_name}} patterns
 * with their corresponding values from the provided variables map.
 */
/**
 * Variable names [GoalVariableResolver] is willing to substitute. An unescaped `{{...}}` whose
 * name does not match is not a variable reference at all: it is left in the text untouched, so
 * callers that reject unresolved variables must ignore it too.
 */
internal val ValidArbigentVariableName: Regex = """^[a-zA-Z0-9_.\-\s]+$""".toRegex()

/**
 * The two patterns substitution is built on, shared so anything that decides whether a `{{...}}`
 * is a variable reference decides it exactly as [GoalVariableResolver] does. Escapes are matched
 * and masked first; only what is left is a reference.
 */
/**
 * Whether [name] is a name Arbigent substitutes. Exposed so every way of supplying a variable —
 * the project YAML, the CLI, the UI — accepts exactly the names a `{{...}}` reference can carry.
 */
public fun isArbigentVariableName(name: String): Boolean = ValidArbigentVariableName.matches(name)

internal val ArbigentEscapedVariablePattern: Regex = """\\\{\{([^}]+)\}\}""".toRegex()
internal val ArbigentVariablePattern: Regex = """\{\{([^}]+)\}\}""".toRegex()


// The markers substitution swaps an escape for while it substitutes the rest. They hold the
// escape's own text, so the braces inside it keep affecting where the next match starts.
internal const val ArbigentEscapedVariableMarkerPrefix: String = "\u0000ESCAPED_"
internal const val ArbigentEscapedVariableMarkerSuffix: String = "_ESCAPED\u0000"

/**
 * Hides `\{{...}}` escapes exactly as substitution does, so whatever `[ArbigentVariablePattern]`
 * still finds afterwards is exactly what substitution will try to look up — including the awkward
 * cases where an escape's own braces swallow the text after it.
 */
internal fun maskEscapedArbigentVariables(value: String): String =
  ArbigentEscapedVariablePattern.replace(value) { match ->
    "$ArbigentEscapedVariableMarkerPrefix${match.groupValues[1]}$ArbigentEscapedVariableMarkerSuffix"
  }

/** Every name substitution would look up in [value], in the order it would look them up. */
internal fun arbigentVariableReferences(value: String): List<String> =
  ArbigentVariablePattern.findAll(maskEscapedArbigentVariables(value))
    .map { it.groupValues[1].trim() }
    .filter { ValidArbigentVariableName.matches(it) }
    .toList()
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
        private val VALID_VARIABLE_NAME = ValidArbigentVariableName
        private const val MAX_VARIABLE_VALUE_LENGTH = 10_000
        private const val MAX_GOAL_LENGTH = 100_000
        
        // Pre-compiled regex patterns for performance
        private val VARIABLE_PATTERN = ArbigentVariablePattern
        private val ESCAPED_VARIABLE_PATTERN = ArbigentEscapedVariablePattern
        
        // Escape sequences for temporary replacement
        private const val TEMP_PREFIX = ArbigentEscapedVariableMarkerPrefix
        private const val TEMP_SUFFIX = ArbigentEscapedVariableMarkerSuffix
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
        val goalWithTempMarkers = maskEscapedArbigentVariables(goal)
        
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

