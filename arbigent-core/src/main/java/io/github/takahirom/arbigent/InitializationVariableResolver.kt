package io.github.takahirom.arbigent

/** Thrown when an initialization method still contains a `{{name}}` placeholder at run time. */
public class ArbigentUnresolvedVariableException(message: String) : IllegalStateException(message)

/**
 * Resolves `{{name}}` project variables in initialization method fields (LaunchApp / CleanupData
 * `packageName`, OpenLink `link`) with the same rules as goals ([GoalVariableResolver]), but a
 * placeholder that stays unresolved is an error instead of being kept literally: launching
 * `{{appId}}` can never succeed, so fail fast with an actionable message.
 */
internal object InitializationVariableResolver {
  // Unescaped placeholders only; `\{{name}}` is an escape handled by GoalVariableResolver.
  private val PLACEHOLDER = """(?<!\\)\{\{([^}]+)\}\}""".toRegex()

  fun resolve(value: String, variables: Map<String, String>?, methodName: String, fieldName: String): String {
    val missing = PLACEHOLDER.findAll(value)
      .map { it.groupValues[1].trim() }
      .filter { variables?.containsKey(it) != true }
      .toList()
    if (missing.isNotEmpty()) {
      throw ArbigentUnresolvedVariableException(
        "Unresolved variable ${missing.joinToString(", ") { "{{$it}}" }} in $methodName $fieldName \"$value\". " +
          "Set settings.variables in the project YAML or pass --variables (e.g. --variables=${missing.first()}=com.example.app)."
      )
    }
    return GoalVariableResolver.resolve(value, variables)
  }
}
