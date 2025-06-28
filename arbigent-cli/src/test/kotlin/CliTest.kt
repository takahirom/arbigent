package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.sources.PropertiesValueSource
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
    val command = ArbigentCli().subcommands(ArbigentRunCommand())
    val projectFileOption = "--project-file=${yaml.absolutePath}"

    val test = command.test(
      "run $projectFileOption --dry-run",
      envvars = mapOf("OPENAI_API_KEY" to "key")
    )

    assertContains(test.output, "Selected scenarios for execution: [7c325428-4e0b-4756-ada5-4f53bdc433a2, 16c24dfc-cbc7-4e17-af68-c97ad0a2aa3f]")
  }

  @Test
  fun `when run scenario specifying id and shard it should run specified scenarios`() {
    val command = ArbigentCli().subcommands(ArbigentRunCommand())
    val projectFileOption = "--project-file=${yaml.absolutePath}"
    val option = "--shard=2/2 --scenario-ids=f9c17741-093e-49f0-ad45-8311ba68c1a6,16c24dfc-cbc7-4e17-af68-c97ad0a2aa3f"

    val test = command.test(
      "run $projectFileOption --dry-run $option",
      envvars = mapOf("OPENAI_API_KEY" to "key")
    )

    assertContains(test.output, "Selected scenarios for execution: [16c24dfc-cbc7-4e17-af68-c97ad0a2aa3f]")
  }

  @Test
  fun `when run scenario specifying tags and shard it should run specified scenarios`() {
    val command = ArbigentCli().subcommands(ArbigentRunCommand())
    val projectFileOption = "--project-file=${yaml.absolutePath}"
    val option = "--shard=1/2 --tags=Settings"

    val test = command.test(
      "run $projectFileOption --dry-run $option",
      envvars = mapOf("OPENAI_API_KEY" to "key")
    )
    assertContains(
      test.output,
      "Selected scenarios for execution: [7c325428-4e0b-4756-ada5-4f53bdc433a2]"
    )
  }

  @Test
  fun `test global option placement`() {
    val command = ArbigentCli().subcommands(ArbigentRunCommand())
    val projectFileOption = "--project-file=${yaml.absolutePath}"

    // Current form: run --project-file=...
    val currentTest = command.test(
      "run $projectFileOption --dry-run",
      envvars = mapOf("OPENAI_API_KEY" to "key")
    )
    
    // Would fail if we try global form: --project-file=... run
    // (This test documents current behavior)
    assertContains(currentTest.output, "Selected scenarios for execution")
  }

  @Test
  fun `confirm global option syntax - requires option before subcommand`() {
    val command = ArbigentCli().subcommands(ArbigentRunCommand())
    val projectFileOption = "--project-file=${yaml.absolutePath}"

    // Current syntax: run --project-file=...
    val currentTest = command.test(
      "run $projectFileOption --dry-run",
      envvars = mapOf("OPENAI_API_KEY" to "key")
    )
    println("Current syntax success: ${currentTest.statusCode == 0}")
    
    // Global syntax would be: --project-file=... run  
    val globalTest = command.test(
      "$projectFileOption run --dry-run",
      envvars = mapOf("OPENAI_API_KEY" to "key")
    )
    println("Global syntax success: ${globalTest.statusCode == 0}")
    println("Global test output: ${globalTest.output}")
    
    // Document the syntax difference
    assert(currentTest.statusCode == 0) { "Current syntax failed: ${currentTest.output}" }
    assert(globalTest.statusCode != 0) { "Global syntax succeeded unexpectedly: ${globalTest.output}" }
  }

  @Test
  fun `test properties file with global option - no prefix needed`() {
    // Create properties file with global option (no prefix)
    val propsFile = File("build/test-arbigent.properties")
    propsFile.parentFile.mkdirs()
    propsFile.writeText("""
      project-file=${yaml.absolutePath}
    """.trimIndent())

    val command = ArbigentCli().apply {
      context {
        valueSource = PropertiesValueSource.from(propsFile.absolutePath)
      }
    }.subcommands(ArbigentRunCommand())

    val globalTest = command.test(
      "run --dry-run",
      envvars = mapOf("OPENAI_API_KEY" to "key")
    )
    
    println("Properties test output: ${globalTest.output}")
    println("Properties test exit code: ${globalTest.statusCode}")
    
    // Should succeed using properties file value
    assertContains(globalTest.output, "Selected scenarios for execution")
    
    propsFile.delete()
  }
}
