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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InstructionCommandTest {
  private val yaml = File("build/arbigent/instruction-project.yaml")

  @BeforeTest
  fun setup() {
    yaml.parentFile.mkdirs()
    yaml.writeText(
      """
settings:
  prompt:
    appUiStructure: "Home > Settings > About emulated device"
scenarios:
- id: "launch"
  goal: "Open the app and confirm the top screen is shown"
  noteForHumans: "Use the shared test account"
  initializationMethods:
  - type: "CleanupData"
    packageName: "com.example.app"
  - type: "LaunchApp"
    packageName: "com.example.app"
- id: "login"
  goal: "Log in as {{username}}"
  dependency: "launch"
- id: "do-search"
  dependency: "login"
  uses: "search"
  with:
    term: "wifi"
reusableScenarios:
- id: "search"
  inputs:
    term:
      required: true
  goal: "Search for {{inputs.term}} and verify it appears in results"
  initializationMethods:
  - type: "LaunchApp"
    packageName: "com.example.app"
  imageAssertions:
  - assertionPrompt: "Results for {{inputs.term}} are shown"
      """.trimIndent()
    )
  }

  private fun run(vararg args: String) =
    ArbigentInstructionCommand().test(
      (listOf("--project-file=${yaml.absolutePath}") + args).joinToString(" ")
    )

  @Test
  fun `prints ordered chain with resolved goal init note and verification`() {
    val result = run("--scenario-ids=do-search")
    assertEquals(0, result.statusCode, result.output)
    val output = result.output

    assertContains(output, "# Instructions to reach scenario: do-search")

    // Ordered: launch -> login -> search (dependency-first, target last)
    val launchIndex = output.indexOf("## Step 1: launch")
    val loginIndex = output.indexOf("## Step 2: login")
    val searchIndex = output.indexOf("## Step 3: search")
    assertTrue(launchIndex >= 0, output)
    assertTrue(loginIndex > launchIndex, output)
    assertTrue(searchIndex > loginIndex, output)

    // Reusable goal expanded and {{inputs.term}} resolved.
    assertContains(output, "Search for wifi and verify it appears in results")

    // Initialization rendered in YAML order.
    assertContains(output, "Cleanup app data: package=com.example.app")
    assertContains(output, "Launch app: package=com.example.app")

    assertContains(output, "Note: Use the shared test account")

    // Image assertion resolved and rendered as verification.
    assertContains(output, "Verification: Results for wifi are shown")

    // Bare project variable stays unresolved and is listed.
    assertContains(output, "{{username}}")
    assertContains(output, "Variables")

    assertContains(output, "Device form factor: Mobile")
  }

  @Test
  fun `accepts multiple scenario ids`() {
    val result = run("--scenario-ids=launch,do-search")
    assertEquals(0, result.statusCode, result.output)
    assertContains(result.output, "# Instructions to reach scenario: launch")
    assertContains(result.output, "# Instructions to reach scenario: do-search")
  }

  @Test
  fun `unknown scenario id fails and lists available ids`() {
    val result = run("--scenario-ids=missing")
    assertNotEquals(0, result.statusCode, result.output)
    assertContains(result.output, "Unknown scenario id 'missing'")
    assertContains(result.output, "launch")
    assertContains(result.output, "do-search")
  }

  @Test
  fun `dangling dependency fails and names both scenarios`() {
    yaml.writeText(
      """
scenarios:
- id: "child"
  goal: "Do the thing"
  dependency: "missing-parent"
      """.trimIndent()
    )
    val result = run("--scenario-ids=child")
    assertNotEquals(0, result.statusCode, result.output)
    assertContains(result.output, "child")
    assertContains(result.output, "missing-parent")
  }

  @Test
  fun `renders legacy singular initializeMethods field`() {
    yaml.writeText(
      """
scenarios:
- id: "legacy"
  goal: "Open the app"
  initializeMethods:
    type: "LaunchApp"
    packageName: "com.legacy.app"
      """.trimIndent()
    )
    val result = run("--scenario-ids=legacy")
    assertEquals(0, result.statusCode, result.output)
    assertContains(result.output, "- Initialization:")
    assertContains(result.output, "Launch app: package=com.legacy.app")
  }

  @Test
  fun `include app ui structure flag toggles the section`() {
    val without = run("--scenario-ids=launch")
    assertEquals(0, without.statusCode, without.output)
    assertFalse(without.output.contains("Home > Settings > About emulated device"), without.output)

    val with = run("--scenario-ids=launch", "--include-app-ui-structure")
    assertEquals(0, with.statusCode, with.output)
    assertContains(with.output, "## App UI structure")
    assertContains(with.output, "Home > Settings > About emulated device")
  }
}
