package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.MaestroDevice.OptimizationResult
import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import maestro.*
import maestro.UiElement.Companion.toUiElement
import maestro.UiElement.Companion.toUiElementOrNull
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
  public fun moveFocusToElement(element: ArbigentElement)
}


private val meaningfulAttributes: Set<String> = setOf(
  "text",
  "title",
  "accessibilityText",
  "content description",
  "hintText"
)

public interface ArbigentDevice {
  public fun deviceName(): String = "ArbigentDevice"
  public fun executeCommands(commands: List<MaestroCommand>)
  public fun viewTreeString(): ArbigentUiTreeStrings
  public fun focusedTreeString(): String
  public fun close()
  public fun elements(): ArbigentElementList
  public fun waitForAppToSettle(appId: String? = null)
  public fun os(): ArbigentDeviceOs
}

public data class ArbigentElement(
  val index: Int,
  val textForAI: String,
  val rawText: String,
  val treeNode: TreeNode,
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
  val isVisible: Boolean,
) {
  public class Rect(
    public val left: Int,
    public val top: Int,
    public val right: Int,
    public val bottom: Int
  ) {
    public fun width(): Int = right - left
    public fun height(): Int = bottom - top
    public fun centerX(): Int = (left + right) / 2
    public fun centerY(): Int = (top + bottom) / 2
  }

  val rect: Rect
    get() = Rect(x, y, x + width, y + height)
}

public data class ArbigentElementList(
  val elements: List<ArbigentElement>,
  val screenWidth: Int
) {

  public fun getAiTexts(): String {
    return elements.joinToString("\n") { "" + it.index + ":" + it.textForAI }
  }

  public class NodeInBoundsNotFoundException : Exception()

  public companion object {
    public fun from(
      viewHierarchy: ViewHierarchy,
      deviceInfo: DeviceInfo
    ): ArbigentElementList {
      val clickableAvailable = deviceInfo.platform == Platform.ANDROID
      var index = 0
      val deviceInfo = deviceInfo
      val root = viewHierarchy.root
      val treeNode = root.filterOutOfBounds(
        width = deviceInfo.widthPixels,
        height = deviceInfo.heightPixels
      ) ?: throw NodeInBoundsNotFoundException()
      val result = treeNode.optimizeTree(
        isRoot = true,
        viewHierarchy = viewHierarchy,
      )
      val optimizedTree = result.node ?: result.promotedChildren.firstOrNull()
      val elements = mutableListOf<ArbigentElement>()
      fun TreeNode.toElement(): ArbigentElement {
        val bounds = toUiElementOrNull()?.bounds
        return ArbigentElement(
          index = index++,
          textForAI = buildString {
            val className = attributes["class"]?.substringAfterLast('.') ?: "Node"
            append(className)
            append("(")
            appendUiElementContents(this@toElement, true)
            append(")")
          },
          rawText = attributes.toString(),
          treeNode = this,
          x = bounds?.x ?: 0,
          y = bounds?.y ?: 0,
          width = bounds?.width ?: 0,
          height = bounds?.height ?: 0,
          isVisible = bounds?.let {
            it.width > 0 && it.height > 0
          } ?: false
        )
      }

      fun TreeNode.toElementList(): List<ArbigentElement> {
        val elements = mutableListOf<ArbigentElement>()
        val shouldAddElement = if (clickableAvailable) {
          (clickable == true || focused == true
            || attributes["clickable"] == "true"
            || attributes["focused"] == "true"
            || attributes["focusable"] == "true")
        } else {
          meaningfulAttributes.any { attributes[it]?.isNotBlank() == true }
        }
        if (shouldAddElement
        ) {
          elements.add(toElement())
        }
        children
          .forEach {
            elements.addAll(it.toElementList())
          }
        return elements
      }
      elements.addAll(optimizedTree?.toElementList() ?: emptyList())

      arbigentDebugLog("ArbigentDevice: widthGrid:${deviceInfo.widthGrid} widthPixels:${deviceInfo.widthPixels}")

      return ArbigentElementList(
        elements = elements,
        screenWidth = (deviceInfo.widthGrid)
      )
    }
  }
}

public class MaestroDevice(
  private val maestro: Maestro,
  screenshotsDir: File = ArbigentDir.screenshotsDir
) : ArbigentDevice, ArbigentTvCompatDevice {
  private val orchestra = Orchestra(
    maestro = maestro,
    screenshotsDir = screenshotsDir
  )

  override fun deviceName(): String {
    return maestro.deviceName
  }

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

  public override fun waitForAppToSettle(appId: String?) {
    maestro.waitForAppToSettle(appId = appId)
  }

  override fun elements(): ArbigentElementList {
    for (it in 0..2) {
      try {
        val viewHierarchy = maestro.viewHierarchy(false)
        val deviceInfo = maestro.cachedDeviceInfo
        val elementList = ArbigentElementList.from(viewHierarchy, deviceInfo)
        return elementList
      } catch (e: ArbigentElementList.NodeInBoundsNotFoundException) {
        arbigentDebugLog("NodeInBoundsNotFoundException. Retry $it")
        Thread.sleep(1000)
      }
    }
    return ArbigentElementList(emptyList(), maestro.cachedDeviceInfo.widthPixels)
  }


  override fun viewTreeString(): ArbigentUiTreeStrings {
    for (it in 0..2) {
      try {
        val viewHierarchy = maestro.viewHierarchy(false)
        return ArbigentUiTreeStrings(
          allTreeString = viewHierarchy.toString(),
          optimizedTreeString = viewHierarchy.toOptimizedString(
            deviceInfo = maestro.cachedDeviceInfo
          )
        )
      } catch (e: ArbigentElementList.NodeInBoundsNotFoundException) {
        arbigentDebugLog("NodeInBoundsNotFoundException. Retry $it")
        Thread.sleep(1000)
      }
    }
    return ArbigentUiTreeStrings(
      allTreeString = "",
      optimizedTreeString = ""
    )
  }

  override fun focusedTreeString(): String {
    return findCurrentFocus()
      ?.optimizedToString(0, enableDepth = false) ?: ""
  }

  public data class OptimizationResult(
    val node: TreeNode?,
    val promotedChildren: List<TreeNode>
  )


  private fun TreeNode.optimizedToString(depth: Int, enableDepth: Boolean = false): String {
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
      this.appendUiElementContents(this@optimizedToString)
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

  override fun os(): ArbigentDeviceOs {
    return when (maestro.cachedDeviceInfo.platform) {
      Platform.ANDROID -> ArbigentDeviceOs.Android
      Platform.IOS -> ArbigentDeviceOs.Ios
      else -> ArbigentDeviceOs.Web
    }
  }

  private fun ViewHierarchy.toOptimizedString(deviceInfo: DeviceInfo): String {
    val root = root
    val nodes = root.filterOutOfBounds(
      width = deviceInfo.widthPixels,
      height = deviceInfo.heightPixels
    ) ?: throw ArbigentElementList.NodeInBoundsNotFoundException()
    val result = nodes.optimizeTree(
      isRoot = true,
      viewHierarchy = this,
    )
    val optimizedTree = result.node ?: result.promotedChildren.firstOrNull()
    val optimizedToString = optimizedTree?.optimizedToString(depth = 0)
    return optimizedToString ?: ""
  }

  override fun moveFocusToElement(
    selector: ArbigentTvCompatDevice.Selector,
  ) {
    moveFocusToElement(
      fetchTarget = { fetchTargetBounds(selector) }
    )
  }

  override fun moveFocusToElement(element: ArbigentElement) {
    moveFocusToElement(
      fetchTarget = {
        val newElement = maestro.viewHierarchy().refreshElement(element.treeNode)
        newElement?.toUiElement()?.bounds ?: element.treeNode.toUiElement().bounds
      }
    )
  }


  private fun moveFocusToElement(
    fetchTarget: () -> Bounds
  ) {
    var remainCount = 15
    var lastBounds: Bounds? = null
    while (remainCount-- > 0) {
      val currentFocus = findCurrentFocus()
        ?: throw IllegalStateException("No focused node")
      val currentBounds = currentFocus.toUiElement().bounds
      if (lastBounds == currentBounds) {
        arbigentDebugLog("Same bounds detected. Might be stuck or scrollable view. Breaking loop.")
        break
      }
      lastBounds = currentBounds
      val targetBounds = fetchTarget()

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

      if (isOverlapping(
          currentBounds.x,
          currentBounds.right(),
          targetBounds.x,
          targetBounds.right()
        ) && isOverlapping(
          currentBounds.y,
          currentBounds.bottom(),
          targetBounds.y,
          targetBounds.bottom()
        )
      ) {
        arbigentDebugLog("Overlap detected. Breaking loop.")
        break
      }

      // Check if X ranges overlap, prioritize vertical movement
      if (isOverlapping(
          currentBounds.x,
          currentBounds.right(),
          targetBounds.x,
          targetBounds.right()
        )
      ) {
        arbigentDebugLog("X Overlap detected")
        if (currentBounds.centerY() > targetBounds.centerY()) {
          directionCandidates.add(KeyCode.REMOTE_UP)
        } else if (currentBounds.centerY() < targetBounds.centerY()) {
          directionCandidates.add(KeyCode.REMOTE_DOWN)
        }
      }

      // Check if Y ranges overlap, prioritize horizontal movement
      if (isOverlapping(
          currentBounds.y,
          currentBounds.bottom(),
          targetBounds.y,
          targetBounds.bottom()
        )
      ) {
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

  private fun fetchTargetBounds(selector: ArbigentTvCompatDevice.Selector): Bounds {
    return when (selector) {
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
    }.bounds
  }

  private fun findCurrentFocus(): TreeNode? {
    val viewHierarchy = maestro.viewHierarchy(false)
    return dfs(viewHierarchy.root) {
      // If keyboard is focused, return the focused node with keyboard
      it.attributes["resource-id"]?.startsWith("com.google.android.inputmethod.latin:id/") == true && it.focused == true
    } ?: dfs(viewHierarchy.root) {
      // If no keyboard is focused, return the focused node
      it.focused == true
    }
  }

  override fun close() {
    maestro.close()
  }
}

private fun dfs(node: TreeNode, condition: (TreeNode) -> Boolean): TreeNode? {
  if (condition(node)) {
    return node
  }
  for (child in node.children) {
    val result = dfs(child, condition)
    if (result != null) {
      return result
    }
  }
  return null
}

private fun StringBuilder.appendUiElementContents(
  treeNode: TreeNode,
  fetchChildrenAttributes: Boolean = false
) {
  if (treeNode.attributes.isNotEmpty()) {
    //      appendString("attr={")
    if (fetchChildrenAttributes) {
      meaningfulAttributes.forEach { attribute ->
        dfs(treeNode) {
          it.attributes[attribute]?.isNotBlank() ?: false
        }?.let {
          append("$attribute=${it.attributes[attribute]}, ")
        }
      }
    } else {
      meaningfulAttributes.forEach { attribute ->
        if (treeNode.attributes[attribute]?.isNotBlank() == true) {
          append("$attribute=${treeNode.attributes[attribute]}, ")
        }
      }
    }
    treeNode.attributes.forEach { (key, value) ->
      if (meaningfulAttributes.contains(key)) {
        return@forEach
      }
      if (key == "class") {
        // Skip class name
      } else if (key == "resource-id" && value.isNotBlank()) {
        append("id=${value.substringAfterLast('/')}, ")
      } else if (key == "clickable") {
        // Skip clickable
      } else if (key == "enabled") {
        if (value == "false") {
          append("enabled=$value, ")
        }
      } else if (value.isNotBlank() && value != "null" && value != "false") {
        append("$key=$value, ")
      }
    }
    //      append("}, ")
  }
  val clickable = treeNode.clickable ?: treeNode.attributes["clickable"]
  if (clickable != null) {
    append("clickable=$clickable, ")
  }
  if (treeNode.enabled != null && !treeNode.enabled!!) {
    append("enabled=${treeNode.enabled}, ")
  }
  if (treeNode.focused != null && treeNode.focused!!) {
    append("focused=${treeNode.focused}, ")
  }
  if (treeNode.checked != null && treeNode.checked!!) {
    append("checked=${treeNode.checked}, ")
  }
  if (treeNode.selected != null && treeNode.selected!!) {
    append("selected=${treeNode.selected}, ")
  }
}


public fun TreeNode.optimizeTree(
  isRoot: Boolean = false,
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
    .map { it.optimizeTree(false, viewHierarchy) }
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
