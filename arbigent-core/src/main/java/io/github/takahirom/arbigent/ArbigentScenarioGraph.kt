package io.github.takahirom.arbigent

/**
 * Execution-structure graph of a project: scenario nodes connected by `dependency` edges,
 * with reusable calls expanded per call site into the reusable leaves that actually execute
 * (composites are flattened, mirroring how calls become agent tasks at runtime; the composite
 * path is kept as the node's subtitle). The same reusable called from two places becomes two
 * nodes, so each scenario's flow stays separate — every node has at most one incoming edge,
 * making the graph a forest. A dependency edge starts from the last node the dependency
 * scenario executes (its last call node, or the scenario itself when it has no calls), so
 * edges read as execution order: `A -.-> login --> B` means B runs after A including its
 * calls. Shared by the CLI (`arbigent graph`, Mermaid output) and the UI (scenario graph
 * dialog).
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
      "  classDef reusable fill:$REUSABLE_FILL_HEX,stroke:$REUSABLE_STROKE_HEX",
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
    // Reusable-call node colors, shared by every renderer (Mermaid classDef and the UI dialog).
    public const val REUSABLE_FILL_HEX: String = "#e8f0fe"
    public const val REUSABLE_STROKE_HEX: String = "#4285f4"

    private const val MAX_GOAL_LENGTH = 60

    public fun from(projectFileContent: ArbigentProjectFileContent): ArbigentScenarioGraph {
      val nodes = mutableListOf<Node>()
      val edges = mutableListOf<Edge>()
      // Call-node keys carry a monotonic counter so they can never collide with a
      // scenario key (scenario ids are unrestricted and could imitate any path scheme).
      var callNodeCounter = 0

      fun scenarioKey(id: String) = "scenario:$id"

      projectFileContent.scenarioContents.forEach { scenario ->
        nodes += Node(
          key = scenarioKey(scenario.id),
          title = scenario.id,
          subtitle = goalSnippet(scenario),
          kind = NodeKind.Scenario,
        )
      }
      // First expand every scenario's call chain and remember where it ends, because a
      // dependency edge starts from the dependency scenario's last executed node. The
      // expansion emits leaves in execution order, so each leaf simply chains after the
      // previous one; broken references still emit a leaf, since the graph renders an
      // invalid project instead of rejecting it (diagnostics are reported by validation).
      val lastKeyByScenarioId = mutableMapOf<String, String>()
      projectFileContent.scenarioContents.forEach { scenario ->
        var lastKey = scenarioKey(scenario.id)
        val leaves = ArbigentScenarioResolver
          .expandCalls(scenario, projectFileContent.reusableScenarios)
          .leaves
        leaves.forEach { leaf ->
          // `uses` is always set for a leaf from expandCalls; orEmpty keeps the key readable
          // rather than encoding "null" if that ever stops holding. The counter makes it unique.
          val key = "call#${callNodeCounter++}:${leaf.uses.orEmpty()}"
          nodes += Node(
            key = key,
            title = leaf.callLabel,
            subtitle = leaf.viaPath.let { if (it.isEmpty()) "" else "via ${it.joinToString(" › ")}" },
            kind = NodeKind.ReusableCall,
          )
          edges += Edge(fromKey = lastKey, toKey = key, kind = EdgeKind.Call)
          lastKey = key
        }
        lastKeyByScenarioId[scenario.id] = lastKey
      }
      projectFileContent.scenarioContents.forEach { scenario ->
        scenario.dependencyId?.let { dependencyId ->
          // A dangling dependency silently loses its edge; the rest of the graph still renders.
          lastKeyByScenarioId[dependencyId]?.let { fromKey ->
            edges += Edge(
              fromKey = fromKey,
              toKey = scenarioKey(scenario.id),
              kind = EdgeKind.Dependency,
            )
          }
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
