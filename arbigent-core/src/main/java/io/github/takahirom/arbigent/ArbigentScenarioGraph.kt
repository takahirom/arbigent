package io.github.takahirom.arbigent

/**
 * Execution-structure graph of a project: scenario nodes connected by `dependency` edges,
 * with reusable calls expanded per call site into the reusable leaves that actually execute
 * (composites are flattened, mirroring how calls become agent tasks at runtime; the composite
 * path is kept as the node's subtitle). The same reusable called from two places becomes two
 * nodes, so each scenario's flow stays separate. Shared by the CLI (`arbigent graph`, Mermaid
 * output) and the UI (scenario graph dialog).
 */
public data class ArbigentScenarioGraph(
  public val nodes: List<Node>,
  public val edges: List<Edge>,
) {
  public enum class NodeKind { Scenario, ReusableCall }
  public enum class EdgeKind { Dependency, Call }

  /**
   * [key] is unique within the graph; call nodes get one key per call site.
   * For a scenario, [title] is its id and [subtitle] the goal snippet. For a reusable call,
   * [title] is the call label (`login (user=paid)`, bindings resolved like at runtime) and
   * [subtitle] the flattened composite path (`via prepare (user=paid)`), empty for direct calls.
   */
  public data class Node(
    public val key: String,
    public val title: String,
    public val subtitle: String,
    public val kind: NodeKind,
  )

  public data class Edge(
    public val fromKey: String,
    public val toKey: String,
    public val kind: EdgeKind,
  )

  public fun toMermaid(): String {
    val mermaidIds = nodes.withIndex().associate { (index, node) -> node.key to "n$index" }
    val lines = mutableListOf(
      "graph LR",
      "  classDef reusable fill:#e8f0fe,stroke:#4285f4",
    )
    nodes.forEach { node ->
      val id = mermaidIds.getValue(node.key)
      val label = escapeMermaid(node.title) +
        if (node.subtitle.isEmpty()) "" else "<br/>${escapeMermaid(node.subtitle)}"
      lines += when (node.kind) {
        NodeKind.Scenario -> "  $id[\"$label\"]"
        NodeKind.ReusableCall -> "  $id[[\"$label\"]]:::reusable"
      }
    }
    edges.forEach { edge ->
      val from = mermaidIds.getValue(edge.fromKey)
      val to = mermaidIds.getValue(edge.toKey)
      lines += when (edge.kind) {
        EdgeKind.Dependency -> "  $from --> $to"
        EdgeKind.Call -> "  $from -.-> $to"
      }
    }
    return lines.joinToString("\n")
  }

  private fun escapeMermaid(text: String): String = text.replace("\"", "#quot;")

  public companion object {
    private const val MAX_GOAL_LENGTH = 60

    public fun from(projectFileContent: ArbigentProjectFileContent): ArbigentScenarioGraph {
      val reusableById = projectFileContent.reusableScenarios.associateBy { it.id }
      val nodes = mutableListOf<Node>()
      val edges = mutableListOf<Edge>()
      // Call-node keys carry a monotonic counter so they can never collide with a
      // scenario key (scenario ids are unrestricted and could imitate any path scheme).
      var callNodeCounter = 0

      /**
       * Expands one call step. Leaves become nodes chained after [previousKey]; composites are
       * flattened by recursing into their steps, accumulating the path into [viaPath]. Returns
       * the key of the last emitted node so the next sibling step can chain from it. Bindings
       * are resolved like the runtime expansion (`defaults + with` resolved against the caller's
       * bindings). [expansionStack] guards against cyclic references on content that has not
       * gone through load-time validation.
       */
      fun expandStep(
        step: ArbigentScenarioContent.ReusableStep,
        parentBindings: Map<String, String>,
        viaPath: List<String>,
        previousKey: String,
        expansionStack: List<String>,
      ): String {
        val key = "call#${callNodeCounter++}:${step.uses}"
        val target = reusableById[step.uses]
        val resolvedWith = step.withValues.mapValues { (_, value) ->
          ReusableInputsResolver.resolve(value, parentBindings)
        }
        val defaults = target?.inputs.orEmpty()
          .mapNotNull { (name, input) -> input.default?.let { name to it } }.toMap()
        val bindings = defaults + resolvedWith
        val label = ReusableInputsResolver.breadcrumbLabel(step.uses, bindings)
        // Unresolved references (target == null) are reported by validation; keep a leaf node.
        if (target == null || !target.isCallForm() || step.uses in expansionStack) {
          nodes += Node(
            key = key,
            title = label,
            subtitle = if (viaPath.isEmpty()) "" else "via ${viaPath.joinToString(" › ")}",
            kind = NodeKind.ReusableCall,
          )
          edges += Edge(fromKey = previousKey, toKey = key, kind = EdgeKind.Call)
          return key
        }
        var lastKey = previousKey
        target.callSteps().forEach { nestedStep ->
          lastKey = expandStep(
            step = nestedStep,
            parentBindings = bindings,
            viaPath = viaPath + label,
            previousKey = lastKey,
            expansionStack = expansionStack + step.uses,
          )
        }
        return lastKey
      }

      fun scenarioKey(id: String) = "scenario:$id"

      projectFileContent.scenarioContents.forEach { scenario ->
        nodes += Node(
          key = scenarioKey(scenario.id),
          title = scenario.id,
          subtitle = goalSnippet(scenario),
          kind = NodeKind.Scenario,
        )
      }
      val scenarioIds = projectFileContent.scenarioContents.map { it.id }.toSet()
      projectFileContent.scenarioContents.forEach { scenario ->
        scenario.dependencyId?.let { dependencyId ->
          if (dependencyId in scenarioIds) {
            edges += Edge(
              fromKey = scenarioKey(dependencyId),
              toKey = scenarioKey(scenario.id),
              kind = EdgeKind.Dependency,
            )
          }
        }
        var lastKey = scenarioKey(scenario.id)
        scenario.callSteps().forEach { step ->
          lastKey = expandStep(
            step = step,
            parentBindings = emptyMap(),
            viaPath = emptyList(),
            previousKey = lastKey,
            expansionStack = emptyList(),
          )
        }
      }

      return ArbigentScenarioGraph(nodes = nodes, edges = edges)
    }

    private fun goalSnippet(scenario: ArbigentScenarioContent): String {
      val goal = scenario.goal.replace(Regex("\\s+"), " ").trim()
      return if (goal.length <= MAX_GOAL_LENGTH) goal else goal.take(MAX_GOAL_LENGTH - 1) + "…"
    }
  }
}
