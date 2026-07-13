package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.testing.test
import io.github.takahirom.arbigent.ArbigentProjectSerializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuideCommandTest {
  @Test
  fun topicNamesAreUniqueKebabCase() {
    val names = arbigentGuideTopics.map { it.name }
    assertEquals(names, names.distinct(), "Guide topic names must be unique")
    names.forEach { name ->
      assertTrue(name.matches(Regex("[a-z]+(-[a-z]+)*")), "Guide topic name must be kebab-case: $name")
    }
  }

  @Test
  fun descriptionsAreSingleLineAndNonEmpty() {
    arbigentGuideTopics.forEach { topic ->
      assertTrue(topic.description.isNotBlank(), "Description must not be blank: ${topic.name}")
      assertTrue(!topic.description.contains("\n"), "Description must be a single line: ${topic.name}")
    }
  }

  @Test
  fun rootHelpListsAllGuideTopics() {
    // The help formatter wraps lines to the terminal width, so compare with
    // all whitespace collapsed.
    val normalized = arbigentCli().test("--help").output.replace(Regex("\\s+"), " ")
    arbigentGuideTopics.forEach { topic ->
      assertContains(normalized, "${topic.name}: ${topic.description}")
    }
  }

  @Test
  fun guideSubcommandPrintsBody() {
    arbigentGuideTopics.forEach { topic ->
      val result = arbigentCli().test("guide ${topic.name}")
      assertEquals(0, result.statusCode, "guide ${topic.name} should succeed")
      assertEquals(topic.body.trim(), result.output.trim())
    }
  }

  @Test
  fun yamlExamplesInGuidesAreParseableProjectFiles() {
    val fence = Regex("```yaml\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
    var checked = 0
    arbigentGuideTopics.forEach { topic ->
      fence.findAll(topic.body).forEach { match ->
        val yaml = match.groupValues[1]
        val file = File.createTempFile("guide-${topic.name}", ".yaml")
        try {
          file.writeText(yaml)
          val content = ArbigentProjectSerializer().load(file)
          assertTrue(
            content.scenarioContents.isNotEmpty(),
            "YAML example in guide '${topic.name}' must define at least one scenario"
          )
          checked++
        } finally {
          file.delete()
        }
      }
    }
    assertTrue(checked > 0, "Expected at least one yaml example across guides")
  }
}
