package io.github.takahirom.arbigent

/**
 * Single implementation of "scenario -> ordered list of things that actually execute".
 *
 * Two independent walks live here, because a project has two kinds of references:
 * - `dependency`: a scenario runs after the scenario it depends on (at most one parent, so the
 *   dependency structure is a forest of chains).
 * - `uses`/`steps`: a call site expands into the reusable leaves it eventually reaches, with
 *   `{{inputs.*}}` bound from `with` + declared defaults.
 *
 * The resolver never throws. Broken references are reported as [ArbigentScenarioDiagnostic]s
 * alongside a best-effort result, so each caller keeps its own policy: the runtime and the CLI
 * turn diagnostics into errors, the graph renders the broken project anyway.
 */
public object ArbigentScenarioResolver {

  /**
   * One executable leaf. [content] is null for a call that could not be resolved (dangling or
   * cyclic reference); the accompanying diagnostic explains why, and [callPath] still carries the
   * label so renderers can show the broken call.
   */
  public data class ResolvedLeaf(
    /** Id of the scenario whose expansion produced this leaf (the agent task's scenario id). */
    public val rootScenarioId: String,
    /** The `uses` id of the call site, or null when the leaf is the scenario itself. */
    public val uses: String?,
    public val content: ArbigentScenarioContent?,
    /** Input bindings in effect, or null when the leaf is the scenario itself (no call involved). */
    public val bindings: Map<String, String>?,
    /**
     * `[rootScenarioId, enclosing composite labels..., this leaf's label]`, empty for a scenario
     * that is not reached through a call.
     */
    public val callPath: List<String>,
  ) {
    /**
     * Label of this leaf's own call site, e.g. `login (user=paid)`.
     * Only defined for a leaf reached through a call ([uses] is non-null); [expandCalls] emits
     * only such leaves, while [resolveChain] can also emit the scenario itself with an empty
     * [callPath], for which there is no call site to label.
     */
    public val callLabel: String get() = callPath.last()

    /** Enclosing composite labels between the scenario and this leaf. */
    public val viaPath: List<String> get() = callPath.drop(1).dropLast(1)
  }

  public data class Resolution(
    public val leaves: List<ResolvedLeaf>,
    public val diagnostics: List<ArbigentScenarioDiagnostic>,
  )

  /**
   * Walks [target]'s `dependency` chain root-first and expands each scenario's calls, returning
   * the leaves in execution order.
   *
   * [scenarioLookup] and [reusableLookup] resolve a dependency id and a `uses` id. Callers pass
   * their existing index, because they disagree on which declaration wins for a duplicate id and
   * that difference is observable for content that never went through load-time validation.
   */
  public fun resolveChain(
    target: ArbigentScenarioContent,
    scenarioLookup: (String) -> ArbigentScenarioContent?,
    reusableLookup: (String) -> ArbigentScenarioContent?,
  ): Resolution {
    val leaves = mutableListOf<ResolvedLeaf>()
    val diagnostics = mutableListOf<ArbigentScenarioDiagnostic>()
    // Keyed by instance, not by id: `ArbigentScenarioContent` does not override `equals`, and the
    // runtime's `visited` set has always been identity-based. Two declarations sharing an id are
    // therefore both expanded, which only in-memory content can produce — load-time validation
    // rejects duplicate scenario ids.
    val visited = mutableSetOf<ArbigentScenarioContent>()

    // Both the path guard and [visited] are keyed by instance, not by id, because
    // `ArbigentScenarioContent` does not override `equals` and the runtime's cut-off has always
    // been identity-based. Two declarations sharing an id are therefore both expanded instead of
    // the second looking like a cycle. Only in-memory content can produce that — load-time
    // validation rejects duplicate scenario ids.
    fun dfs(scenario: ArbigentScenarioContent, dependencyStack: List<ArbigentScenarioContent>) {
      if (dependencyStack.any { it === scenario }) {
        diagnostics += ArbigentScenarioDiagnostic.CyclicDependency(
          (dependencyStack + scenario).map { it.id }
        )
        return
      }
      if (!visited.add(scenario)) return
      scenario.dependencyId?.let { dependencyId ->
        val dependency = scenarioLookup(dependencyId)
        if (dependency == null) {
          diagnostics += ArbigentScenarioDiagnostic.DanglingDependency(scenario.id, dependencyId)
        } else {
          dfs(dependency, dependencyStack + scenario)
        }
      }
      val expansion = expandCalls(scenario, reusableLookup)
      leaves += expansion.leaves
      diagnostics += expansion.diagnostics
      if (!scenario.isCallForm()) {
        leaves += ResolvedLeaf(
          rootScenarioId = scenario.id,
          uses = null,
          content = scenario,
          bindings = null,
          callPath = emptyList(),
        )
      }
    }

    dfs(target, emptyList())
    return Resolution(leaves, diagnostics)
  }

  /**
   * Expands only [scenario]'s own `uses`/`steps` (no dependency walk), flattening composites into
   * the leaves that actually execute. Empty for a scenario that has no calls.
   *
   * [reusableLookup] is supplied by the caller for the same reason as in [resolveChain]: the
   * runtime resolves a duplicate `uses` id to the first declaration while the graph and the CLI
   * resolve it to the last.
   */
  public fun expandCalls(
    scenario: ArbigentScenarioContent,
    reusableLookup: (String) -> ArbigentScenarioContent?,
  ): Resolution {
    if (!scenario.isCallForm()) return Resolution(emptyList(), emptyList())
    val leaves = mutableListOf<ResolvedLeaf>()
    val diagnostics = mutableListOf<ArbigentScenarioDiagnostic>()

    fun expandStep(
      step: ArbigentScenarioContent.ReusableStep,
      parentBindings: Map<String, String>,
      breadcrumb: List<String>,
      expansionStack: List<String>,
    ) {
      val target = reusableLookup(step.uses)
      // Explicit propagation: with-values may reference the caller's own inputs via {{inputs.*}}.
      val resolvedWith = step.withValues.mapValues { (_, value) ->
        ReusableInputsResolver.resolve(value, parentBindings)
      }
      val defaults = target?.inputs.orEmpty()
        .mapNotNull { (name, input) -> input.default?.let { name to it } }.toMap()
      // Label with the effective bindings (including defaults) so reports show the full snapshot.
      val bindings = defaults + resolvedWith
      val callPath = breadcrumb + ReusableInputsResolver.breadcrumbLabel(step.uses, bindings)

      fun unresolvedLeaf() {
        leaves += ResolvedLeaf(
          rootScenarioId = scenario.id,
          uses = step.uses,
          content = null,
          bindings = bindings,
          callPath = callPath,
        )
      }

      if (expansionStack.contains(step.uses)) {
        diagnostics += ArbigentScenarioDiagnostic.CyclicReusable(expansionStack + step.uses)
        unresolvedLeaf()
        return
      }
      if (target == null) {
        // The owner is the innermost composite the call sits in, or the scenario itself for a
        // direct call. `expansionStack` holds raw ids; `breadcrumb` holds display labels.
        diagnostics += ArbigentScenarioDiagnostic.UnresolvedReusable(
          referencedFromSection = if (expansionStack.isEmpty()) DeclarationSection.Scenarios
          else DeclarationSection.ReusableScenarios,
          referencedFrom = expansionStack.lastOrNull() ?: scenario.id,
          uses = step.uses,
        )
        unresolvedLeaf()
        return
      }
      if (target.isCallForm()) {
        target.callSteps().forEach {
          expandStep(it, bindings, callPath, expansionStack + step.uses)
        }
      } else {
        leaves += ResolvedLeaf(
          rootScenarioId = scenario.id,
          uses = step.uses,
          content = target,
          bindings = bindings,
          callPath = callPath,
        )
      }
    }

    scenario.callSteps().forEach { step ->
      expandStep(step, emptyMap(), listOf(scenario.id), emptyList())
    }
    return Resolution(leaves, diagnostics)
  }

  /**
   * Whole-project `dependency` diagnostics, in a stable order: duplicate ids, then dangling
   * references in declaration order, then each cycle once. Used by load-time validation so a
   * broken project is rejected with every violation at once instead of one per run.
   */
  public fun diagnoseDependencies(
    scenarios: List<ArbigentScenarioContent>,
  ): List<ArbigentScenarioDiagnostic> {
    val diagnostics = mutableListOf<ArbigentScenarioDiagnostic>()
    scenarios.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, declarations) ->
      diagnostics += ArbigentScenarioDiagnostic.DuplicateScenarioId(id, declarations.size)
    }
    val byId = scenarios.associateBy { it.id }
    scenarios.forEach { scenario ->
      scenario.dependencyId?.let { dependencyId ->
        if (!byId.containsKey(dependencyId)) {
          diagnostics += ArbigentScenarioDiagnostic.DanglingDependency(scenario.id, dependencyId)
        }
      }
    }

    // Three-color walk so a cycle shared by several scenarios is reported once.
    val visiting = mutableSetOf<String>()
    val done = mutableSetOf<String>()
    fun visit(id: String, path: List<String>) {
      if (id in visiting) {
        diagnostics += ArbigentScenarioDiagnostic.CyclicDependency(
          path.dropWhile { it != id } + id
        )
        return
      }
      if (id in done) return
      visiting += id
      byId[id]?.dependencyId?.takeIf { byId.containsKey(it) }?.let { visit(it, path + id) }
      visiting -= id
      done += id
    }
    scenarios.forEach { visit(it.id, emptyList()) }
    return diagnostics
  }

  /**
   * Orders [items] as a dependency forest and pairs each with its depth: roots first, each
   * followed by its dependents. An item whose dependency is not in [items] (or is itself) is a
   * root. Items are matched with `==` and used as map keys, so the UI can order live scenario
   * state holders (which have no stable id) by identity — `ArbigentScenarioStateHolder` does not
   * override `equals`.
   *
   * Items that only reach each other form no root, so a dependency cycle is omitted from the
   * result entirely. That is what the UI has always done; see
   * `mutualDependencyCycleIsOmittedFromTheForest`.
   */
  public fun <T> dependencyForestWithDepth(
    items: List<T>,
    dependencyOf: (T) -> T?,
  ): List<Pair<T, Int>> {
    val dependents = mutableMapOf<T, MutableList<T>>()
    val roots = mutableListOf<T>()

    items.forEach { item ->
      val dependency = items.firstOrNull { it == dependencyOf(item) }
      if (dependency == null || dependency == item) {
        roots.add(item)
      } else {
        dependents.getOrPut(dependency) { mutableListOf() }.add(item)
      }
    }

    val result = mutableListOf<Pair<T, Int>>()
    fun walk(item: T, depth: Int) {
      result.add(item to depth)
      dependents[item]?.forEach { walk(it, depth + 1) }
    }
    roots.forEach { walk(it, 0) }
    return result
  }
}

/** Which project-file section a declaration lives in, used to point a violation at it. */
public enum class DeclarationSection(public val sectionName: String) {
  Scenarios("scenarios"),
  ReusableScenarios("reusableScenarios"),
}

/**
 * A broken reference found while resolving scenarios. Callers decide whether it is fatal.
 *
 * [message] follows the same shape as every other validation message —
 * `<section> '<id>': <what is wrong>`, no trailing period — so a report that mixes dependency
 * and reusable violations reads as one list.
 */
public sealed interface ArbigentScenarioDiagnostic {
  /** Section and id the violation is attributed to, e.g. `scenarios 'checkout'`. */
  public val where: String
  public val detail: String
  public val message: String get() = "$where: $detail"

  public data class DanglingDependency(
    public val scenarioId: String,
    public val dependencyId: String,
  ) : ArbigentScenarioDiagnostic {
    override val where: String get() = "scenarios '$scenarioId'"
    override val detail: String get() = "dependency '$dependencyId' is not defined in scenarios"
  }

  /** [path] ends with the scenario that closes the cycle, e.g. `a -> b -> a`. */
  public data class CyclicDependency(public val path: List<String>) : ArbigentScenarioDiagnostic {
    init { require(path.isNotEmpty()) { "A cycle path needs at least the repeated scenario" } }

    // A walk can enter a cycle from outside it (`tail -> a -> b -> a`), so the lead-in is trimmed
    // before attributing: load-time and runtime detection must name the same scenario.
    private val cycle: List<String> get() = path.dropWhile { it != path.last() }
    override val where: String get() = "scenarios '${cycle.first()}'"
    override val detail: String get() = "cyclic dependency: ${cycle.joinToString(" -> ")}"
  }

  public data class DuplicateScenarioId(
    public val id: String,
    public val declarationCount: Int,
  ) : ArbigentScenarioDiagnostic {
    override val where: String get() = "scenarios '$id'"
    override val detail: String get() = "duplicate id (declared $declarationCount times)"
  }

  public data class UnresolvedReusable(
    /** Where the declaration holding the call lives. */
    public val referencedFromSection: DeclarationSection,
    /**
     * Raw id of that declaration. Never a breadcrumb label like `outer (user=paid)`, which names
     * a call site rather than something the user can open and edit.
     */
    public val referencedFrom: String,
    public val uses: String,
  ) : ArbigentScenarioDiagnostic {
    override val where: String get() = "${referencedFromSection.sectionName} '$referencedFrom'"
    override val detail: String get() = "uses '$uses' is not defined in reusableScenarios"
  }

  /** [path] ends with the reusable scenario that closes the cycle, e.g. `y -> z -> y`. */
  public data class CyclicReusable(public val path: List<String>) : ArbigentScenarioDiagnostic {
    init { require(path.isNotEmpty()) { "A cycle path needs at least the repeated reusable" } }

    private val cycle: List<String> get() = path.dropWhile { it != path.last() }
    override val where: String get() = "reusableScenarios '${cycle.first()}'"
    override val detail: String get() = "cyclic reusable reference: ${cycle.joinToString(" -> ")}"
  }
}
