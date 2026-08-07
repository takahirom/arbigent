package io.github.takahirom.arbigent.ui

import io.github.takahirom.arbigent.ArbigentProjectSerializer
import io.github.takahirom.arbigent.ArbigentTagManager
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `getCurrentProjectFileContent()` writes scenarios in the order `sortedScenariosAndDepths()`
 * produces, appending anything that ordering does not place. These tests cover both halves: the
 * ordering keeps every scenario (a cycle used to drop out of it entirely), and the save path
 * stays lossless regardless of what the ordering returns.
 */
class ScenarioOrderingUiTest {

  @Before
  fun setup() {
    globalKeyStoreFactory = TestKeyStoreFactory()
  }

  /** Errors the app surfaced, captured instead of opening the Swing dialog. */
  private val errors = mutableListOf<Throwable>()

  private fun appWith(vararg ids: String): Pair<ArbigentAppStateHolder, Map<String, ArbigentScenarioStateHolder>> {
    val app = ArbigentAppStateHolder(
      aiFactory = { FakeAi() },
      dispatcher = Dispatchers.Unconfined,
      onRecoverableError = { errors += it },
    )
    val holders = ids.associateWith { id ->
      ArbigentScenarioStateHolder(
        id = id,
        tagManager = ArbigentTagManager(),
        dispatcher = Dispatchers.Unconfined,
      ).also { app.addScenarioStateHolder(it) }
    }
    return app to holders
  }

  @Test
  fun `scenarios in a dependency cycle are still ordered`() {
    val (app, holders) = appWith("a", "b", "unrelated")
    holders.getValue("a").dependencyScenarioStateHolderStateFlow.value = holders.getValue("b")
    holders.getValue("b").dependencyScenarioStateHolderStateFlow.value = holders.getValue("a")

    val ordered = app.sortedScenariosAndDepths()

    // Genuine roots keep their order and come first; the cycle is appended rather than dropped.
    assertEquals(listOf("unrelated" to 0, "a" to 0, "b" to 1), ordered.map { it.first.id to it.second })
  }

  @Test
  fun `a scenario depending on a cycle is listed exactly once`() {
    val (app, holders) = appWith("a", "b", "trailing")
    holders.getValue("a").dependencyScenarioStateHolderStateFlow.value = holders.getValue("b")
    holders.getValue("b").dependencyScenarioStateHolderStateFlow.value = holders.getValue("a")
    holders.getValue("trailing").dependencyScenarioStateHolderStateFlow.value = holders.getValue("a")

    val ordered = app.sortedScenariosAndDepths().map { it.first.id }

    assertEquals(listOf("a", "b", "trailing"), ordered.sorted())
  }

  /**
   * The save path must never write a project that is missing scenarios. With a cycle present,
   * validation rejects the save outright; without one, every scenario reaches the file.
   */
  @Test
  fun `saving a project with a dependency cycle writes nothing rather than dropping scenarios`() {
    val (app, holders) = appWith("a", "b", "unrelated")
    holders.getValue("a").dependencyScenarioStateHolderStateFlow.value = holders.getValue("b")
    holders.getValue("b").dependencyScenarioStateHolderStateFlow.value = holders.getValue("a")
    val file = File.createTempFile("arbigent-cycle", ".yml").also { it.delete() }

    app.saveProjectContents(file)

    assertFalse(file.exists(), "a project with a cyclic dependency must not be written")
    assertEquals(1, errors.size, "the rejected save must be reported once")
    assertTrue(
      errors.single().message!!.contains("scenarios 'a': cyclic dependency: a -> b -> a"),
      errors.single().message
    )
    file.delete()
  }

  /**
   * Nothing collects `sortedScenariosAndDepthsStateFlow` here, which is the state the app is in
   * before the scenario list is first composed and whenever collection stops. Saving must still
   * write every scenario, so the ordering is recomputed rather than read from that flow.
   */
  @Test
  fun `saving an ordinary project writes every scenario without an active collector`() {
    // Declared dependent-first, so dependency order and declaration order disagree: reading the
    // uncollected flow would fall back to declaration order and be caught here.
    val (app, holders) = appWith("child", "root", "loose")
    holders.getValue("child").dependencyScenarioStateHolderStateFlow.value = holders.getValue("root")
    val file = File.createTempFile("arbigent-ok", ".yml")

    app.saveProjectContents(file)

    val written = ArbigentProjectSerializer().load(file).scenarioContents.map { it.id }
    assertEquals(listOf("root", "child", "loose"), written)
    file.delete()
  }

  @Test
  fun `an ordinary dependency chain keeps roots first and dependents indented`() {
    val (app, holders) = appWith("root", "child", "grandChild")
    holders.getValue("child").dependencyScenarioStateHolderStateFlow.value = holders.getValue("root")
    holders.getValue("grandChild").dependencyScenarioStateHolderStateFlow.value = holders.getValue("child")

    val ordered = app.sortedScenariosAndDepths()

    assertEquals(
      listOf("root" to 0, "child" to 1, "grandChild" to 2),
      ordered.map { it.first.id to it.second }
    )
  }
}
