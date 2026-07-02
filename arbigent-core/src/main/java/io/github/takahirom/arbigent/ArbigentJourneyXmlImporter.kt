package io.github.takahirom.arbigent

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Intermediate DTO representing an Android Studio Journeys `*.journey.xml` file.
 */
public data class ArbigentJourney(
  val name: String,
  val description: String,
  val actions: List<String>,
)

/**
 * Imports Android Studio Journeys `*.journey.xml` / `*_journey.xml` files and converts
 * them into Arbigent's project model so they can be run by the CLI.
 *
 * See tmp/journeys.md for the source format. This is an import-only MVP: verify actions are
 * kept inside the goal (not promoted to imageAssertions) and launch actions are not promoted to
 * initializationMethods.
 */
public object ArbigentJourneyXmlImporter {
  private val JOURNEY_FILE_SUFFIXES = listOf(".journey.xml", "_journey.xml")

  /**
   * Returns true when [file] name looks like a Journeys XML file.
   */
  public fun isJourneyFile(file: File): Boolean {
    val name = file.name.lowercase()
    return JOURNEY_FILE_SUFFIXES.any { name.endsWith(it) }
  }

  /**
   * Parses a single `*.journey.xml` file into an [ArbigentJourney].
   */
  public fun parse(file: File): ArbigentJourney {
    if (!file.exists()) {
      throw IllegalArgumentException("Journey file does not exist: ${file.absolutePath}")
    }
    val document = try {
      val factory = DocumentBuilderFactory.newInstance().apply {
        // XXE hardening: disable DTDs and external entity resolution.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
        isNamespaceAware = true
      }
      factory.newDocumentBuilder().parse(file)
    } catch (e: Exception) {
      throw IllegalArgumentException(
        "Failed to parse journey XML file ${file.absolutePath}: ${e.message}",
        e
      )
    }

    val root = document.documentElement
      ?: throw IllegalArgumentException("Journey file has no root element: ${file.absolutePath}")
    if (root.localOrTagName() != "journey") {
      throw IllegalArgumentException(
        "Journey file root element must be <journey> but was <${root.tagName}>: ${file.absolutePath}"
      )
    }

    val name = root.getAttribute("name").trim().ifBlank { journeyIdHint(file) }

    val description = childElements(root)
      .firstOrNull { it.localOrTagName() == "description" }
      ?.textContent
      ?.trim()
      .orEmpty()

    val actionsContainer = childElements(root)
      .firstOrNull { it.localOrTagName() == "actions" }
    val actionTexts = actionsContainer
      ?.let { childElements(it) }
      ?.filter { it.localOrTagName() == "action" }
      ?.map { it.textContent.trim() }
      .orEmpty()
    val blankActionCount = actionTexts.count { it.isEmpty() }
    if (blankActionCount > 0) {
      arbigentWarnLog(
        "Ignoring $blankActionCount blank <action> element(s) in ${file.absolutePath}"
      )
    }
    val actions: List<String> = actionTexts.filter { it.isNotEmpty() }

    if (actions.isEmpty()) {
      throw IllegalArgumentException(
        "Journey file must contain at least one <action> under <actions>: ${file.absolutePath}"
      )
    }

    return ArbigentJourney(
      name = name,
      description = description,
      actions = actions,
    )
  }

  /**
   * Converts an [ArbigentJourney] into an [ArbigentScenarioContent].
   *
   * @param idHint preferred scenario id source (typically the file name stem). Falls back to the
   * journey name. Either way the id is sanitized to a lowercase-dash form.
   */
  public fun toScenarioContent(journey: ArbigentJourney, idHint: String? = null): ArbigentScenarioContent {
    val id = sanitizeId(idHint?.takeIf { it.isNotBlank() } ?: journey.name)
    val numberedActions = journey.actions
      .mapIndexed { index, action -> "${index + 1}. $action" }
      .joinToString("\n")
    val goal = buildString {
      if (journey.description.isNotBlank()) {
        append(journey.description)
        append("\n\n")
      }
      append("Follow these steps in order:\n")
      append(numberedActions)
    }
    val maxStep = maxOf(10, journey.actions.size * 2 + 2)
    return ArbigentScenarioContent(
      id = id,
      goal = goal,
      maxStep = maxStep,
    )
  }

  /**
   * Loads a journey source (a single file or a directory containing journey files) into an
   * [ArbigentProjectFileContent] with one scenario per journey file.
   */
  public fun loadProjectContent(fileOrDirectory: File): ArbigentProjectFileContent {
    if (!fileOrDirectory.exists()) {
      throw IllegalArgumentException("Journey path does not exist: ${fileOrDirectory.absolutePath}")
    }
    val files = if (fileOrDirectory.isDirectory) {
      fileOrDirectory.walkTopDown()
        .filter { it.isFile && isJourneyFile(it) }
        .sortedBy { it.absolutePath }
        .toList()
        .ifEmpty {
          throw IllegalArgumentException(
            "No journey files (*.journey.xml / *_journey.xml) found under directory: ${fileOrDirectory.absolutePath}"
          )
        }
    } else {
      listOf(fileOrDirectory)
    }

    val fileToContent = files.map { file ->
      val journey = parse(file)
      val idHint = journeyIdHint(file)
      file to toScenarioContent(journey, idHint)
    }

    val duplicates = fileToContent.groupBy { it.second.id }.filterValues { it.size > 1 }
    if (duplicates.isNotEmpty()) {
      val details = duplicates.entries.joinToString("; ") { (id, entries) ->
        "'$id' from ${entries.joinToString(", ") { it.first.absolutePath }}"
      }
      throw IllegalArgumentException(
        "Duplicate scenario ids derived from journey files: $details"
      )
    }

    return ArbigentProjectFileContent(scenarioContents = fileToContent.map { it.second })
  }

  private fun journeyIdHint(file: File): String {
    var name = file.name
    for (suffix in JOURNEY_FILE_SUFFIXES) {
      if (name.lowercase().endsWith(suffix)) {
        name = name.substring(0, name.length - suffix.length)
        break
      }
    }
    return name
  }

  private fun sanitizeId(name: String): String {
    val sanitized = name.trim()
      .lowercase()
      .replace(Regex("[^a-z0-9]+"), "-")
      .trim('-')
    return sanitized.ifBlank { "journey" }
  }

  private fun childElements(node: Node): List<Element> {
    val result = mutableListOf<Element>()
    val children = node.childNodes
    for (i in 0 until children.length) {
      val child = children.item(i)
      if (child is Element) {
        result.add(child)
      }
    }
    return result
  }

  private fun Element.localOrTagName(): String = localName ?: tagName
}
