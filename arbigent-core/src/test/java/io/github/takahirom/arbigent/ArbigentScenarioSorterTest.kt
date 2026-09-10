package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.ArbigentProjectSerializer
import io.github.takahirom.arbigent.ArbigentProjectValidationException
import io.github.takahirom.arbigent.ArbigentScenarioSorter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArbigentScenarioSorterTest {

  /** A child appended at the end of the file, far from its parent. */
  private val appendedChild = """
scenarios:
- id: "launch-app"
  goal: "Launch the app"
- id: "open-settings"
  goal: "Open settings"
  dependency: "launch-app"
- id: "open-search"
  goal: "Open search"
  dependency: "launch-app"
- id: "toggle-dark-mode"
  goal: "Toggle dark mode"
  dependency: "open-settings"
""".trimStart()

  @Test
  fun `moves a scenario directly after its dependency and comments every scenario`() {
    val result = ArbigentScenarioSorter.sort(appendedChild)

    assertEquals(
      """
scenarios:
# [depth 0] launch-app | children: open-settings, open-search
- id: "launch-app"
  goal: "Launch the app"
# [depth 1] launch-app > open-settings | children: toggle-dark-mode
- id: "open-settings"
  goal: "Open settings"
  dependency: "launch-app"
# [depth 2] launch-app > open-settings > toggle-dark-mode
- id: "toggle-dark-mode"
  goal: "Toggle dark mode"
  dependency: "open-settings"
# [depth 1] launch-app > open-search
- id: "open-search"
  goal: "Open search"
  dependency: "launch-app"
""".trimStart(),
      result.yaml
    )
    assertEquals(listOf("toggle-dark-mode", "open-search"), result.movedScenarioIds)
    assertEquals(
      listOf("launch-app", "open-settings", "toggle-dark-mode", "open-search"),
      result.staleCommentScenarioIds
    )
  }

  @Test
  fun `sorting is idempotent`() {
    val once = ArbigentScenarioSorter.sort(appendedChild).yaml
    val twice = ArbigentScenarioSorter.sort(once)

    assertEquals(once, twice.yaml)
    assertEquals(emptyList(), twice.movedScenarioIds)
    assertEquals(emptyList(), twice.staleCommentScenarioIds)
  }

  @Test
  fun `only the position comments are rewritten, everything else stays byte for byte`() {
    // Hand-written styles the serializer would normalize away: a header comment, blank lines,
    // an unquoted id, a folded goal, an unknown key, a comment inside a block scalar, a comment
    // between keys, trailing sections, and an outdated position comment.
    val original = """
# Project for the example app
settings:
  maxRetry: 2
scenarios:

  # The entry point
  # [depth 7] stale > chain
  - id: launch-app
    goal: >-
      Launch the app
      # not a comment, part of the goal
    unknownKey: kept
  - id: open-settings
    # why: settings is the hub
    dependency: launch-app
    goal: "Open settings"

reusableScenarios: []
""".trimStart()

    val result = ArbigentScenarioSorter.sort(original)

    assertEquals(
      """
# Project for the example app
settings:
  maxRetry: 2
scenarios:

  # The entry point
  # [depth 0] launch-app | children: open-settings
  - id: launch-app
    goal: >-
      Launch the app
      # not a comment, part of the goal
    unknownKey: kept
  # [depth 1] launch-app > open-settings
  - id: open-settings
    # why: settings is the hub
    dependency: launch-app
    goal: "Open settings"

reusableScenarios: []
""".trimStart(),
      result.yaml
    )
    assertEquals(emptyList(), result.movedScenarioIds)
    assertEquals(listOf("launch-app", "open-settings"), result.staleCommentScenarioIds)
  }

  @Test
  fun `comments stay attached to the scenario they describe when it moves`() {
    val original = """
scenarios:
- id: "launch-app"
  goal: "Launch the app"

# Dark mode lives under settings
- id: "toggle-dark-mode"
  goal: "Toggle dark mode"
  dependency: "open-settings"
- id: "open-settings"
  goal: "Open settings"
  dependency: "launch-app"
""".trimStart()

    val sorted = ArbigentScenarioSorter.sort(original, positionComments = false).yaml

    assertEquals(
      """
scenarios:
- id: "launch-app"
  goal: "Launch the app"

- id: "open-settings"
  goal: "Open settings"
  dependency: "launch-app"
# Dark mode lives under settings
- id: "toggle-dark-mode"
  goal: "Toggle dark mode"
  dependency: "open-settings"
""".trimStart(),
      sorted
    )
  }

  @Test
  fun `disabling position comments removes the ones already there`() {
    val commented = ArbigentScenarioSorter.sort(appendedChild).yaml

    val result = ArbigentScenarioSorter.sort(commented, positionComments = false)

    assertTrue(result.yaml.lines().none { ArbigentScenarioSorter.POSITION_COMMENT_MARKER.containsMatchIn(it) }, result.yaml)
    assertEquals(4, result.staleCommentScenarioIds.size)
  }

  @Test
  fun `settings positionComments false is the default for the file`() {
    val original = "settings:\n  positionComments: false\n" + appendedChild

    val result = ArbigentScenarioSorter.sort(original)

    assertTrue(result.yaml.lines().none { it.startsWith("#") }, result.yaml)
    assertEquals(listOf("toggle-dark-mode", "open-search"), result.movedScenarioIds)
  }

  @Test
  fun `roots and siblings keep their declared order`() {
    val original = """
scenarios:
- id: "zeta-flow"
  goal: "Z"
- id: "alpha-flow"
  goal: "A"
- id: "zeta-step"
  goal: "Z1"
  dependency: "zeta-flow"
- id: "alpha-step-b"
  goal: "AB"
  dependency: "alpha-flow"
- id: "alpha-step-a"
  goal: "AA"
  dependency: "alpha-flow"
""".trimStart()

    val ids = ArbigentScenarioSorter.sort(original, positionComments = false).yaml
      .lines().filter { it.startsWith("- id:") }.map { it.removePrefix("- id: ").trim('"') }

    assertEquals(listOf("zeta-flow", "zeta-step", "alpha-flow", "alpha-step-b", "alpha-step-a"), ids)
  }

  @Test
  fun `the serializer output round trips through sort without changes except comments`() {
    // The UI passes scenarios already in dependency order; mirror that by encoding a sorted project.
    val content = ArbigentProjectSerializer().load(ArbigentScenarioSorter.sort(appendedChild).yaml)
    val encoded = ArbigentProjectSerializer().encodeToString(content)

    val sorted = ArbigentScenarioSorter.sort(encoded, content, positionComments = true).yaml

    assertEquals(
      encoded.lines(),
      sorted.lines().filterNot { ArbigentScenarioSorter.POSITION_COMMENT_MARKER.containsMatchIn(it) }
    )
    assertEquals(4, sorted.lines().count { ArbigentScenarioSorter.POSITION_COMMENT_MARKER.containsMatchIn(it) })
  }

  @Test
  fun `a project without scenarios is returned unchanged`() {
    val original = "scenarios: []\nreusableScenarios: []\n"

    assertEquals(original, ArbigentScenarioSorter.sort(original).yaml)
  }

  @Test
  fun `a cyclic dependency is rejected with the validation report`() {
    val original = """
scenarios:
- id: "a"
  goal: "A"
  dependency: "b"
- id: "b"
  goal: "B"
  dependency: "a"
""".trimStart()

    val e = assertFailsWith<ArbigentProjectValidationException> { ArbigentScenarioSorter.sort(original) }
    assertTrue(e.message!!.contains("cyclic dependency"), e.message)
  }

  @Test
  fun `a flow-style scenarios list is rejected instead of being rewritten`() {
    val original = """scenarios: [{id: "a", goal: "A"}]""" + "\n"

    val e = assertFailsWith<IllegalArgumentException> { ArbigentScenarioSorter.sort(original) }
    assertTrue(e.message!!.contains("scenarios"), e.message)
  }
}
