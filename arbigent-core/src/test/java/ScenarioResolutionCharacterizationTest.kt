package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins down how scenario resolution reacts to broken `dependency` graphs. A broken graph is now
 * rejected at load time with every violation listed at once; the tests that used to record each
 * caller's own divergent reaction were rewritten accordingly.
 */
class ScenarioResolutionCharacterizationTest {
  private fun load(yaml: String): ArbigentProjectFileContent = ArbigentProjectSerializer().load(yaml)

  private fun ArbigentProjectFileContent.scenario(id: String) =
    scenarioContents.first { it.id == id }

  private fun ArbigentProjectFileContent.tasksOf(id: String) =
    scenarioContents.createArbigentScenario(
      projectSettings = settings,
      scenario = scenario(id),
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
      aiDecisionCache = AiDecisionCacheStrategy.Disabled.toCache(),
      fixedScenarios = fixedScenarios,
      reusableScenarios = reusableScenarios,
    ).agentTasks

  private fun assertValidationError(expectedMessagePart: String, yaml: String) {
    val exception = assertFailsWith<ArbigentProjectValidationException> { load(yaml) }
    assertTrue(
      exception.message!!.contains(expectedMessagePart),
      "Expected message to contain '$expectedMessagePart' but was: ${exception.message}"
    )
  }

  @Test
  fun danglingDependencyFailsAtLoad() {
    assertValidationError(
      "scenarios 'a': dependency 'missing' is not defined in scenarios",
      """
      scenarios:
      - id: "a"
        goal: "A"
        dependency: "missing"
      """
    )
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

  /**
   * A violation must name a declaration the user can open. The resolver walks from the target, so
   * it can enter a cycle from outside it and reach a nested call through composites; neither the
   * lead-in nor a breadcrumb label is addressable, so both are trimmed away before attributing.
   */
  @Test
  fun runtimeAttributesACycleEnteredFromOutsideToTheCycleItself() {
    val a = ArbigentScenarioContent(id = "a", goal = "A", dependencyId = "b")
    val b = ArbigentScenarioContent(id = "b", goal = "B", dependencyId = "a")
    val tail = ArbigentScenarioContent(id = "tail", goal = "T", dependencyId = "a")
    val scenarios = listOf(a, b, tail)

    val failure = assertFailsWith<ArbigentProjectValidationException> {
      scenarios.createArbigentScenario(
        projectSettings = ArbigentProjectSettings(),
        scenario = tail,
        aiFactory = { FakeAi() },
        deviceFactory = { FakeDevice() },
        aiDecisionCache = AiDecisionCacheStrategy.Disabled.toCache(),
      )
    }
    // Same attribution load-time validation produces for this project, not `scenarios 'tail'`.
    assertEquals("scenarios 'a': cyclic dependency: a -> b -> a", failure.message)
  }

  @Test
  fun runtimeAttributesAnUndefinedNestedCallToTheReusableThatMakesIt() {
    val scenarios = listOf(ArbigentScenarioContent(id = "caller", uses = "outer"))
    val reusableScenarios = listOf(
      ArbigentScenarioContent(
        id = "outer",
        steps = listOf(ArbigentScenarioContent.ReusableStep(uses = "nowhere")),
        inputs = mapOf("user" to ArbigentScenarioContent.ReusableInput(default = "paid")),
      )
    )

    val failure = assertFailsWith<ArbigentProjectValidationException> {
      scenarios.createArbigentScenario(
        projectSettings = ArbigentProjectSettings(),
        scenario = scenarios.single(),
        aiFactory = { FakeAi() },
        deviceFactory = { FakeDevice() },
        aiDecisionCache = AiDecisionCacheStrategy.Disabled.toCache(),
        reusableScenarios = reusableScenarios,
      )
    }
    // Not `scenarios 'outer (user=paid)'` — that breadcrumb label is not a declaration.
    assertEquals(
      "reusableScenarios 'outer': uses 'nowhere' is not defined in reusableScenarios",
      failure.message
    )
  }

  @Test
  fun runtimeAttributesAReusableCycleEnteredFromOutsideToTheCycleItself() {
    val scenarios = listOf(ArbigentScenarioContent(id = "caller", uses = "entry"))
    val reusableScenarios = listOf(
      ArbigentScenarioContent(id = "entry", steps = listOf(ArbigentScenarioContent.ReusableStep(uses = "y"))),
      ArbigentScenarioContent(id = "y", steps = listOf(ArbigentScenarioContent.ReusableStep(uses = "z"))),
      ArbigentScenarioContent(id = "z", steps = listOf(ArbigentScenarioContent.ReusableStep(uses = "y"))),
    )

    val failure = assertFailsWith<ArbigentProjectValidationException> {
      scenarios.createArbigentScenario(
        projectSettings = ArbigentProjectSettings(),
        scenario = scenarios.single(),
        aiFactory = { FakeAi() },
        deviceFactory = { FakeDevice() },
        aiDecisionCache = AiDecisionCacheStrategy.Disabled.toCache(),
        reusableScenarios = reusableScenarios,
      )
    }
    assertEquals(
      "reusableScenarios 'y': cyclic reusable reference: y -> z -> y",
      failure.message
    )
  }

  @Test
  fun cyclicDependencyFailsAtLoadWithThePath() {
    assertValidationError(
      "scenarios 'a': cyclic dependency: a -> c -> b -> a",
      """
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
    )
  }

  @Test
  fun selfDependencyFailsAtLoad() {
    assertValidationError(
      "scenarios 'selfie': cyclic dependency: selfie -> selfie",
      """
      scenarios:
      - id: "selfie"
        goal: "Loop"
        dependency: "selfie"
      """
    )
  }

  @Test
  fun duplicateScenarioIdFailsAtLoad() {
    assertValidationError(
      "scenarios 'a': duplicate id (declared 2 times)",
      """
      scenarios:
      - id: "a"
        goal: "first"
      - id: "a"
        goal: "second"
      """
    )
  }

  @Test
  fun allViolationsAreReportedInOnePass() {
    val exception = assertFailsWith<ArbigentProjectValidationException> {
      load(
        """
        scenarios:
        - id: "dup"
          goal: "first"
        - id: "dup"
          goal: "second"
        - id: "orphan"
          goal: "O"
          dependency: "missing"
        - id: "loop-a"
          goal: "A"
          dependency: "loop-b"
        - id: "loop-b"
          goal: "B"
          dependency: "loop-a"
        - id: "bad-call"
          uses: "nowhere"
        """
      )
    }
    val message = exception.message!!
    assertTrue(message.contains("scenarios 'dup': duplicate id (declared 2 times)"), message)
    assertTrue(message.contains("scenarios 'orphan': dependency 'missing' is not defined in scenarios"), message)
    assertTrue(message.contains("scenarios 'loop-a': cyclic dependency: loop-a -> loop-b -> loop-a"), message)
    // Reusable violations are reported in the same pass, not one run later.
    assertTrue(message.contains("uses 'nowhere' is not defined in reusableScenarios"), message)
  }

  @Test
  fun aCycleSharedBySeveralScenariosIsReportedOnce() {
    val exception = assertFailsWith<ArbigentProjectValidationException> {
      load(
        """
        scenarios:
        - id: "a"
          goal: "A"
          dependency: "b"
        - id: "b"
          goal: "B"
          dependency: "a"
        - id: "c"
          goal: "C"
          dependency: "a"
        """
      )
    }
    assertEquals(
      1,
      exception.message!!.lines().count { it.contains("cyclic dependency:") },
      exception.message
    )
  }

  /**
   * Content built in memory never went through load-time validation, so the runtime repeats the
   * check instead of running a half-resolved chain.
   */
  @Test
  fun runtimeRejectsInMemoryContentWithADanglingDependency() {
    val scenarios = listOf(
      ArbigentScenarioContent(id = "a", goal = "A", dependencyId = "missing"),
    )
    val failure = assertFailsWith<ArbigentProjectValidationException> {
      scenarios.createArbigentScenario(
        projectSettings = ArbigentProjectSettings(),
        scenario = scenarios.single(),
        aiFactory = { FakeAi() },
        deviceFactory = { FakeDevice() },
        aiDecisionCache = AiDecisionCacheStrategy.Disabled.toCache(),
      )
    }
    assertEquals("scenarios 'a': dependency 'missing' is not defined in scenarios", failure.message)
  }

  /**
   * The graph renders whatever content it is handed — the UI draws it while the project is still
   * being edited — so a dangling reference only loses its edge.
   */
  @Test
  fun graphStillRendersInMemoryContentWithADanglingDependency() {
    val graph = ArbigentScenarioGraph.from(
      ArbigentProjectFileContent(
        scenarioContents = listOf(
          ArbigentScenarioContent(id = "a", goal = "A", dependencyId = "missing"),
        )
      )
    )
    assertEquals(listOf("a"), graph.nodes.map { it.title })
    assertEquals(emptyList(), graph.edges)
  }

  /**
   * The UI tree ordering: roots first, dependents indented under them. A scenario pointing at
   * something outside the list is shown as a root.
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
   * A mutual cycle gives every item a dependency inside the list, so it has no root. The first
   * member is surfaced as a root instead of dropping the group, because
   * `getCurrentProjectFileContent()` serializes this ordering and a dropped scenario is a
   * scenario deleted from the saved project.
   */
  @Test
  fun mutualDependencyCycleKeepsEveryItemWithTheFirstAsRoot() {
    class Item(val name: String, var dependency: Item? = null)

    val a = Item("a")
    val b = Item("b", a)
    a.dependency = b
    val unrelated = Item("unrelated")

    val ordered = ArbigentScenarioResolver.dependencyForestWithDepth(listOf(a, b, unrelated)) {
      it.dependency
    }

    // Genuine roots keep their order and come first; the cycle is appended rather than dropped.
    assertEquals(
      listOf("unrelated" to 0, "a" to 0, "b" to 1),
      ordered.map { (item, depth) -> item.name to depth }
    )
  }

  /**
   * A dependent of a cycle stays a dependent even when it is declared before the cycle. Entering
   * the group at the first unplaced item would emit `trailing` as a root and then be unable to
   * attach it under `a`, so the fallback walks up to a cycle member first.
   */
  @Test
  fun aDependentDeclaredBeforeTheCycleItPointsIntoIsNotShownAsARoot() {
    class Item(val name: String, var dependency: Item? = null)

    val a = Item("a")
    val b = Item("b", a)
    a.dependency = b
    val trailing = Item("trailing", a)

    val ordered = ArbigentScenarioResolver.dependencyForestWithDepth(listOf(trailing, a, b)) {
      it.dependency
    }

    assertEquals(0, ordered.single { it.first.name == "a" }.second)
    assertEquals(1, ordered.single { it.first.name == "trailing" }.second)
    assertEquals(1, ordered.single { it.first.name == "b" }.second)
    assertEquals(3, ordered.size)
  }

  /** A scenario hanging off a cycle must survive too, and must not be visited twice. */
  @Test
  fun aDependentOfACycleIsListedOnce() {
    class Item(val name: String, var dependency: Item? = null)

    val a = Item("a")
    val b = Item("b", a)
    a.dependency = b
    val trailing = Item("trailing", a)

    val ordered = ArbigentScenarioResolver.dependencyForestWithDepth(listOf(a, b, trailing)) {
      it.dependency
    }

    assertEquals(listOf("a", "b", "trailing"), ordered.map { it.first.name }.sorted())
    assertEquals(3, ordered.size)
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
      "scenarios 'a': uses 'nowhere' is not defined in reusableScenarios",
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
      "reusableScenarios 'first': cyclic reusable reference: first -> second -> first",
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
    val tasks = project.tasksOf("buy")
    assertEquals(listOf("setup", "buy", "buy"), tasks.map { it.scenarioId })
    assertEquals(listOf("Setup", "Log in as paid", "Checkout"), tasks.map { it.goal })
    assertEquals(
      listOf(null, "buy › prepare (user=paid) › login (user=paid)", "buy › checkout"),
      tasks.map { it.callBreadcrumb }
    )
  }
}
