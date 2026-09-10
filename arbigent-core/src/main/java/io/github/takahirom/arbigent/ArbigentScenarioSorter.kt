package io.github.takahirom.arbigent

/**
 * Rewrites the `scenarios:` section of a project YAML into depth-first dependency order: every
 * scenario comes after the scenario it depends on, and its whole subtree comes before the next
 * sibling (the same order the UI saves in), and puts a *position comment*
 * above each scenario that spells out where it sits in the dependency tree:
 *
 * ```yaml
 * scenarios:
 * # [depth 0] launch-app | children: complete-onboarding
 * - id: launch-app
 *   ...
 * # [depth 2] launch-app > complete-onboarding > open-search | children: type-keyword
 * - id: open-search
 *   dependency: complete-onboarding
 * ```
 *
 * The comments are derived from `dependency` on every write and carry no information of their
 * own; `dependency` stays the single source of truth. They exist so that a reader — a person or a
 * coding agent looking at one scenario in a flat file — can see its depth, its ancestors and its
 * direct dependents without searching the rest of the file.
 *
 * The rewrite works on the YAML *text*, not on a decode/encode round trip: each `- id:` item is
 * cut out as a block of lines and the blocks are reordered. Everything inside a block — quoting
 * style, comments, blank lines, keys the serializer does not know — is left byte-for-byte as it
 * was. Only lines matching [POSITION_COMMENT_MARKER] are regenerated. Roots and siblings keep
 * their declared order, so a hand-curated grouping of top-level flows survives.
 */
public object ArbigentScenarioSorter {

  /** A line that this sorter wrote and owns; any such line is replaced on the next sort. */
  public val POSITION_COMMENT_MARKER: Regex = Regex("""^\s*#\s*\[depth \d+]""")

  public class Result(
    /** The rewritten YAML. Equal to the input when nothing had to change. */
    public val yaml: String,
    /** Scenarios whose index in the `scenarios:` list changed. */
    public val movedScenarioIds: List<String>,
    /** Scenarios whose position comment was missing, outdated, or (when disabled) still present. */
    public val staleCommentScenarioIds: List<String>,
  )

  /**
   * Decodes [yamlText] with the project serializer (including validation, so a project with a
   * cyclic or dangling `dependency` is rejected with the usual report) and sorts it.
   * [positionComments] null means "as the project's `settings.positionComments` says".
   */
  public fun sort(yamlText: String, positionComments: Boolean? = null): Result {
    val content = ArbigentProjectSerializer().load(yamlText)
    return sort(yamlText, content, positionComments ?: content.settings.positionComments)
  }

  /**
   * Sorts [yamlText], whose decoded form is [content]. [content] must be the decode of exactly this
   * text: its scenarios are matched to the `- id:` blocks by position.
   */
  public fun sort(yamlText: String, content: ArbigentProjectFileContent, positionComments: Boolean): Result {
    val scenarios = content.scenarioContents
    if (scenarios.isEmpty()) return Result(yamlText, emptyList(), emptyList())

    val split = splitScenarioBlocks(yamlText)
    require(split.blocks.size == scenarios.size) {
      "Found ${split.blocks.size} scenario entries in the YAML text but the project decodes to " +
        "${scenarios.size} scenarios. Only block-style lists (`- id: ...`) are supported."
    }

    val ordered = ArbigentScenarioResolver.dependencyForestWithDepth(scenarios) { scenario ->
      scenario.dependencyId?.let { id -> scenarios.firstOrNull { it.id == id } }
    }
    val childrenOf = scenarios.groupBy { it.dependencyId }
    val indent = " ".repeat(split.itemIndent)

    val moved = mutableListOf<String>()
    val stale = mutableListOf<String>()
    val out = StringBuilder()
    split.header.forEach { out.appendLine(it) }
    ordered.forEachIndexed { newIndex, (scenario, depth) ->
      val oldIndex = scenarios.indexOf(scenario)
      val block = split.blocks[oldIndex]
      if (oldIndex != newIndex) moved += scenario.id

      val expected = if (positionComments) {
        listOf(indent + positionComment(scenario, depth, scenarios, childrenOf))
      } else {
        emptyList()
      }
      val (ownComments, otherComments) = block.leadingComments.partition { POSITION_COMMENT_MARKER.containsMatchIn(it) }
      if (ownComments != expected) stale += scenario.id

      otherComments.forEach { out.appendLine(it) }
      expected.forEach { out.appendLine(it) }
      block.body.forEach { out.appendLine(it) }
    }
    split.footer.forEach { out.appendLine(it) }
    // splitting on "\n" and re-appending lines adds exactly one newline at the end; drop it.
    out.setLength(out.length - 1)
    val yaml = out.toString()
    if (yaml != yamlText) requireSameProject(content, ordered.map { it.first }, yaml)
    return Result(yaml, moved, stale)
  }

  /**
   * The text split is line based and cannot see every YAML construct (a quoted scalar continuing
   * at column zero, a `|+` block scalar whose trailing blank lines sit at the end of the list, an
   * omitted `id` that decodes to a fresh random value each time). Rather than trust the split,
   * decode the rewritten text and refuse to return it unless it is the same project with the
   * scenarios in the new order.
   */
  private fun requireSameProject(
    content: ArbigentProjectFileContent,
    ordered: List<ArbigentScenarioContent>,
    rewritten: String,
  ) {
    val serializer = ArbigentProjectSerializer()
    val expected = serializer.encodeToString(
      ArbigentProjectFileContent(
        scenarioContents = ordered,
        reusableScenarios = content.reusableScenarios,
        fixedScenarios = content.fixedScenarios,
        settings = content.settings,
      )
    )
    val actual = try {
      serializer.encodeToString(serializer.load(rewritten))
    } catch (e: Exception) {
      throw IllegalArgumentException("Reordering the scenarios would produce a project file that no longer parses: ${e.message}", e)
    }
    require(actual == expected) {
      "Reordering the scenarios would change the project, so nothing was rewritten. This happens " +
        "with scenarios that have no explicit `id`, quoted values that continue at column zero, and " +
        "`|+` block scalars at the end of the list."
    }
  }

  private fun positionComment(
    scenario: ArbigentScenarioContent,
    depth: Int,
    scenarios: List<ArbigentScenarioContent>,
    childrenOf: Map<String?, List<ArbigentScenarioContent>>,
  ): String {
    // Root-first chain ending at this scenario. A cycle would loop forever; stop at the repeat.
    val chain = ArrayDeque<String>()
    val seen = mutableSetOf<String>()
    var current: ArbigentScenarioContent? = scenario
    while (current != null && seen.add(current.id)) {
      chain.addFirst(current.id)
      val dependencyId = current.dependencyId
      current = dependencyId?.let { id -> scenarios.firstOrNull { it.id == id } }
    }
    val children = childrenOf[scenario.id].orEmpty().map { it.id }
    return buildString {
      append("# [depth ").append(depth).append("] ").append(chain.joinToString(" > ") { it.singleLine() })
      if (children.isNotEmpty()) append(" | children: ").append(children.joinToString(", ") { it.singleLine() })
    }
  }

  /** A comment is one line; an id containing a line break would end it early. */
  private fun String.singleLine(): String = replace("\r", "\\r").replace("\n", "\\n")

  private class Block(val leadingComments: List<String>, val body: List<String>)

  private class Split(
    val header: List<String>,
    val itemIndent: Int,
    val blocks: List<Block>,
    val footer: List<String>,
  )

  private val SCENARIOS_KEY = Regex("""^scenarios:\s*(#.*)?$""")
  private val LIST_ITEM = Regex("""^( *)-( |$)""")
  private val BLANK_OR_COMMENT = Regex("""^\s*(#.*)?$""")

  /**
   * Cuts the `scenarios:` list into one block per item. A block is the item's lines plus the
   * comment lines directly above it (a comment run that touches the item; blank lines separate it
   * from the previous item and stay with that item). Comment lines indented deeper than the item
   * marker are content of the current item, e.g. a `#` line inside a block scalar goal.
   */
  private fun splitScenarioBlocks(yamlText: String): Split {
    val lines = yamlText.split("\n")
    val keyIndex = lines.indexOfFirst { SCENARIOS_KEY.matches(it) }
    require(keyIndex >= 0) { "No `scenarios:` block-style list found in the project file." }

    val header = lines.subList(0, keyIndex + 1).toMutableList()
    val blocks = mutableListOf<Block>()
    var footer: List<String> = emptyList()
    var itemIndent = -1
    var leading = mutableListOf<String>()
    var body = mutableListOf<String>()
    // Blank and comment lines not yet attributed to a block.
    val pending = mutableListOf<String>()

    fun closeBlock() {
      if (body.isNotEmpty()) blocks += Block(leading, body)
      leading = mutableListOf()
      body = mutableListOf()
    }

    /** Position comments left behind after the last item are rewritten with that item. */
    fun endList(rest: List<String>): List<String> {
      closeBlock()
      val (detached, footer) = pending.partition { POSITION_COMMENT_MARKER.containsMatchIn(it) }
      pending.clear()
      if (detached.isNotEmpty() && blocks.isNotEmpty()) {
        val last = blocks.removeAt(blocks.size - 1)
        blocks += Block(last.leadingComments + detached, last.body)
      }
      return footer + rest
    }

    /** Attach [pending] to the item being read: it is inside the item, not between items. */
    fun flushPendingIntoBody() {
      body += pending
      pending.clear()
    }

    var i = keyIndex + 1
    while (i < lines.size) {
      val line = lines[i]
      val lineIndent = line.length - line.trimStart(' ').length
      when {
        BLANK_OR_COMMENT.matches(line) -> {
          val isComment = line.trimStart().startsWith("#")
          if (isComment && itemIndent >= 0 && lineIndent > itemIndent && body.isNotEmpty()) {
            flushPendingIntoBody()
            body += line
          } else {
            pending += line
          }
        }

        itemIndent < 0 || (lineIndent == itemIndent && LIST_ITEM.containsMatchIn(line)) -> {
          if (itemIndent < 0) {
            require(LIST_ITEM.containsMatchIn(line)) {
              "Expected a `- id: ...` list item after `scenarios:` but found: $line"
            }
            itemIndent = lineIndent
          }
          closeBlock()
          // The comment run touching this item leads it; anything before the last blank line
          // belongs to whatever came before.
          // A position comment separated from its item by a blank line is still ours to rewrite.
          val lastBlank = pending.indexOfLast { it.isBlank() }
          val (detached, before) = pending.subList(0, lastBlank + 1).partition { POSITION_COMMENT_MARKER.containsMatchIn(it) }
          val touching = detached + pending.subList(lastBlank + 1, pending.size)
          pending.clear()
          if (blocks.isEmpty()) header += before else {
            val last = blocks.removeAt(blocks.size - 1)
            blocks += Block(last.leadingComments, last.body + before)
          }
          leading = touching.toMutableList()
          body = mutableListOf(line)
        }

        lineIndent > itemIndent -> {
          flushPendingIntoBody()
          body += line
        }

        else -> {
          // A key at or above the item level ends the scenarios list.
          footer = endList(lines.subList(i, lines.size))
          break
        }
      }
      i++
    }
    if (i >= lines.size) footer = endList(emptyList())
    return Split(header, itemIndent, blocks, footer)
  }
}
