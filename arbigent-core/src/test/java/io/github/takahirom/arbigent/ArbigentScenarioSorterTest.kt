@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.ArbigentProjectSerializer
import io.github.takahirom.arbigent.ArbigentProjectValidationException
import io.github.takahirom.arbigent.ArbigentScenarioSorter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
# The "# tree:" lines below are generated from `dependency` by `arbigent sort` (and on UI save); do not edit them, rerun `arbigent sort`.
# tree: launch-app | children: open-settings, open-search
- id: "launch-app"
  goal: "Launch the app"
# tree: launch-app > open-settings | children: toggle-dark-mode
- id: "open-settings"
  goal: "Open settings"
  dependency: "launch-app"
# tree: launch-app > open-settings > toggle-dark-mode
- id: "toggle-dark-mode"
  goal: "Toggle dark mode"
  dependency: "open-settings"
# tree: launch-app > open-search
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
    assertFalse(twice.headerStale)
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
  # tree: stale > chain
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
# The "# tree:" lines below are generated from `dependency` by `arbigent sort` (and on UI save); do not edit them, rerun `arbigent sort`.

  # The entry point
  # tree: launch-app | children: open-settings
  - id: launch-app
    goal: >-
      Launch the app
      # not a comment, part of the goal
    unknownKey: kept
  # tree: launch-app > open-settings
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

    assertTrue(result.yaml.lines().none { it.startsWith("#") }, result.yaml)
    assertEquals(4, result.staleCommentScenarioIds.size)
    assertTrue(result.headerStale)
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
      sorted.lines().filterNot {
        ArbigentScenarioSorter.POSITION_COMMENT_MARKER.containsMatchIn(it) ||
          ArbigentScenarioSorter.HEADER_COMMENT_MARKER.containsMatchIn(it)
      }
    )
    assertEquals(4, sorted.lines().count { ArbigentScenarioSorter.POSITION_COMMENT_MARKER.containsMatchIn(it) })
  }

  @Test
  fun `a project without scenarios is returned unchanged`() {
    val original = "scenarios: []\nreusableScenarios: []\n"

    assertEquals(original, ArbigentScenarioSorter.sort(original).yaml)
  }

  @Test
  fun `a stale position comment separated from its scenario by a blank line is still replaced`() {
    val original = """
scenarios:
# tree: stale

- id: "launch-app"
  goal: "Launch the app"
# tree: also stale
""".trimStart()

    val result = ArbigentScenarioSorter.sort(original)

    assertEquals(
      """
scenarios:
# The "# tree:" lines below are generated from `dependency` by `arbigent sort` (and on UI save); do not edit them, rerun `arbigent sort`.

# tree: launch-app
- id: "launch-app"
  goal: "Launch the app"
""".trimStart(),
      result.yaml
    )
    assertEquals(listOf("launch-app"), result.staleCommentScenarioIds)
    assertTrue(result.headerStale)
    val removed = ArbigentScenarioSorter.sort(original, positionComments = false).yaml
    assertTrue(removed.lines().none { ArbigentScenarioSorter.POSITION_COMMENT_MARKER.containsMatchIn(it) }, removed)
  }

  @Test
  fun `line breaks in ids are escaped so the comment stays one line`() {
    val original = """
scenarios:
- id: "launch\napp"
  goal: "Launch the app"
""".trimStart()

    val result = ArbigentScenarioSorter.sort(original)

    assertEquals(
      """
scenarios:
# The "# tree:" lines below are generated from `dependency` by `arbigent sort` (and on UI save); do not edit them, rerun `arbigent sort`.
# tree: launch\napp
- id: "launch\napp"
  goal: "Launch the app"
""".trimStart(),
      result.yaml
    )
    assertEquals("launch\napp", ArbigentProjectSerializer().load(result.yaml).scenarioContents.single().id)
  }

  @Test
  fun `ids that could be misread in the comment are quoted`() {
    val original = """
scenarios:
- id: "a > b"
  goal: "Root with a separator in its id"
- id: "c, d"
  goal: "Child with a comma in its id"
  dependency: "a > b"
""".trimStart()

    val result = ArbigentScenarioSorter.sort(original)

    val comments = result.yaml.lines().filter { ArbigentScenarioSorter.POSITION_COMMENT_MARKER.containsMatchIn(it) }
    assertEquals(
      listOf("# tree: \"a > b\" | children: \"c, d\"", "# tree: \"a > b\" > \"c, d\""),
      comments
    )
  }

  @Test
  fun `a scenario without an explicit id is rejected instead of getting a random comment`() {
    val original = """
scenarios:
- goal: "Launch the app"
""".trimStart()

    val e = assertFailsWith<IllegalArgumentException> { ArbigentScenarioSorter.sort(original) }
    assertTrue(e.message!!.contains("explicit `id`"), e.message)
  }

  @Test
  fun `a quoted value continuing at column zero is rejected instead of swallowing a scenario`() {
    val original = """
scenarios:
- id: "open-settings"
  goal: "Open settings"
  dependency: "launch-app"
- id: "launch-app"
  goal: "Launch
the app"
""".trimStart()

    val e = assertFailsWith<IllegalArgumentException> { ArbigentScenarioSorter.sort(original) }
    assertTrue(e.message!!.contains("Reordering the scenarios would"), e.message)
  }

  @Test
  fun `a keep-chomping block scalar at the end of the list is rejected instead of losing its blank lines`() {
    val original = "scenarios:\n- id: \"open-settings\"\n  goal: \"Open settings\"\n  dependency: \"launch-app\"\n" +
      "- id: \"launch-app\"\n  goal: |+\n    Launch the app\n\n\n"

    val e = assertFailsWith<IllegalArgumentException> { ArbigentScenarioSorter.sort(original) }
    assertTrue(e.message!!.contains("Reordering the scenarios would"), e.message)
  }

  @Test
  fun `a file without a final newline sorts without changing any value`() {
    val original = appendedChild.trimEnd('\n')

    val result = ArbigentScenarioSorter.sort(original, positionComments = false)

    assertEquals(
      listOf("launch-app", "open-settings", "toggle-dark-mode", "open-search"),
      ArbigentProjectSerializer().load(result.yaml).scenarioContents.map { it.id }
    )
    assertTrue(!result.yaml.endsWith("\n"), result.yaml)
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
