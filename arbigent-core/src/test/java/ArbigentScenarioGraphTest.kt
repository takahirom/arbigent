package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.ArbigentProjectFileContent
import io.github.takahirom.arbigent.ArbigentProjectSerializer
import io.github.takahirom.arbigent.ArbigentScenarioGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArbigentScenarioGraphTest {
  private fun load(yaml: String): ArbigentProjectFileContent = ArbigentProjectSerializer().load(yaml)

  @Test
  fun dependencyEdgesConnectScenarioNodes() {
    val graph = ArbigentScenarioGraph.from(
      load(
        """
        scenarios:
        - id: "setup"
          goal: "Open the app"
        - id: "buy"
          goal: "Buy an item"
          dependency: "setup"
        """
      )
    )
    assertEquals(listOf("setup", "buy"), graph.nodes.map { it.title })
    assertEquals(1, graph.edges.size)
    val edge = graph.edges.single()
    assertEquals(ArbigentScenarioGraph.EdgeKind.Dependency, edge.kind)
    assertEquals("scenario:setup", edge.fromKey)
    assertEquals("scenario:buy", edge.toKey)
  }

  @Test
  fun sameReusableCalledTwiceBecomesTwoNodes() {
    val graph = ArbigentScenarioGraph.from(
      load(
        """
        scenarios:
        - id: "scenario-a"
          uses: "login"
          with:
            user: "paid"
        - id: "scenario-b"
          uses: "login"
          with:
            user: "free"
        reusableScenarios:
        - id: "login"
          inputs:
            user:
              required: true
          goal: "Log in as {{inputs.user}}"
        """
      )
    )
    val callNodes = graph.nodes.filter { it.kind == ArbigentScenarioGraph.NodeKind.ReusableCall }
    assertEquals(listOf("login (user=paid)", "login (user=free)"), callNodes.map { it.title })
    assertEquals(2, callNodes.map { it.key }.distinct().size)
  }

  @Test
  fun compositesAreFlattenedToExecutedLeavesInOrder() {
    val graph = ArbigentScenarioGraph.from(
      load(
        """
        scenarios:
        - id: "combo"
          steps:
          - uses: "prepare"
            with:
              user: "paid"
          - uses: "checkout"
        reusableScenarios:
        - id: "prepare"
          inputs:
            user:
              required: true
          steps:
          - uses: "login"
            with:
              user: "{{inputs.user}}"
          - uses: "add-to-cart"
        - id: "login"
          inputs:
            user:
              required: true
          goal: "Log in as {{inputs.user}}"
        - id: "add-to-cart"
          goal: "Add an item to the cart"
        - id: "checkout"
          goal: "Check out the cart"
        """
      )
    )
    val callNodes = graph.nodes.filter { it.kind == ArbigentScenarioGraph.NodeKind.ReusableCall }
    // The composite "prepare" itself has no node; only executed leaves appear,
    // with bindings resolved through the composite and the path kept as subtitle.
    assertEquals(
      listOf("login (user=paid)", "add-to-cart", "checkout"),
      callNodes.map { it.title }
    )
    assertEquals(
      listOf("via prepare (user=paid)", "via prepare (user=paid)", ""),
      callNodes.map { it.subtitle }
    )
    val chain = graph.edges
      .filter { it.kind == ArbigentScenarioGraph.EdgeKind.Call }
      .map { it.fromKey to it.toKey }
    assertEquals("scenario:combo", chain[0].first)
    assertTrue(chain[0].second.endsWith(":login"))
    assertTrue(chain[1].first.endsWith(":login"))
    assertTrue(chain[1].second.endsWith(":add-to-cart"))
    assertTrue(chain[2].first.endsWith(":add-to-cart"))
    assertTrue(chain[2].second.endsWith(":checkout"))
  }

  @Test
  fun mermaidOutputEscapesQuotesAndMarksReusableCalls() {
    val mermaid = ArbigentScenarioGraph.from(
      load(
        """
        scenarios:
        - id: "search"
          goal: "Search for \"About\" in settings"
        - id: "call-login"
          uses: "login"
          dependency: "search"
        reusableScenarios:
        - id: "login"
          goal: "Log in"
        """
      )
    ).toMermaid()
    assertTrue(mermaid.startsWith("graph LR"))
    assertTrue(mermaid.contains("Search for #quot;About#quot; in settings"))
    assertTrue(mermaid.contains("[[\"login\"]]:::reusable"))
    assertTrue(mermaid.contains("n0 --> n1"))
    assertTrue(mermaid.contains("-.->"))
  }

  @Test
  fun longGoalIsTruncatedInSubtitle() {
    val graph = ArbigentScenarioGraph.from(
      load(
        """
        scenarios:
        - id: "long"
          goal: "${"a".repeat(100)}"
        """
      )
    )
    val subtitle = graph.nodes.single().subtitle
    assertEquals(60, subtitle.length)
    assertTrue(subtitle.endsWith("…"))
  }
}
