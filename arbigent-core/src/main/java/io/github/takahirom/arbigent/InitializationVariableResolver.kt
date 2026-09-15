package io.github.takahirom.arbigent

/**
 * Resolves `{{name}}` project variables in initialization method fields (LaunchApp / CleanupData
 * `packageName`, LaunchApp string launch arguments, OpenLink `link`) with the same rules as goals
 * ([GoalVariableResolver]), and rejects a placeholder that stays unresolved.
 *
 * A run normally never reaches this: [ArbigentScenarioExecutor.execute] rejects the whole scenario
 * up front so the report lists every unresolved reference instead of the first one to run. This
 * stays as the backstop for agents built outside that path.
 */
internal object InitializationVariableResolver {
  fun resolve(value: String, variables: Map<String, String>?, methodName: String, fieldName: String): String {
    val missing = UnresolvedVariableFinder.missingNames(value, variables)
    if (missing.isNotEmpty()) {
      throw ArbigentUnresolvedVariableException(
        unresolvedVariableMessage(
          header = "$methodName $fieldName \"$value\" references variables that are not defined:",
          unresolved = missing.map { ArbigentUnresolvedVariable(it, "$methodName $fieldName") },
        )
      )
    }
    return GoalVariableResolver.resolve(value, variables)
  }
}
