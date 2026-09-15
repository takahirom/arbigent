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
  // A character no name can contain, so masking an escape cannot accidentally create a reference
  // out of the text around it — which is exactly what substitution's own temporary marker does.
  private const val MASK = "\u0000"

  /**
   * The variable names [value] references but [variables] does not define. This must agree with
   * [GoalVariableResolver] on every input, so it masks escapes with the resolver's own pattern
   * before looking for references: `\{{name}}` is not a reference, and neither is a name the
   * resolver would refuse to substitute (e.g. `example://open?template={{user:id}}`), which stays
   * as literal text.
   */
  fun missingNames(value: String, variables: Map<String, String>?): List<String> =
    ArbigentVariablePattern.findAll(ArbigentEscapedVariablePattern.replace(value, MASK))
      .map { it.groupValues[1].trim() }
      .filter { ValidArbigentVariableName.matches(it) && variables?.containsKey(it) != true }
      .distinct()
      .toList()
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
        "--variables=$example=com.example.app, or write \\{{$example}} if you meant the text itself."
    )
  }
}
