package io.github.takahirom.arbigent

/** Thrown when a `{{name}}` placeholder has no project variable to resolve it. */
public class ArbigentUnresolvedVariableException(message: String) : IllegalStateException(message)

/**
 * A `{{name}}` reference that no variable resolves, and the field it was found in. Collected while
 * a scenario is built so a run can report every one of them at once.
 */
public data class ArbigentUnresolvedVariable(
  public val name: String,
  public val location: String,
) {
  override fun toString(): String = "{{$name}} in $location"
}

/**
 * Finds `{{name}}` references that no variable defines. Goals and initialization methods share
 * this so both fail the same way: an unresolved placeholder is a mistake, and `\{{name}}` is how
 * you ask for the literal text.
 */
internal object UnresolvedVariableFinder {
  /**
   * The variable names [value] references but [variables] does not define. This must agree with
   * [GoalVariableResolver] on every input, so it hides escapes with
   * [maskEscapedArbigentVariables] — the very transformation substitution applies — before looking
   * for references: `\{{name}}` is not a reference, and neither is a name the resolver would
   * refuse to substitute (e.g. `example://open?template={{user:id}}`), which stays as literal
   * text.
   */
  fun missingNames(value: String, variables: Map<String, String>?): List<String> =
    arbigentVariableReferences(value)
      .filter { variables?.containsKey(it) != true }
      .distinct()
}

/** One message listing every unresolved reference, plus every way to make it resolve. */
internal fun unresolvedVariableMessage(header: String, unresolved: List<ArbigentUnresolvedVariable>): String {
  val example = unresolved.first().name
  return buildString {
    append(header)
    unresolved.forEach { appendLine().append("  - ").append(it.toString()) }
    appendLine()
    append(
      "Give it a default in settings.variables in the project YAML, pass it in with " +
        "--variables=\"$example=com.example.app\", or write \\{{$example}} if you meant the text " +
        "itself."
    )
  }
}
