package io.github.takahirom.arbigent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArbigentJourneyXmlImporterTest {

  private fun resourceFile(path: String): File {
    val url = requireNotNull(javaClass.getResource(path)) { "Resource not found: $path" }
    return File(url.toURI())
  }

  private fun tempJourney(fileName: String, content: String): File {
    val dir = File.createTempFile("journey-test", "").let {
      it.delete()
      it.mkdirs()
      it
    }
    val file = File(dir, fileName)
    file.writeText(content)
    return file
  }

  @Test
  fun parseMinimalJourney() {
    val journey = ArbigentJourneyXmlImporter.parse(resourceFile("/journeys/weather.journey.xml"))
    assertEquals("Weather screen", journey.name)
    assertEquals("Verify that the Weather screen displays correctly when navigated to", journey.description)
    assertEquals(
      listOf(
        "Tap \"Weather\"",
        "Verify that the Weather screen is displayed and shows the current temperature"
      ),
      journey.actions
    )
  }

  @Test
  fun xmlSpacePreserveTrimsToSameResult() {
    val preserve = ArbigentJourneyXmlImporter.parse(resourceFile("/journeys/smart_paste.journey.xml"))
    val noPreserve = ArbigentJourneyXmlImporter.parse(
      tempJourney(
        "smart.journey.xml",
        """
        <?xml version="1.0" encoding="utf-8"?>
        <journey name="Smart Paste from Clipboard">
            <description>Verify that pasting multi-line text from the clipboard splits it into multiple items.</description>
            <actions>
                <action>Open Google Keep</action>
                <action>Long press the body text to select all content within.</action>
                <action>Tap "Copy" in the context menu to place the text into the system clipboard.</action>
                <action>Launch the grocery application</action>
                <action>Verify that the app automatically splits the text into three separate list items: "Carrots", "Potatoes", and "Onions"</action>
            </actions>
        </journey>
        """.trimIndent()
      )
    )
    assertEquals(preserve, noPreserve)
    assertEquals(5, preserve.actions.size)
  }

  @Test
  fun missingDescriptionFallsBackToEmpty() {
    val journey = ArbigentJourneyXmlImporter.parse(
      tempJourney(
        "nodesc.journey.xml",
        """
        <?xml version="1.0" encoding="utf-8"?>
        <journey name="No description">
            <actions>
                <action>Tap "Go"</action>
            </actions>
        </journey>
        """.trimIndent()
      )
    )
    assertEquals("", journey.description)
    assertEquals(listOf("Tap \"Go\""), journey.actions)
  }

  @Test
  fun missingNameFallsBackToFileStem() {
    val journey = ArbigentJourneyXmlImporter.parse(
      tempJourney(
        "my-flow.journey.xml",
        """
        <?xml version="1.0" encoding="utf-8"?>
        <journey>
            <actions>
                <action>Tap "Go"</action>
            </actions>
        </journey>
        """.trimIndent()
      )
    )
    assertEquals("my-flow", journey.name)
  }

  @Test
  fun missingNameStripsUnderscoreJourneySuffix() {
    val journey = ArbigentJourneyXmlImporter.parse(
      tempJourney(
        "checkout_journey.xml",
        """
        <?xml version="1.0" encoding="utf-8"?>
        <journey>
            <actions>
                <action>Tap "Go"</action>
            </actions>
        </journey>
        """.trimIndent()
      )
    )
    assertEquals("checkout", journey.name)
  }

  @Test
  fun blankActionsAreIgnored() {
    val journey = ArbigentJourneyXmlImporter.parse(
      tempJourney(
        "blank.journey.xml",
        """
        <?xml version="1.0" encoding="utf-8"?>
        <journey name="Blank actions">
            <actions>
                <action>Tap "Go"</action>
                <action>   </action>
            </actions>
        </journey>
        """.trimIndent()
      )
    )
    assertEquals(listOf("Tap \"Go\""), journey.actions)
  }

  @Test
  fun zeroActionsThrows() {
    val e = assertFailsWith<IllegalArgumentException> {
      ArbigentJourneyXmlImporter.parse(
        tempJourney(
          "empty.journey.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <journey name="Empty">
              <actions>
              </actions>
          </journey>
          """.trimIndent()
        )
      )
    }
    assertTrue(e.message!!.contains("at least one <action>"))
  }

  @Test
  fun malformedXmlThrows() {
    val e = assertFailsWith<IllegalArgumentException> {
      ArbigentJourneyXmlImporter.parse(
        tempJourney(
          "broken.journey.xml",
          "<journey name=\"Broken\"><actions><action>Tap</action>"
        )
      )
    }
    assertTrue(e.message!!.contains("Failed to parse"))
  }

  @Test
  fun wrongRootThrows() {
    val e = assertFailsWith<IllegalArgumentException> {
      ArbigentJourneyXmlImporter.parse(
        tempJourney(
          "wrongroot.journey.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <scenario name="Wrong">
              <actions>
                  <action>Tap</action>
              </actions>
          </scenario>
          """.trimIndent()
        )
      )
    }
    assertTrue(e.message!!.contains("must be <journey>"))
  }

  @Test
  fun toScenarioContentAssemblesGoalIdAndMaxStep() {
    val journey = ArbigentJourney(
      name = "Weather screen",
      description = "Verify that the Weather screen displays correctly when navigated to",
      actions = listOf(
        "Tap \"Weather\"",
        "Verify that the Weather screen is displayed and shows the current temperature"
      )
    )
    val content = ArbigentJourneyXmlImporter.toScenarioContent(journey, idHint = "weather")
    assertEquals("weather", content.id)
    assertEquals(
      """
      Verify that the Weather screen displays correctly when navigated to

      Follow these steps in order:
      1. Tap "Weather"
      2. Verify that the Weather screen is displayed and shows the current temperature
      """.trimIndent(),
      content.goal
    )
    // max(10, 2 * 2 + 2) = 10
    assertEquals(10, content.maxStep)
  }

  @Test
  fun toScenarioContentMaxStepScalesWithActions() {
    val journey = ArbigentJourney(
      name = "Big",
      description = "desc",
      actions = (1..6).map { "Step $it" }
    )
    val content = ArbigentJourneyXmlImporter.toScenarioContent(journey, idHint = "big")
    // max(10, 6 * 2 + 2) = 14
    assertEquals(14, content.maxStep)
  }

  @Test
  fun toScenarioContentSanitizesNameWhenNoIdHint() {
    val journey = ArbigentJourney(
      name = "Weather screen!",
      description = "",
      actions = listOf("Tap")
    )
    val content = ArbigentJourneyXmlImporter.toScenarioContent(journey)
    assertEquals("weather-screen", content.id)
    // No description -> goal starts directly with the steps header.
    assertTrue(content.goal.startsWith("Follow these steps in order:"))
  }

  @Test
  fun loadProjectContentSingleFile() {
    val content = ArbigentJourneyXmlImporter.loadProjectContent(resourceFile("/journeys/weather.journey.xml"))
    assertEquals(1, content.scenarioContents.size)
    assertEquals("weather", content.scenarioContents.first().id)
  }

  @Test
  fun loadProjectContentDirectoryCollectsBothNamingPatternsDeterministically() {
    val dir = resourceFile("/journeys/dir")
    val content = ArbigentJourneyXmlImporter.loadProjectContent(dir)
    assertEquals(2, content.scenarioContents.size)
    // Sorted by absolute path -> alpha before beta.
    assertEquals(listOf("alpha", "beta"), content.scenarioContents.map { it.id })
  }

  @Test
  fun loadProjectContentSanitizesFileNameDerivedIds() {
    val file = tempJourney(
      "My Flow.journey.xml",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <journey name="My Flow">
          <actions>
              <action>Tap "Go"</action>
          </actions>
      </journey>
      """.trimIndent()
    )
    val content = ArbigentJourneyXmlImporter.loadProjectContent(file)
    assertEquals("my-flow", content.scenarioContents.single().id)
  }

  @Test
  fun loadProjectContentDuplicateIdsThrow() {
    val journeyXml = """
      <?xml version="1.0" encoding="utf-8"?>
      <journey name="Foo">
          <actions>
              <action>Tap "Go"</action>
          </actions>
      </journey>
    """.trimIndent()
    val first = tempJourney("foo.journey.xml", journeyXml)
    File(first.parentFile, "foo_journey.xml").writeText(journeyXml)
    val e = assertFailsWith<IllegalArgumentException> {
      ArbigentJourneyXmlImporter.loadProjectContent(first.parentFile)
    }
    assertTrue(e.message!!.contains("Duplicate scenario ids"))
    assertTrue(e.message!!.contains("foo.journey.xml"))
    assertTrue(e.message!!.contains("foo_journey.xml"))
  }

  @Test
  fun loadProjectContentEmptyDirectoryThrows() {
    val emptyDir = File.createTempFile("empty-journeys", "").let {
      it.delete()
      it.mkdirs()
      it
    }
    val e = assertFailsWith<IllegalArgumentException> {
      ArbigentJourneyXmlImporter.loadProjectContent(emptyDir)
    }
    assertTrue(e.message!!.contains("No journey files"))
  }
}
