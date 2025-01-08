package io.github.takahirom.arbigent

import maestro.Bounds
import maestro.DeviceInfo
import maestro.Filters
import maestro.FindElementResult
import maestro.KeyCode
import maestro.Maestro
import maestro.MaestroException
import maestro.TreeNode
import maestro.UiElement
import maestro.UiElement.Companion.toUiElement
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.ViewHierarchy
import maestro.filterOutOfBounds
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import java.io.File

@ArbigentInternalApi
public fun getIndexSelector(text: String): Pair<Int, String> {
  val matchResult = "(.*)\\[(\\d)\\]".toRegex().find(text)
  if (matchResult != null) {
    val (selector, index) = matchResult.destructured
    return index.toInt() to selector
  }
  return 0 to text
}

public interface ArbigentTvCompatDevice {
  public sealed interface Selector {
    public data class ByText(val text: String, val index: Int) : Selector {
      public companion object {
        public fun fromText(text: String): Selector {
          val (index, selector) = getIndexSelector(text)
          return ByText(selector, index)
        }
      }
    }

    public data class ById(val id: String, val index: Int) : Selector {
      public companion object {
        public fun fromId(id: String): Selector {
          val (index, selector) = getIndexSelector(id)
          return ById(selector, index)
        }
      }
    }
  }

  public fun moveFocusToElement(selector: Selector)
}

public interface ArbigentDevice {
  public fun executeCommands(commands: List<MaestroCommand>)
  public fun viewTreeString(): String
  public fun focusedTreeString(): String
  public fun close()
}

public class MaestroDevice(
  private val maestro: Maestro,
  screenshotsDir: File = ArbigentTempDir.screenshotsDir
) : ArbigentDevice, ArbigentTvCompatDevice {
  private val orchestra = Orchestra(
    maestro = maestro,
    screenshotsDir = screenshotsDir
  )

  override fun executeCommands(commands: List<MaestroCommand>) {
    // If the jsEngine is already initialized, we don't need to reinitialize it
    val shouldJsReinit = if (orchestra::class.java.getDeclaredField("jsEngine").apply {
        isAccessible = true
      }.get(orchestra) != null) {
      false
    } else {
      true
    }
    orchestra.executeCommands(commands, shouldReinitJsEngine = shouldJsReinit)
  }

  override fun viewTreeString(): String {
    return maestro.viewHierarchy(false).toOptimizedString(
      deviceInfo = maestro.cachedDeviceInfo
    )
  }

  override fun focusedTreeString(): String {
    return maestro.viewHierarchy(false)
      .getFocusedNode()
      ?.optimizedToString(0, enableDepth = false) ?: ""
  }

  private fun ViewHierarchy.getFocusedNode(): TreeNode? {
    fun dfs(node: TreeNode): TreeNode? {
      if (node.focused == true) {
        return node
      }
      for (child in node.children) {
        val result = dfs(child)
        if (result != null) {
          return result
        }
      }
      return null
    }
    return dfs(root)
  }

  public data class OptimizationResult(
    val node: TreeNode?,
    val promotedChildren: List<TreeNode>
  )

  public fun TreeNode.optimizeTree(
    isRoot: Boolean = false,
    meaningfulAttributes: Set<String> = setOf(
      "text",
      "accessibilityText",
      "content description",
      "hintText"
    ),
    viewHierarchy: ViewHierarchy
  ): OptimizationResult {
    // Optimize children
    val childResults = children
      .filter {
        it.attributes["resource-id"]?.contains("status_bar_container").let {
          if (it != null) !it
          else true
        } &&
          (it.toUiElementOrNull()?.bounds?.let {
            it.width > 0 && it.height > 0
          }) ?: true
      }
      .map { it.optimizeTree(false, meaningfulAttributes, viewHierarchy) }
    val optimizedChildren = childResults.flatMap {
      it.node?.let { node -> listOf(node) } ?: it.promotedChildren
    }
    val hasContent =
      children.isNotEmpty()
        || (attributes.keys.any { it in meaningfulAttributes })
    if (!hasContent) {
      arbigentDebugLog(
        "Node has no content: $this viewHierarchy.isVisible(this):${
          viewHierarchy.isVisible(
            this
          )
        }"
      )
    }
    val singleChild = optimizedChildren.singleOrNull()

    return when {
      (hasContent) || isRoot -> {
        // Keep the node with optimized children
        OptimizationResult(
          node = this.copy(children = optimizedChildren),
          promotedChildren = emptyList()
        )
      }

      optimizedChildren.isEmpty() -> {
        // Remove the node
        OptimizationResult(
          node = null,
          promotedChildren = emptyList()
        )
      }
      // If the node has only one child, promote it
      singleChild != null -> {
        OptimizationResult(
          node = singleChild,
          promotedChildren = emptyList()
        )
      }

      else -> {
        // Promote children
        OptimizationResult(
          node = null,
          promotedChildren = optimizedChildren
        )
      }
    }
  }

  public fun TreeNode.optimizedToString(depth: Int, enableDepth: Boolean = false): String {
    val blank = " ".repeat(depth)
    fun StringBuilder.appendString(str: String) {
      if (enableDepth) {
        append(blank)
        appendLine(str)
      } else {
        append(str)
      }
    }
    return buildString {
      val className = attributes["class"]?.substringAfterLast('.') ?: "Node"
      appendString("$className(")
//    appendString("attributes=$attributes, ")
      if (attributes.isNotEmpty()) {
//      appendString("attr={")
        attributes.forEach { (key, value) ->
          if (key == "class") {
            // Skip class name
          } else if (key == "resource-id" && value.isNotBlank()) {
            appendString("id=${value.substringAfterLast('/')}, ")
          } else if (key == "clickable") {
            // Skip clickable
          } else if (key == "enabled") {
            if (value == "false") {
              appendString("enabled=$value, ")
            }
          } else if (value.isNotBlank() && value != "null" && value != "false") {
            appendString("$key=$value, ")
          }
        }
//      appendString("}, ")
      }
      val clickable = clickable ?: attributes["clickable"]
      if (clickable != null) {
        appendString("clickable=$clickable, ")
      }
      if (enabled != null && !enabled!!) {
        appendString("enabled=$enabled, ")
      }
      if (focused != null && focused!!) {
        appendString("focused=$focused, ")
      }
      if (checked != null && checked!!) {
        appendString("checked=$checked, ")
      }
      if (selected != null && selected!!) {
        appendString("selected=$selected, ")
      }
      if (children.isNotEmpty()) {
        appendString("children(${children.size})=[")
        children.forEachIndexed { index, child ->
          append(child.optimizedToString(depth + 1))
          if (index < children.size - 1) {
            appendString(", ")
          }
        }
        appendString("], ")
      }
      appendString(")")
    }
  }

  public fun ViewHierarchy.toOptimizedString(
    meaningfulAttributes: Set<String> = setOf("text", "content description", "hintText", "focused"),
    deviceInfo: DeviceInfo
  ): String {
    val root = root
    val result = root.filterOutOfBounds(
      width = deviceInfo.widthPixels,
      height = deviceInfo.heightPixels
    )!!.optimizeTree(
      isRoot = true,
      viewHierarchy = this,
      meaningfulAttributes = meaningfulAttributes
    )
    val optimizedTree = result.node ?: result.promotedChildren.firstOrNull()
    arbigentDebugLog("Before optimization (length): ${this.toString().length}")
    val optimizedToString = optimizedTree?.optimizedToString(depth = 0)
    arbigentDebugLog("After optimization (length): ${optimizedToString?.length}")
    return optimizedToString ?: ""
  }


  override fun moveFocusToElement(selector: ArbigentTvCompatDevice.Selector) {
    val fetchTarget: () -> UiElement = {
      when (selector) {
        is ArbigentTvCompatDevice.Selector.ById -> {
          try {
            val element: FindElementResult = maestro.findElementWithTimeout(
              timeoutMs = 100,
              filter = Filters.compose(
                Filters.idMatches(selector.id.toRegex()),
                Filters.index(selector.index)
              ),
            ) ?: throw MaestroException.ElementNotFound(
              "Element not found",
              maestro.viewHierarchy().root
            )
            val uiElement: UiElement = element.element
            uiElement
          } catch (e: MaestroException.ElementNotFound) {
            val element: FindElementResult = maestro.findElementWithTimeout(
              timeoutMs = 100,
              filter = Filters.compose(
                Filters.idMatches((".*" + selector.id + ".*").toRegex()),
                Filters.index(selector.index)
              )
            ) ?: throw MaestroException.ElementNotFound(
              "Element not found",
              maestro.viewHierarchy().root
            )
            val uiElement: UiElement = element.element
            uiElement
          }
        }

        is ArbigentTvCompatDevice.Selector.ByText -> {
          try {
            val element: FindElementResult = maestro.findElementWithTimeout(
              timeoutMs = 100,
              filter = Filters.compose(
                Filters.textMatches(selector.text.toRegex()),
                Filters.index(selector.index)
              ),
            ) ?: throw MaestroException.ElementNotFound(
              "Element not found",
              maestro.viewHierarchy().root
            )
            val uiElement: UiElement = element.element
            uiElement
          } catch (e: MaestroException.ElementNotFound) {
            val element: FindElementResult = maestro.findElementWithTimeout(
              timeoutMs = 100,
              filter = Filters.compose(
                Filters.textMatches((".*" + selector.text + ".*").toRegex()),
                Filters.index(selector.index)
              )
            ) ?: throw MaestroException.ElementNotFound(
              "Element not found",
              maestro.viewHierarchy().root
            )
            val uiElement: UiElement = element.element
            uiElement
          }
        }
      }
    }
    var remainCount = 10
    while (remainCount-- > 0) {
      val currentFocus = findCurrentFocus()
      val currentBounds = currentFocus.toUiElement().bounds
      val targetBounds = fetchTarget().bounds

      // Helper functions to calculate the center X and Y of a Bounds object.
      fun Bounds.centerY(): Int {
        return center().y
      }
      fun Bounds.centerX(): Int {
        return center().x
      }

      fun Bounds.right(): Int {
        return x + width
      }

      fun Bounds.bottom(): Int {
        return y + height
      }

      // Function to check if two ranges overlap
      fun isOverlapping(start1: Int, end1: Int, start2: Int, end2: Int): Boolean {
        return maxOf(start1, start2) <= minOf(end1, end2)
      }

      arbigentDebugLog("currentBounds: $currentBounds")
      arbigentDebugLog("targetBounds: $targetBounds")

      val directionCandidates = mutableListOf<KeyCode>()

      // Check if X ranges overlap, prioritize vertical movement
      if (isOverlapping(currentBounds.x, currentBounds.right(), targetBounds.x, targetBounds.right())) {
        arbigentDebugLog("X Overlap detected")
        if (currentBounds.centerY() > targetBounds.centerY()) {
          directionCandidates.add(KeyCode.REMOTE_UP)
        } else if (currentBounds.centerY() < targetBounds.centerY()) {
          directionCandidates.add(KeyCode.REMOTE_DOWN)
        }
      }

      // Check if Y ranges overlap, prioritize horizontal movement
      if (isOverlapping(currentBounds.y, currentBounds.bottom(), targetBounds.y, targetBounds.bottom())) {
        arbigentDebugLog("Y Overlap detected")
        if (currentBounds.centerX() > targetBounds.centerX()) {
          directionCandidates.add(KeyCode.REMOTE_LEFT)
        } else if (currentBounds.centerX() < targetBounds.centerX()) {
          directionCandidates.add(KeyCode.REMOTE_RIGHT)
        }
      }

      // If no overlap in X or Y, use existing logic to determine the direction
      if (directionCandidates.isEmpty()) {
        when {
          currentBounds.centerY() > targetBounds.centerY() && currentBounds.centerX() > targetBounds.centerX() -> {
            directionCandidates.add(KeyCode.REMOTE_UP)
            directionCandidates.add(KeyCode.REMOTE_LEFT)
          }

          currentBounds.centerY() > targetBounds.centerY() && currentBounds.centerX() < targetBounds.centerX() -> {
            directionCandidates.add(KeyCode.REMOTE_UP)
            directionCandidates.add(KeyCode.REMOTE_RIGHT)
          }

          currentBounds.centerY() < targetBounds.centerY() && currentBounds.centerX() > targetBounds.centerX() -> {
            directionCandidates.add(KeyCode.REMOTE_DOWN)
            directionCandidates.add(KeyCode.REMOTE_LEFT)
          }

          currentBounds.centerY() < targetBounds.centerY() && currentBounds.centerX() < targetBounds.centerX() -> {
            directionCandidates.add(KeyCode.REMOTE_DOWN)
            directionCandidates.add(KeyCode.REMOTE_RIGHT)
          }

          currentBounds.centerY() > targetBounds.centerY() -> directionCandidates.add(KeyCode.REMOTE_UP)
          currentBounds.centerY() < targetBounds.centerY() -> directionCandidates.add(KeyCode.REMOTE_DOWN)
          currentBounds.centerX() > targetBounds.centerX() -> directionCandidates.add(KeyCode.REMOTE_LEFT)
          currentBounds.centerX() < targetBounds.centerX() -> directionCandidates.add(KeyCode.REMOTE_RIGHT)
          else -> {} // No possible direction
        }
      }

      if (directionCandidates.isEmpty()) {
        arbigentDebugLog("No direction candidates found. Breaking loop.")
        break
      }

      val direction = directionCandidates.random()
      arbigentDebugLog("directionCandidates: $directionCandidates \ndirection: $direction")
      maestro.pressKey(direction)
      maestro.waitForAnimationToEnd(100)
    }
  }

  private fun findCurrentFocus(): TreeNode {
    return maestro.viewHierarchy(false)
      .getFocusedNode()
      ?: throw IllegalStateException("No focused node")
  }

  override fun close() {
    maestro.close()
  }
}

