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

  /**
   * The runtime and the graph disagree on which declaration wins for a duplicate reusable id:
   * `createArbigentScenario` looked it up with `firstOrNull { it.id == step.uses }` while
   * `ArbigentScenarioGraph` and `instruction` used `associateBy`. Loading YAML rejects duplicate
   * reusable ids, so only in-memory content — what the UI holds while editing — can reach this.
   */
  @Test
  fun duplicateReusableIdResolvesToTheFirstAtRuntimeAndTheLastInTheGraph() {
    // Each duplicate is a composite reaching a differently-named leaf, so the winner is visible
    // in both the executed goals and the rendered node titles.
    val scenarios = listOf(ArbigentScenarioContent(id = "caller", uses = "part"))
    val reusableScenarios = listOf(
      ArbigentScenarioContent(
        id = "part",
        steps = listOf(ArbigentScenarioContent.ReusableStep(uses = "leaf-of-first")),
      ),
      ArbigentScenarioContent(
        id = "part",
        steps = listOf(ArbigentScenarioContent.ReusableStep(uses = "leaf-of-last")),
      ),
      ArbigentScenarioContent(id = "leaf-of-first", goal = "reached the first declaration"),
      ArbigentScenarioContent(id = "leaf-of-last", goal = "reached the last declaration"),
    )

    val goals = scenarios.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(),
      scenario = scenarios.single(),
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.Disabled.toCache(),
      reusableScenarios = reusableScenarios,
    ).agentTasks.map { it.goal }
    assertEquals(listOf("reached the first declaration"), goals)

    val graph = ArbigentScenarioGraph.from(
      ArbigentProjectFileContent(
        scenarioContents = scenarios,
        reusableScenarios = reusableScenarios,
      )
    )
    assertEquals(listOf("caller", "leaf-of-last"), graph.nodes.map { it.title })
  }

  /**
   * The runtime's `visited` set is keyed by instance, not by id, because
   * `ArbigentScenarioContent` does not override `equals`. Two declarations sharing an id are both
   * expanded rather than the second being swallowed. Only in-memory content reaches this — load
   * rejects duplicate scenario ids.
   */
  @Test
  fun duplicateScenarioIdInMemoryExpandsBothDeclarations() {
    val first = ArbigentScenarioContent(id = "dup", goal = "dup-first")
    val middle = ArbigentScenarioContent(id = "x", goal = "x", dependencyId = "dup")
    val second = ArbigentScenarioContent(id = "dup", goal = "dup-second", dependencyId = "x")
    val scenarios = listOf(first, middle, second)

    val goals = scenarios.createArbigentScenario(
      projectSettings = ArbigentProjectSettings(),
      scenario = second,
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.Disabled.toCache(),
    ).agentTasks.map { it.goal }

    assertEquals(listOf("dup-first", "x", "dup-second"), goals)
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

  /**
   * A mutual cycle gives every item a dependency inside the list, so no root exists and the
   * whole cycle drops out of the ordering. The UI has always behaved this way: the pre-refactor
   * `sortedScenarioAndDepth` built the same roots/dependents split, and its
   * `if (v.isEmpty()) roots.add(k)` branch was unreachable because `getOrPut` always inserted a
   * non-empty list.
   *
   * This matters beyond ordering, because `getCurrentProjectFileContent()` serializes
   * `sortedScenariosAndDepthsStateFlow`, so scenarios in a cycle are dropped when the project is
   * saved. That is a pre-existing bug rather than something this refactoring introduces, and it
   * is pinned here so a fix has to change this test deliberately.
   */
  @Test
  fun mutualDependencyCycleIsOmittedFromTheForest() {
    class Item(val name: String, var dependency: Item? = null)

    val a = Item("a")
    val b = Item("b", a)
    a.dependency = b
    val unrelated = Item("unrelated")

    val ordered = ArbigentScenarioResolver.dependencyForestWithDepth(listOf(a, b, unrelated)) {
      it.dependency
    }

    assertEquals(listOf("unrelated" to 0), ordered.map { (item, depth) -> item.name to depth })
  }

  /**
   * `createArbigentScenario` turns an unresolvable or cyclic `uses` into an exception. Without
   * that throw the null-content leaf the resolver emits for those diagnostics would reach
   * `requireNotNull(leaf.content)`, so the branch is pinned here.
   *
   * Loading YAML cannot reach it — reusable references are validated at load time — so these
   * cases build the content in memory, the way the UI does while a project is being edited.
   */
  private fun buildTasks(
    scenarios: List<ArbigentScenarioContent>,
    reusableScenarios: List<ArbigentScenarioContent> = emptyList(),
  ) = scenarios.createArbigentScenario(
    projectSettings = ArbigentProjectSettings(),
    scenario = scenarios.first(),
    aiFactory = { FakeAi() },
    deviceFactory = { FakeDevice() },
    aiDecisionCache = AiDecisionCacheStrategy.Disabled.toCache(),
    reusableScenarios = reusableScenarios,
  )

  @Test
  fun runtimeRejectsUnresolvedReusableReference() {
    val failure = assertFailsWith<ArbigentProjectValidationException> {
      buildTasks(listOf(ArbigentScenarioContent(id = "a", uses = "nowhere")))
    }
    assertEquals(
      "Reusable scenario 'nowhere' referenced from 'a' is not defined in reusableScenarios",
      failure.message
    )
  }

  @Test
  fun runtimeRejectsCyclicReusableReference() {
    val failure = assertFailsWith<ArbigentProjectValidationException> {
      buildTasks(
        scenarios = listOf(ArbigentScenarioContent(id = "a", uses = "first")),
        reusableScenarios = listOf(
          ArbigentScenarioContent(id = "first", uses = "second"),
          ArbigentScenarioContent(id = "second", uses = "first"),
        ),
      )
    }
    assertEquals(
      "Cyclic reusable scenario reference detected: first -> second -> first",
      failure.message
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
