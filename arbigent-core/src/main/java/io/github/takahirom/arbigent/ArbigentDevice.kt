package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.MaestroDevice.OptimizationResult
import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import kotlinx.coroutines.runBlocking
import maestro.*
import maestro.UiElement.Companion.toUiElement
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.device.Platform
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import okio.sink
import java.io.File
import kotlin.math.pow
import kotlin.system.measureTimeMillis

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


public interface ArbigentDevice {
  public fun deviceName(): String = "ArbigentDevice"
  public fun executeActions(actions: List<MaestroCommand>)
  public fun viewTreeString(): ArbigentUiTreeStrings
  public fun focusedTreeString(): String
  public fun close()
  public fun isClosed(): Boolean
  public fun elements(): ArbigentElementList
  public fun waitForAppToSettle(appId: String? = null)
  public fun os(): ArbigentDeviceOs
}

public data class ArbigentElement(
  val index: Int,
  val textForAI: String,
  val rawText: String,
  val identifierData: IdentifierData,
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

  public data class IdentifierData(
    val identifier: List<Any>,
    val index: Int
  )

  val rect: Rect
    get() = Rect(x, y, x + width, y + height)
}

public data class ArbigentElementList(
  val elements: List<ArbigentElement>,
  val screenWidth: Int
) {

  public fun getPromptTexts(): String {
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
      val result = treeNode.optimizeTree2(
        isRoot = true,
        viewHierarchy = viewHierarchy,
      )
      val optimizedTree = (result.node ?: result.promotedChildren.firstOrNull())!!
      val elements = mutableListOf<ArbigentElement>()
      fun TreeNode.toElement(): ArbigentElement {
        val bounds = toUiElementOrNull()?.bounds
        val identifierData = this.getIdentifierDataForFocus()
        val saveIdentifierIndex = optimizedTree.aggregate()
          .map {
            (it.toUiElementOrNull()?.bounds == bounds) to (it.getIdentifierDataForFocus() == identifierData)
          }
          .filter {
            it.second
          }
          .indexOfFirst { it.first }
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
          identifierData = ArbigentElement.IdentifierData(
            identifier = identifierData,
            index = saveIdentifierIndex
          ),
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
            || attributes["focusable"] == "true"
            || checked == true
            || attributes["checked"] == "true"
            || selected == true
            || attributes["selected"] == "true"
          )
        } else {
          isMeaningfulView()
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

      arbigentDebugLog("Element loaded size:${elements.size} ArbigentDevice: widthGrid:${deviceInfo.widthGrid} widthPixels:${deviceInfo.widthPixels}")

      return ArbigentElementList(
        elements = elements,
        screenWidth = (deviceInfo.widthGrid)
      )
    }
  }
}

public class MaestroDevice(
  maestro: Maestro,
  private val screenshotsDir: File = ArbigentFiles.screenshotsDir,
  private val availableDevice: ArbigentAvailableDevice? = null,
  // Extra teardown tied to this connection (e.g. the iproxy port forwarder for a physical
  // iPhone), run after maestro.close() so session-scoped child processes stop with the device.
  onClose: (() -> Unit)? = null,
) : ArbigentDevice, ArbigentTvCompatDevice {
  // maestro, its Orchestra and the connection-scoped teardown are swapped together as one
  // Connection, so a reconnect can never pair the new maestro with the old cleanup (which would
  // leak the replacement device's iproxy) or keep invoking the stale one on close().
  //
  // Maestro's Orchestra artifact refactor (#3282) removed the screenshotsDir constructor
  // parameter; takeScreenshot commands now write under an artifacts dir instead of
  // screenshotsDir/<path>.png. Arbigent captures screenshots itself in executeActions()
  // (see below) to keep that path contract, so Orchestra needs no screenshots dir here.
  private class Connection(
    val maestro: Maestro,
    val orchestra: Orchestra,
    val onClose: (() -> Unit)?,
  )

  @Volatile private var connection: Connection =
    Connection(maestro, Orchestra(maestro = maestro), onClose)

  // Volatile read of the live connection so callers keep using `maestro`/`orchestra` unchanged.
  private val maestro: Maestro get() = connection.maestro
  private val orchestra: Orchestra get() = connection.orchestra

  init {
    arbigentInfoLog("MaestroDevice created: screenshotsDir:${screenshotsDir.absolutePath}")
  }

  override fun deviceName(): String {
    return maestro.deviceName
  }

  @Synchronized
  private fun ensureConnected() {
    // Try a simple operation to check connection
    try {
      runBlocking { maestro.viewHierarchy() }
    } catch (e: Exception) {
      // Device appears disconnected, reconnect
      arbigentInfoLog("MaestroDevice failed to fetch view hierarchy: ${e.message}. Reconnect device ${maestro.deviceName}")
      reconnectIfDisconnected()
    }
  }

  override fun executeActions(actions: List<MaestroCommand>) {
    ensureConnected()
    // A lone takeScreenshot (how arbigent always issues one, see ArbigentAgent) is captured
    // directly to screenshotsDir/<path>.png. Maestro's Orchestra artifact refactor (#3282)
    // moved takeScreenshot output under an artifacts dir, which no longer matches where
    // arbigent reads the file, so screenshots bypass Orchestra to keep the path contract.
    // Every other command still runs through Orchestra via runFlow (which manages the JS
    // engine lifecycle internally, replacing the old shouldReinitJsEngine reflection).
    val screenshot = actions.singleOrNull()?.takeScreenshotCommand
    ArbigentGlobalStatus.onDevice(actions.joinToString { it.toString() }) {
      runBlocking {
        if (screenshot != null) {
          val file = resolveScreenshotFile(screenshotsDir, screenshot.path)
          maestro.takeScreenshot(file.sink(), false)
        } else {
          orchestra.runFlow(actions)
        }
      }
    }
  }

  public override fun waitForAppToSettle(appId: String?) {
    ensureConnected()
    runBlocking { maestro.waitForAppToSettle(appId = appId) }
  }

  override fun elements(): ArbigentElementList {
    ensureConnected()
    for (it in 0..2) {
      try {
        val viewHierarchy = runBlocking { maestro.viewHierarchy(false) }
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
    ensureConnected()
    for (it in 0..2) {
      try {
        val viewHierarchy = runBlocking { maestro.viewHierarchy(false) }
        return ArbigentUiTreeStrings(
          allTreeString = viewHierarchy.toString(),
          optimizedTreeString = viewHierarchy.toOptimizedString(
            deviceInfo = maestro.cachedDeviceInfo
          ),
          aiHints = viewHierarchy.root.findAllAiHints()
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
    ensureConnected()
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
    val result = nodes.optimizeTree2(
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
    ensureConnected()
    moveFocusToElement(
      fetchTarget = { fetchTargetBounds(selector) }
    )
  }

  override fun moveFocusToElement(element: ArbigentElement) {
    ensureConnected()
    moveFocusToElement(
      fetchTarget = {
        val newElement = runBlocking { maestro.viewHierarchy() }.refreshedElement(element.identifierData)
        val bounds = newElement?.toUiElement()?.bounds
        if (bounds == null) {
          arbigentInfoLog("Element(${element.treeNode.getIdentifierDataForFocus()}) not found in current ViewHierarchy.")
        }
        bounds
      }
    )
  }


  private fun ViewHierarchy.refreshedElement(identifierData: ArbigentElement.IdentifierData): TreeNode? {
    val targetNodeIdentifierData = identifierData.identifier
    arbigentDebugLog("targetNode: $targetNodeIdentifierData index:${identifierData.index}")
    val identifierCounter = mutableMapOf<String, Int>()
    val matches = root.optimizeTree2(isRoot = true, viewHierarchy = this).node!!
      .aggregate()
      .filter {
        val identifierDataForFocus = it.getIdentifierDataForFocus()
        val index = identifierCounter.getOrPut(identifierDataForFocus.toString()) {
          0
        }
        arbigentDebugLog("candidateNode: $identifierDataForFocus index:$index")
        val isMatchIdentifier = identifierDataForFocus == targetNodeIdentifierData
        val isMatchIndex = (index) == identifierData.index
        identifierCounter[identifierDataForFocus.toString()] = index + 1
        isMatchIdentifier && isMatchIndex
      }

    if (matches.size != 1) {
      return null
    }

    return matches[0]
  }

  private fun moveFocusToElement(
    fetchTarget: () -> Bounds?
  ) {
    var remainCount = 15
    while (remainCount-- > 0) {
      val currentFocus = findCurrentFocus()
        ?: throw IllegalStateException("No focused node")
      val targetBounds =
        fetchTarget() ?: throw IllegalStateException("Attempted to move to element but missed the target bounds")
      val currentBounds = currentFocus.toUiElement().bounds

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
        return maxOf(start1, start2) < minOf(end1, end2)
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
      runBlocking {
        maestro.pressKey(direction)
        maestro.waitForAnimationToEnd("100")
      }
    }
  }

  private fun fetchTargetBounds(selector: ArbigentTvCompatDevice.Selector): Bounds {
    return when (selector) {
      is ArbigentTvCompatDevice.Selector.ById -> {
        try {
          val element: FindElementResult = runBlocking { maestro.findElementWithTimeout(
            timeoutMs = 100,
            filter = Filters.compose(
              Filters.idMatches(selector.id.toRegex()),
              Filters.index(selector.index)
            ),
          ) } ?: throw MaestroException.ElementNotFound(
            "Element not found",
            runBlocking { maestro.viewHierarchy() }.root,
            "Element not found",
          )
          val uiElement: UiElement = element.element
          uiElement
        } catch (e: MaestroException.ElementNotFound) {
          val element: FindElementResult = runBlocking { maestro.findElementWithTimeout(
            timeoutMs = 100,
            filter = Filters.compose(
              Filters.idMatches((".*" + selector.id + ".*").toRegex()),
              Filters.index(selector.index)
            )
          ) } ?: throw MaestroException.ElementNotFound(
            "Element not found",
            runBlocking { maestro.viewHierarchy() }.root,
            "Element not found",
          )
          val uiElement: UiElement = element.element
          uiElement
        }
      }

      is ArbigentTvCompatDevice.Selector.ByText -> {
        try {
          val element: FindElementResult = runBlocking { maestro.findElementWithTimeout(
            timeoutMs = 100,
            filter = Filters.compose(
              Filters.textMatches(selector.text.toRegex()),
              Filters.index(selector.index)
            ),
          ) } ?: throw MaestroException.ElementNotFound(
            "Element not found",
            runBlocking { maestro.viewHierarchy() }.root,
            "Element not found",
          )
          val uiElement: UiElement = element.element
          uiElement
        } catch (e: MaestroException.ElementNotFound) {
          val element: FindElementResult = runBlocking { maestro.findElementWithTimeout(
            timeoutMs = 100,
            filter = Filters.compose(
              Filters.textMatches((".*" + selector.text + ".*").toRegex()),
              Filters.index(selector.index)
            )
          ) } ?: throw MaestroException.ElementNotFound(
            "Element not found",
            runBlocking { maestro.viewHierarchy() }.root,
            "Element not found",
          )
          val uiElement: UiElement = element.element
          uiElement
        }
      }
    }.bounds
  }

  private fun findCurrentFocus(): TreeNode? {
    val viewHierarchy = runBlocking { maestro.viewHierarchy(false) }
    return dfs(viewHierarchy.root) {
      // If keyboard is focused, return the focused node with keyboard
      it.attributes["resource-id"]?.startsWith("com.google.android.inputmethod.latin:id/") == true && it.focused == true
    } ?: dfs(viewHierarchy.root) {
      // If no keyboard is focused, return the focused node
      it.focused == true
    }
  }

  @Volatile private var isClosed = false
  private var reconnectAttempts = 0
  private val maxReconnectAttempts = 6  // Allows retries with exponential backoff up to ~64 seconds
  // Guards the connection swap in reconnect against close(): whoever holds it either replaces the
  // connection or tears it down, never both at once, so close() can't capture the old connection
  // while reconnect installs a new one (which would leak the replacement's iproxy forwarder).
  private val reconnectLock = Any()

  // Close a connection exactly once: shut down maestro, then always run its onClose (which stops
  // and de-registers the iproxy forwarder) even if maestro.close() throws, so the forwarder and
  // its port are released rather than leaked.
  private fun closeConnection(target: Connection) {
    try {
      target.maestro.close()
    } catch (_: Exception) {
      // Ignore close errors, device might already be disconnected
    }
    runCatching { target.onClose?.invoke() }
  }

  private fun reconnectIfDisconnected() {
    synchronized(reconnectLock) {
      // Only reconnect if we have the available device reference
      if (availableDevice == null) {
        throw IllegalStateException("Cannot reconnect: no available device reference")
      }
      if (isClosed) {
        throw IllegalStateException("Cannot reconnect: device is closed")
      }

      // Tear down the current (broken) connection up front — closing its maestro and running its
      // onClose, which stops the iproxy forwarder and frees the port — BEFORE building the
      // replacement. Building first would start a second forwarder on the same port, and the
      // ownership check would reject it because this very JVM still owns the port, so reconnect
      // could never succeed. Readers are serialized behind ensureConnected()'s monitor, so no one
      // observes the closed connection during the swap; a transient failure that retries is fine.
      closeConnection(this.connection)

      var lastException: Exception? = null

      // Retry with exponential backoff
      while (reconnectAttempts < maxReconnectAttempts) {
        // Exponential backoff: 2^attempt seconds (2s, 4s, 8s, 16s, 32s, 64s...)
        // For attempt 0: no wait
        // For attempt 1: 2s
        // For attempt 2: 4s
        // For attempt 3: 8s
        // For attempt 4: 16s
        // For attempt 5: 32s
        // For attempt 6: 64s (just over 1 minute)
        if (reconnectAttempts > 0) {
          val waitTimeMs = (2.0.pow(reconnectAttempts) * 1000).toLong()
          val maxWaitTimeMs = 60000L // Cap at 60 seconds
          val actualWaitTimeMs = minOf(waitTimeMs, maxWaitTimeMs)
          arbigentInfoLog("Waiting ${actualWaitTimeMs}ms before reconnection attempt ${reconnectAttempts + 1}")
          Thread.sleep(actualWaitTimeMs)
        }

        reconnectAttempts++

        // Try to reconnect
        val newDevice = try {
          availableDevice.connectToDevice()
        } catch (e: Exception) {
          lastException = e
          arbigentInfoLog("Reconnection attempt $reconnectAttempts/$maxReconnectAttempts failed: ${e.message}")
          continue // Try again
        }

        if (newDevice !is MaestroDevice) {
          newDevice.close()
          lastException = IllegalStateException("Unexpected device type after reconnection: ${newDevice.javaClass}")
          continue // Try again
        }

        // Adopt the new device's whole Connection (maestro + orchestra + its onClose, which owns the
        // new iproxy) in one volatile swap. The old connection was already torn down above.
        this.connection = newDevice.connection

        // Reset counter on successful reconnection
        reconnectAttempts = 0
        return // Success!
      }

      // All attempts failed - reset counter for future retry sequences
      reconnectAttempts = 0
      throw RuntimeException("Failed to reconnect after $maxReconnectAttempts attempts", lastException)
    }
  }

  override fun close() {
    // Same lock as reconnect so a close racing an in-progress reconnect tears down whichever
    // connection reconnect ultimately installs, instead of capturing the old one and leaking the
    // replacement's forwarder.
    synchronized(reconnectLock) {
      isClosed = true
      closeConnection(connection)
    }
  }

  override fun isClosed(): Boolean {
    return isClosed
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

private const val AI_HINT_PREFIX = "[[aihint:"
private const val AI_HINT_SUFFIX = "]]"

/**
 * Collects all AI hints from the tree.
 * Apps can embed hints in contentDescription using the [[aihint:...]] format to provide
 * domain-specific context information to Arbigent.
 *
 * Example: view.contentDescription = "Play button [[aihint:Video player, currently buffering]]"
 *
 * The hint can be placed anywhere in the contentDescription, allowing coexistence with
 * accessibility labels. Multiple hints can be set on different views and all will be collected.
 *
 * Hints can contain structured data like JSON:
 * Example: view.contentDescription = "[[aihint:{\"screen\":\"player\",\"state\":\"buffering\"}]]"
 */
public fun TreeNode.findAllAiHints(): List<String> {
  val hints = mutableListOf<String>()

  attributes["accessibilityText"]?.let { text ->
    val startIndex = text.indexOf(AI_HINT_PREFIX)
    if (startIndex >= 0) {
      // Find the closing ]] after the opening [[aihint:
      val endIndex = text.indexOf(AI_HINT_SUFFIX, startIndex + AI_HINT_PREFIX.length)
      if (endIndex > startIndex) {
        val hint = text.substring(startIndex + AI_HINT_PREFIX.length, endIndex)
        hints.add(hint)
      }
    }
  }

  children.forEach { hints.addAll(it.findAllAiHints()) }
  return hints
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

private val meaningfulAttributes: Set<String> = setOf(
  "text",
  "title",
  "accessibilityText",
  "content description",
  "hintText"
)

private val meaningfulIfTrueAttributes: Set<String> = setOf(
  "checked",
  "clickable",
  "focusable",
  "selectable",
)

private fun TreeNode.isMeaningfulView(): Boolean {
  return meaningfulAttributes.any { attributes[it]?.isNotBlank() == true }
    || meaningfulIfTrueAttributes.any { attributes[it] == "true" }
}

public fun TreeNode.optimizeTree2(
  isRoot: Boolean = false,
  viewHierarchy: ViewHierarchy
): OptimizationResult {
  fun isIncludeView(node: TreeNode): Boolean {
    val isOkResourceId = run {
      val resourceId = node.attributes["resource-id"] ?: return@run true
      val hasNotNeededId =
        resourceId.contains("status_bar_container") || resourceId.contains("status_bar_launch_animation_container")
      !hasNotNeededId
    }
    val isVisibleRectView = node.toUiElementOrNull()?.bounds?.let {
      it.width > 0 && it.height > 0
    } ?: true
    return isOkResourceId && isVisibleRectView
  }

  fun isMeaningfulViewDfs(node: TreeNode): Boolean {
    if (node.isMeaningfulView()) {
      return true
    }
    return node.children.any { isMeaningfulViewDfs(it) }
  }

  val childResults = children
    .filter { isIncludeView(it) && isMeaningfulViewDfs(it) }
    .map { it.optimizeTree2(false, viewHierarchy) }
  val optimizedChildren = childResults.flatMap {
    it.node?.let { node -> listOf(node) } ?: it.promotedChildren
  }
  if (isRoot) {
    return OptimizationResult(
      node = this.copy(children = optimizedChildren),
      promotedChildren = emptyList()
    )
  }
  val hasContentInThisNode = this.isMeaningfulView()
  if (hasContentInThisNode) {
    return OptimizationResult(
      node = this.copy(children = optimizedChildren),
      promotedChildren = emptyList()
    )
  }
  if (optimizedChildren.isEmpty()) {
    return OptimizationResult(
      node = null,
      promotedChildren = emptyList()
    )
  }
  val isSingleChild = optimizedChildren.size == 1
  return if (isSingleChild) {
    OptimizationResult(
      node = optimizedChildren.single(),
      promotedChildren = emptyList()
    )
  } else {
    OptimizationResult(
      node = null,
      promotedChildren = optimizedChildren
    )
  }
}


public fun TreeNode.optimizeTree(
  isRoot: Boolean = false,
  viewHierarchy: ViewHierarchy
): OptimizationResult {
  // Optimize children
  val childResults = children
    .filter {
      val isOkView = run {
        val resourceId = it.attributes["resource-id"] ?: return@run true
        !resourceId.contains("status_bar_container") && !resourceId.contains("status_bar_launch_animation_container")
      }
      isOkView &&
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

private fun TreeNode.getIdentifierDataForFocus(): List<Any> {
  val sortedAttributes = (attributes - "bounds" - "focused" - "selected").toSortedMap()
  return listOf(sortedAttributes) + children.map { it.getIdentifierDataForFocus() }
}

// takeScreenshot paths come from user-authored flows, so they must not escape screenshotsDir.
// Reject absolute or parent-traversing paths lexically, then canonicalize (without creating any
// directories) so a symlinked component that escapes the base is resolved and caught before we
// create the parent directory.
internal fun resolveScreenshotFile(screenshotsDir: File, path: String): File {
  val base = screenshotsDir.canonicalFile
  val requested = File("$path.png")
  require(!requested.isAbsolute) {
    "Screenshot path must be relative to the screenshots directory: $path"
  }
  require(requested.invariantSeparatorsPath.split('/').none { it == ".." }) {
    "Screenshot path must not traverse outside the screenshots directory: $path"
  }
  val canonical = File(base, requested.path).canonicalFile
  require(canonical.toPath().startsWith(base.toPath())) {
    "Screenshot path escapes the screenshots directory: $path"
  }
  canonical.parentFile?.mkdirs()
  return canonical
}
