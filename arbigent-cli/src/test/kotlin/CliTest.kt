package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.testing.test
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains

class CliTest {
  private val yaml = File("build/arbigent/arbigent-project.yaml")
  @BeforeTest
  fun setup() {
    yaml.parentFile.mkdirs()
    // setup
    yaml.writeText(
      """
scenarios:
- id: "f9c17741-093e-49f0-ad45-8311ba68c1a6"
  goal: "Search for the \"About emulated device\" item in the OS Settings. Please\
    \ do not click \"Device name\". Your goal is to reach the \"About emulated device\"\
    \ page."
  initializationMethods:
  - type: "LaunchApp"
    packageName: "com.android.settings"
  imageAssertions:
  - assertionPrompt: "The screen should display the \"About emulated device\" page."
  tags:
  - name: "Settings"
- id: "7c325428-4e0b-4756-ada5-4f53bdc433a2"
  goal: "Scroll and open \"Model\" page in \"About emulated device\" page. Be careful\
    \ not to open other pages"
  dependency: "f9c17741-093e-49f0-ad45-8311ba68c1a6"
  tags:
  - name: "Settings"
- id: "16c24dfc-cbc7-4e17-af68-c97ad0a2aa3f"
  goal: "Just open camera app"
  initializationMethods:
  - type: "LaunchApp"
    packageName: "com.android.camera2"
  cleanupData:
    type: "Cleanup"
    packageName: "com.android.camera2"
      """
    )
  }

  @Test
  fun `when run scenario it should select leaf scenarios`() {
    val command = ArbigentCli()
    val projectFileOption = "--project-file=${yaml.absolutePath}"

    val test = command.test(
      "$projectFileOption --dry-run",
      envvars = mapOf("OPENAI_API_KEY" to "key")
    )

    assertContains(test.output, "Selected scenarios for execution: [7c325428-4e0b-4756-ada5-4f53bdc433a2, 16c24dfc-cbc7-4e17-af68-c97ad0a2aa3f]")
  }

  @Test
  fun `when run scenario specifying id and shard it should run specified scenarios`() {
    val command = ArbigentCli()
    val projectFileOption = "--project-file=${yaml.absolutePath}"
    val option = "--shard=2/2 --scenario-id=f9c17741-093e-49f0-ad45-8311ba68c1a6,16c24dfc-cbc7-4e17-af68-c97ad0a2aa3f"

    val test = command.test(
      "$projectFileOption --dry-run $option",
      envvars = mapOf("OPENAI_API_KEY" to "key")
    )

    assertContains(test.output, "Selected scenarios for execution: [16c24dfc-cbc7-4e17-af68-c97ad0a2aa3f]")
  }

  @Test
  fun `when run scenario specifying tags and shard it should run specified scenarios`() {
    val command = ArbigentCli()
    val projectFileOption = "--project-file=${yaml.absolutePath}"
    val option = "--shard=2/2 --tags=Settings"

    val test = command.test(
      "$projectFileOption --dry-run $option",
      envvars = mapOf("OPENAI_API_KEY" to "key")
    )

    assertContains(
      test.output,
      "Selected scenarios for execution: [7c325428-4e0b-4756-ada5-4f53bdc433a2]"
    )
  }
}
