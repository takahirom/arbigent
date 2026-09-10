@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.testing.test
import io.github.takahirom.arbigent.ArbigentInternalApi
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SortCommandTest {
  private val yaml = File("build/arbigent/sort-project.yaml")

  private val unsorted = """
scenarios:
- id: "launch-app"
  goal: "Launch the app"
- id: "open-search"
  goal: "Open search"
  dependency: "launch-app"
- id: "open-settings"
  goal: "Open settings"
  dependency: "launch-app"
- id: "toggle-dark-mode"
  goal: "Toggle dark mode"
  dependency: "open-settings"
- id: "type-keyword"
  goal: "Type a keyword"
  dependency: "open-search"
""".trimStart()

  @BeforeTest
  fun setup() {
    yaml.parentFile.mkdirs()
    yaml.writeText(unsorted)
  }

  @Test
  fun `sort rewrites the file in dependency order with position comments`() {
    val result = ArbigentSortCommand().test("--project-file=${yaml.absolutePath}")

    assertEquals(0, result.statusCode, result.output)
    assertContains(result.output, "Sorted ${yaml.absolutePath}: 3 scenarios out of place, 5 position comments stale.")
    assertEquals(
      """
scenarios:
# [depth 0] launch-app | children: open-search, open-settings
- id: "launch-app"
  goal: "Launch the app"
# [depth 1] launch-app > open-search | children: type-keyword
- id: "open-search"
  goal: "Open search"
  dependency: "launch-app"
# [depth 2] launch-app > open-search > type-keyword
- id: "type-keyword"
  goal: "Type a keyword"
  dependency: "open-search"
# [depth 1] launch-app > open-settings | children: toggle-dark-mode
- id: "open-settings"
  goal: "Open settings"
  dependency: "launch-app"
# [depth 2] launch-app > open-settings > toggle-dark-mode
- id: "toggle-dark-mode"
  goal: "Toggle dark mode"
  dependency: "open-settings"
""".trimStart(),
      yaml.readText()
    )
  }

  @Test
  fun `a second sort reports the file as already sorted`() {
    ArbigentSortCommand().test("--project-file=${yaml.absolutePath}")
    val before = yaml.readText()

    val result = ArbigentSortCommand().test("--project-file=${yaml.absolutePath}")

    assertEquals(0, result.statusCode, result.output)
    assertEquals("Already sorted: ${yaml.absolutePath}\n", result.output)
    assertEquals(before, yaml.readText())
  }

  @Test
  fun `diff prints a unified diff and exits with 1 without touching the file`() {
    val result = ArbigentSortCommand().test("--project-file=${yaml.absolutePath} --diff")

    assertEquals(1, result.statusCode, result.output)
    assertEquals(unsorted, yaml.readText())
    assertContains(result.output, "--- ${yaml.absolutePath}")
    assertContains(result.output, "+++ ${yaml.absolutePath} (sorted)")
    assertContains(result.output, "+# [depth 2] launch-app > open-search > type-keyword")
    assertContains(result.output, "3 scenarios out of place, 5 position comments stale. Run `arbigent sort` to apply.")
  }

  @Test
  fun `diff exits with 0 when nothing would change`() {
    ArbigentSortCommand().test("--project-file=${yaml.absolutePath}")

    val result = ArbigentSortCommand().test("--project-file=${yaml.absolutePath} --diff")

    assertEquals(0, result.statusCode, result.output)
    assertEquals("Already sorted: ${yaml.absolutePath}\n", result.output)
  }

  @Test
  fun `no-comments sorts without position comments and removes existing ones`() {
    ArbigentSortCommand().test("--project-file=${yaml.absolutePath}")
    assertTrue(yaml.readText().contains("# [depth"))

    val result = ArbigentSortCommand().test("--project-file=${yaml.absolutePath} --no-comments")

    assertEquals(0, result.statusCode, result.output)
    assertFalse(yaml.readText().contains("# [depth"), yaml.readText())
    assertContains(result.output, "0 scenarios out of place, 5 position comments stale.")
  }

  @Test
  fun `a project that fails validation is reported and left alone`() {
    yaml.writeText(
      """
scenarios:
- id: "a"
  goal: "A"
  dependency: "missing"
""".trimStart()
    )

    val result = ArbigentSortCommand().test("--project-file=${yaml.absolutePath}")

    assertEquals(1, result.statusCode, result.output)
    assertContains(result.output, "Invalid project configuration in ${yaml.absolutePath}")
    assertContains(result.output, "'missing'")
    assertFalse(yaml.readText().contains("# [depth"))
  }
}
