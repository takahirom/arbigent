package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Records how each caller of the scenario-resolution logic reacts to broken `dependency` graphs
 * today. The four callers deliberately disagree, so this test pins the current behavior down
 * before the resolution logic is unified; it must keep passing across the refactoring.
 */
class ScenarioResolutionCharacterizationTest {
  private fun load(yaml: String): ArbigentProjectFileContent = ArbigentProjectSerializer().load(yaml)

  private fun ArbigentProjectFileContent.scenario(id: String) =
    scenarioContents.first { it.id == id }

  private fun ArbigentProjectFileContent.taskIdsOf(id: String): List<String> =
    scenarioContents.createArbigentScenario(
      projectSettings = settings,
      scenario = scenario(id),
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.Disabled.toCache(),
      fixedScenarios = fixedScenarios,
      reusableScenarios = reusableScenarios,
    ).agentTasks.map { it.scenarioId }

  private val danglingDependency = """
    scenarios:
    - id: "a"
      goal: "A"
      dependency: "missing"
    """

  private val cyclicDependency = """
    scenarios:
    - id: "a"
      goal: "A"
      dependency: "c"
    - id: "b"
      goal: "B"
      dependency: "a"
    - id: "c"
      goal: "C"
      dependency: "b"
    """

  private val duplicateScenarioIds = """
    scenarios:
    - id: "a"
      goal: "first"
    - id: "a"
      goal: "second"
    """

  @Test
  fun brokenDependenciesLoadWithoutValidationError() {
    // `dependency` is not validated at load time; only reusable scenarios are.
    load(danglingDependency)
    load(cyclicDependency)
    load(duplicateScenarioIds)
  }

  @Test
  fun runtimeThrowsNoSuchElementOnDanglingDependency() {
    val project = load(danglingDependency)
    val failure = assertFailsWith<NoSuchElementException> { project.taskIdsOf("a") }
    assertEquals("Collection contains no element matching the predicate.", failure.message)
  }

  @Test
  fun runtimeSilentlyTruncatesCyclicDependency() {
    // No error: the `visited` set cuts the cycle and the remaining tasks are emitted.
    val project = load(cyclicDependency)
    assertEquals(listOf("b", "c", "a"), project.taskIdsOf("a"))
    assertEquals(listOf("c", "a", "b"), project.taskIdsOf("b"))
    assertEquals(listOf("a", "b", "c"), project.taskIdsOf("c"))
  }

  @Test
  fun runtimeResolvesDuplicateScenarioIdToTheFirstDeclaration() {
    val project = load(
      """
      scenarios:
      - id: "dep"
        goal: "first"
      - id: "dep"
        goal: "second"
      - id: "a"
        goal: "A"
        dependency: "dep"
      """
    )
    val goals = project.scenarioContents.createArbigentScenario(
      projectSettings = project.settings,
      scenario = project.scenario("a"),
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.Disabled.toCache(),
    ).agentTasks.map { it.goal }
    assertEquals(listOf("first", "A"), goals)
  }

  @Test
  fun graphSilentlyDropsDanglingDependencyEdge() {
    val graph = ArbigentScenarioGraph.from(load(danglingDependency))
    assertEquals(listOf("a"), graph.nodes.map { it.title })
    assertEquals(emptyList(), graph.edges)
  }

  @Test
  fun graphKeepsCyclicDependencyEdges() {
    val graph = ArbigentScenarioGraph.from(load(cyclicDependency))
    assertEquals(
      listOf(
        "scenario:c" to "scenario:a",
        "scenario:a" to "scenario:b",
        "scenario:b" to "scenario:c",
      ),
      graph.edges.map { it.fromKey to it.toKey }
    )
  }

  /**
   * The UI tree ordering: roots first, dependents indented under them. A scenario pointing at
   * something outside the list (the UI's shape of a dangling dependency) is shown as a root.
   */
  @Test
  fun dependencyForestTreatsMissingAndSelfDependenciesAsRoots() {
    class Item(val name: String, var dependency: Item? = null)

    val root = Item("root")
    val child = Item("child", root)
    val grandChild = Item("grandChild", child)
    val orphan = Item("orphan", Item("not-in-list"))
    val selfie = Item("selfie")
    selfie.dependency = selfie

    val ordered = ArbigentScenarioResolver.dependencyForestWithDepth(
      listOf(root, child, grandChild, orphan, selfie)
    ) { it.dependency }

    assertEquals(
      listOf("root" to 0, "child" to 1, "grandChild" to 2, "orphan" to 0, "selfie" to 0),
      ordered.map { (item, depth) -> item.name to depth }
    )
  }

  @Test
  fun reusableExpansionKeepsBreadcrumbsAndOrder() {
    val project = load(
      """
      scenarios:
      - id: "setup"
        goal: "Setup"
      - id: "buy"
        dependency: "setup"
        steps:
        - uses: "prepare"
          with:
            user: "paid"
        - uses: "checkout"
      reusableScenarios:
      - id: "prepare"
        inputs:
          user:
            default: "free"
        steps:
        - uses: "login"
          with:
            user: "{{inputs.user}}"
      - id: "login"
        inputs:
          user:
            default: "free"
        goal: "Log in as {{inputs.user}}"
      - id: "checkout"
        goal: "Checkout"
      """
    )
    val tasks = project.scenarioContents.createArbigentScenario(
      projectSettings = project.settings,
      scenario = project.scenario("buy"),
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.Disabled.toCache(),
      reusableScenarios = project.reusableScenarios,
    ).agentTasks
    assertEquals(listOf("setup", "buy", "buy"), tasks.map { it.scenarioId })
    assertEquals(listOf("Setup", "Log in as paid", "Checkout"), tasks.map { it.goal })
    assertEquals(
      listOf(null, "buy › prepare (user=paid) › login (user=paid)", "buy › checkout"),
      tasks.map { it.callBreadcrumb }
    )
  }
}
