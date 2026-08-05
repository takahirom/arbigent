package io.github.takahirom.arbigent.ui

import io.github.takahirom.arbigent.ArbigentTagManager
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The dependency menu is the only place the editor can build a dependency cycle, so it is where
 * cycles are prevented rather than reported: a scenario that already depends on this one is not
 * offered as a choice.
 */
class DependencySelectionTest {

  @Before
  fun setup() {
    globalKeyStoreFactory = TestKeyStoreFactory()
  }

  private fun appWith(vararg ids: String): Pair<ArbigentAppStateHolder, Map<String, ArbigentScenarioStateHolder>> {
    val app = ArbigentAppStateHolder(aiFactory = { FakeAi() }, dispatcher = Dispatchers.Unconfined)
    val holders = ids.associateWith { id ->
      ArbigentScenarioStateHolder(
        id = id,
        tagManager = ArbigentTagManager(),
        dispatcher = Dispatchers.Unconfined,
      ).also { app.addScenarioStateHolder(it) }
    }
    return app to holders
  }

  private fun ArbigentScenarioStateHolder.dependsOn(other: ArbigentScenarioStateHolder) {
    dependencyScenarioStateHolderStateFlow.value = other
  }

  @Test
  fun `a scenario cannot pick itself`() {
    val (app, holders) = appWith("a", "b")

    assertEquals(listOf("b"), app.selectableDependencies(holders.getValue("a")).map { it.id })
  }

  @Test
  fun `a direct dependent is not offered`() {
    val (app, holders) = appWith("root", "child")
    holders.getValue("child").dependsOn(holders.getValue("root"))

    // `child` already runs after `root`, so root cannot also run after child.
    assertEquals(emptyList(), app.selectableDependencies(holders.getValue("root")).map { it.id })
    assertEquals(listOf("root"), app.selectableDependencies(holders.getValue("child")).map { it.id })
  }

  @Test
  fun `a transitive dependent is not offered`() {
    val (app, holders) = appWith("root", "child", "grandChild", "unrelated")
    holders.getValue("child").dependsOn(holders.getValue("root"))
    holders.getValue("grandChild").dependsOn(holders.getValue("child"))

    assertEquals(
      listOf("unrelated"),
      app.selectableDependencies(holders.getValue("root")).map { it.id }
    )
    assertEquals(
      listOf("root", "unrelated"),
      app.selectableDependencies(holders.getValue("child")).map { it.id }
    )
  }

  @Test
  fun `siblings can still depend on each other`() {
    val (app, holders) = appWith("root", "left", "right")
    holders.getValue("left").dependsOn(holders.getValue("root"))
    holders.getValue("right").dependsOn(holders.getValue("root"))

    // `left` does not depend on `right`, so this pairing is allowed and creates no cycle.
    assertEquals(
      listOf("root", "left"),
      app.selectableDependencies(holders.getValue("right")).map { it.id }
    )
  }

  @Test
  fun `choices are listed in tree order without an active collector`() {
    val (app, holders) = appWith("child", "root", "loose")
    holders.getValue("child").dependsOn(holders.getValue("root"))

    // Declared dependent-first: reading the uncollected ordering flow would fall back to
    // declaration order and list `child` before `root`.
    assertEquals(
      listOf("root", "loose"),
      app.selectableDependencies(holders.getValue("child")).map { it.id }
    )
  }

  @Test
  fun `scenario ids that are already taken are rejected`() {
    val (app, _) = appWith("taken", "other")

    // The id field only commits a change when this returns 0; see ScenarioFundamentalOptions.
    assertEquals(1, app.scenarioCountById("taken"))
    assertEquals(0, app.scenarioCountById("fresh"))
  }
}
