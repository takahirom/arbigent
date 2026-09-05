package io.github.takahirom.arbigent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import maestro.KeyCode
import maestro.SwipeDirection
import maestro.orchestra.ElementSelector
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TapOnElementCommand

/**
 * A single low-level interaction Arbigent sent to the device, recorded at the point where the
 * device layer hands it to Maestro.
 *
 * The point of recording here rather than at the agent-action layer is fidelity: one agent action
 * can expand into several device commands (a D-pad focus walk is many key presses), and only the
 * device layer knows what was really sent. Each event is expressed in terms an `adb` user can
 * reproduce, so a replay script needs no Arbigent and no Maestro.
 */
@ArbigentInternalApi
@Serializable
public sealed interface ArbigentDeviceEvent {
  /** Epoch milliseconds, from [TimeProvider], so tests can pin it. */
  public val timestamp: Long

  @Serializable
  @SerialName("tap")
  public data class Tap(
    val x: Int,
    val y: Int,
    override val timestamp: Long = TimeProvider.get().currentTimeMillis(),
  ) : ArbigentDeviceEvent

  /**
   * A tap on an element Maestro located by text or resource id at the time. The selector is kept
   * so a replay can find the element again in the current hierarchy and press its center, since the
   * pixel it landed on in the recorded run is only right while the layout has not moved. [index] is
   * the zero-based position among the matches, as Maestro counts them.
   */
  @Serializable
  @SerialName("tap_element")
  public data class TapElement(
    val textRegex: String? = null,
    val idRegex: String? = null,
    val index: Int = 0,
    override val timestamp: Long = TimeProvider.get().currentTimeMillis(),
  ) : ArbigentDeviceEvent

  /** [keyName] is an Android `KEYCODE_*` name, ready for `adb shell input keyevent`. */
  @Serializable
  @SerialName("key_press")
  public data class KeyPress(
    val keyName: String,
    override val timestamp: Long = TimeProvider.get().currentTimeMillis(),
  ) : ArbigentDeviceEvent

  @Serializable
  @SerialName("input_text")
  public data class InputText(
    val text: String,
    override val timestamp: Long = TimeProvider.get().currentTimeMillis(),
  ) : ArbigentDeviceEvent

  @Serializable
  @SerialName("swipe")
  public data class Swipe(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long,
    override val timestamp: Long = TimeProvider.get().currentTimeMillis(),
  ) : ArbigentDeviceEvent

  /**
   * [launchArguments] are the intent extras the app was started with, typed as JSON primitives so a
   * replay can pass each one through the matching `am start` flag. An app that reads a launch extra
   * to decide what to show (a test-only entry point, a first-run flow) starts somewhere else without
   * them, and every later step then diverges.
   *
   * [stopApp] mirrors Maestro's default of force-stopping the app before starting it, so a replay
   * starts a fresh process the way the recorded run did instead of resuming whatever was left.
   */
  @Serializable
  @SerialName("launch_app")
  public data class LaunchApp(
    val appId: String,
    val clearState: Boolean = false,
    val stopApp: Boolean = true,
    val launchArguments: Map<String, JsonPrimitive> = emptyMap(),
    override val timestamp: Long = TimeProvider.get().currentTimeMillis(),
  ) : ArbigentDeviceEvent

  @Serializable
  @SerialName("stop_app")
  public data class StopApp(
    val appId: String,
    override val timestamp: Long = TimeProvider.get().currentTimeMillis(),
  ) : ArbigentDeviceEvent

  @Serializable
  @SerialName("clear_state")
  public data class ClearState(
    val appId: String,
    override val timestamp: Long = TimeProvider.get().currentTimeMillis(),
  ) : ArbigentDeviceEvent

  @Serializable
  @SerialName("wait")
  public data class Wait(
    val millis: Long,
    override val timestamp: Long = TimeProvider.get().currentTimeMillis(),
  ) : ArbigentDeviceEvent

  @Serializable
  @SerialName("open_link")
  public data class OpenLink(
    val url: String,
    override val timestamp: Long = TimeProvider.get().currentTimeMillis(),
  ) : ArbigentDeviceEvent

  /**
   * A command that has no faithful `adb` equivalent. Recorded rather than dropped so a replay stops
   * at the point where it would silently diverge instead of carrying on against a wrong screen.
   */
  @Serializable
  @SerialName("unsupported")
  public data class Unsupported(
    val command: String,
    override val timestamp: Long = TimeProvider.get().currentTimeMillis(),
  ) : ArbigentDeviceEvent
}

/** Receives every [ArbigentDeviceEvent] a device emits, on the thread that sent the command. */
@ArbigentInternalApi
public fun interface ArbigentDeviceEventListener {
  public fun onDeviceEvent(event: ArbigentDeviceEvent)
}

/**
 * Translates one Maestro command into the device events it will actually perform.
 *
 * Gestures Maestro expresses relative to the screen (scroll, directional swipe) are resolved into
 * absolute pixels here using [screenWidth]/[screenHeight], because the replay script only has
 * `adb shell input swipe`, which takes pixels. The fractions mirror Maestro's own AndroidDriver
 * (verified against the pinned Maestro release), so a replayed swipe covers the same distance the
 * recorded run did.
 */
internal fun MaestroCommand.toArbigentDeviceEvents(
  screenWidth: Int,
  screenHeight: Int,
  timestamp: Long = TimeProvider.get().currentTimeMillis(),
): List<ArbigentDeviceEvent> {
  tapOnPointV2Command?.let { command ->
    if (command.longPress == true || command.repeat != null) {
      return listOf(ArbigentDeviceEvent.Unsupported("tapOnPointV2 ${command.point} (longPress/repeat)", timestamp))
    }
    val point = command.point
    // Arbigent always issues absolute points; a percentage point would need the driver's own
    // resolution rules, so it is recorded as a divergence instead of guessed at.
    if (point.contains('%')) return listOf(ArbigentDeviceEvent.Unsupported("tapOnPointV2 $point", timestamp))
    val x = point.substringBefore(',').trim().toIntOrNull()
    val y = point.substringAfter(',', "").trim().toIntOrNull()
    if (x == null || y == null) {
      return listOf(ArbigentDeviceEvent.Unsupported("tapOnPointV2 $point", timestamp))
    }
    return listOf(ArbigentDeviceEvent.Tap(x, y, timestamp))
  }
  tapOnPoint?.let { command ->
    return listOf(ArbigentDeviceEvent.Tap(command.x, command.y, timestamp))
  }
  // Element taps are how the agent clicks by text or id, so they are the common case on phones.
  // Only a selector with something a hierarchy dump can be searched for is replayable.
  tapOnElement?.let { command ->
    val selector = command.selector
    if (selector.textRegex == null && selector.idRegex == null) {
      return listOf(ArbigentDeviceEvent.Unsupported("tapOn $selector", timestamp))
    }
    // A plain tap on the first element matching text/id/index is all the replay can reproduce.
    // Anything that changes which element is hit or how (a long press, a repeated tap, a tap at a
    // relative point, a selector narrowed by position, traits or state) is recorded as a
    // divergence rather than silently downgraded to a different interaction.
    if (command.longPress == true || command.repeat != null || command.relativePoint != null ||
      selector.hasConstraintsBeyondTextIdIndex()
    ) {
      return listOf(ArbigentDeviceEvent.Unsupported("tapOn $selector (${command.describeExtras()})", timestamp))
    }
    return listOf(
      ArbigentDeviceEvent.TapElement(
        textRegex = selector.textRegex,
        idRegex = selector.idRegex,
        index = selector.index?.toIntOrNull() ?: 0,
        timestamp = timestamp,
      )
    )
  }
  backPressCommand?.let { return listOf(ArbigentDeviceEvent.KeyPress("KEYCODE_BACK", timestamp)) }
  pressKeyCommand?.let { command ->
    val keyName = command.code.toAndroidKeyName()
      ?: return listOf(ArbigentDeviceEvent.Unsupported("pressKey ${command.code}", timestamp))
    return listOf(ArbigentDeviceEvent.KeyPress(keyName, timestamp))
  }
  inputTextCommand?.let { return listOf(ArbigentDeviceEvent.InputText(it.text, timestamp)) }
  eraseTextCommand?.let { command ->
    val count = command.charactersToErase ?: DEFAULT_CHARACTERS_TO_ERASE
    return List(count) { ArbigentDeviceEvent.KeyPress("KEYCODE_DEL", timestamp) }
  }
  launchAppCommand?.let { command ->
    return listOf(
      ArbigentDeviceEvent.LaunchApp(
        appId = command.appId,
        clearState = command.clearState == true,
        // Maestro treats a missing stopApp as true.
        stopApp = command.stopApp != false,
        launchArguments = command.launchArguments.orEmpty().mapValues { (_, value) -> value.toJsonPrimitive() },
        timestamp = timestamp,
      )
    )
  }
  stopAppCommand?.let { return listOf(ArbigentDeviceEvent.StopApp(it.appId, timestamp)) }
  // killApp asks the driver to kill the process without clearing tasks, which `am force-stop`
  // does not reproduce exactly, so it is left to the AI fallback.
  killAppCommand?.let { return listOf(ArbigentDeviceEvent.Unsupported("killApp ${it.appId}", timestamp)) }
  clearStateCommand?.let { return listOf(ArbigentDeviceEvent.ClearState(it.appId, timestamp)) }
  openLinkCommand?.let { return listOf(ArbigentDeviceEvent.OpenLink(it.link, timestamp)) }
  scrollCommand?.let {
    return listOf(swipeEvent(SwipeDirection.UP, SCROLL_DURATION_MS, screenWidth, screenHeight, timestamp))
  }
  swipeCommand?.let { command ->
    // A swipe that starts on an element depends on where that element is at replay time.
    if (command.elementSelector != null) {
      return listOf(ArbigentDeviceEvent.Unsupported("swipe from element ${command.elementSelector}", timestamp))
    }
    val start = command.startPoint
    val end = command.endPoint
    if (start != null && end != null) {
      return listOf(
        ArbigentDeviceEvent.Swipe(start.x, start.y, end.x, end.y, command.duration, timestamp)
      )
    }
    val startRelative = command.startRelative
    val endRelative = command.endRelative
    if (startRelative != null && endRelative != null) {
      val startPoint = parseRelativePoint(startRelative, screenWidth, screenHeight)
      val endPoint = parseRelativePoint(endRelative, screenWidth, screenHeight)
      if (startPoint == null || endPoint == null) {
        return listOf(
          ArbigentDeviceEvent.Unsupported("swipe $startRelative -> $endRelative", timestamp)
        )
      }
      return listOf(
        ArbigentDeviceEvent.Swipe(
          startPoint.first, startPoint.second, endPoint.first, endPoint.second,
          command.duration, timestamp,
        )
      )
    }
    val direction = command.direction
      ?: return listOf(ArbigentDeviceEvent.Unsupported("swipe $command", timestamp))
    return listOf(swipeEvent(direction, command.duration, screenWidth, screenHeight, timestamp))
  }
  waitForAnimationToEndCommand?.let { command ->
    val millis = command.timeout?.toLongOrNull() ?: DEFAULT_ANIMATION_WAIT_MS
    return listOf(ArbigentDeviceEvent.Wait(millis, timestamp))
  }
  // Screenshots are Arbigent's own observation of the device, not something a replay has to redo.
  takeScreenshotCommand?.let { return emptyList() }
  hideKeyboardCommand?.let { return listOf(ArbigentDeviceEvent.Unsupported("hideKeyboard", timestamp)) }
  return listOf(ArbigentDeviceEvent.Unsupported(describeUnknown(), timestamp))
}

private const val DEFAULT_CHARACTERS_TO_ERASE = 50

/** Maestro's own default when `waitForAnimationToEnd` carries no timeout. */
private const val DEFAULT_ANIMATION_WAIT_MS = 5000L

/** Maestro's AndroidDriver scrolls with a fixed-duration directional swipe. */
private const val SCROLL_DURATION_MS = 400L

private fun MaestroCommand.describeUnknown(): String =
  runCatching { description() }.getOrNull() ?: toString()

/**
 * The start and end points Maestro's AndroidDriver uses for each swipe direction, as fractions of
 * the screen. Taken from the pinned Maestro release rather than assumed, because a swipe that
 * starts in the wrong half scrolls the wrong list.
 */
private fun swipeEvent(
  direction: SwipeDirection,
  durationMs: Long,
  screenWidth: Int,
  screenHeight: Int,
  timestamp: Long,
): ArbigentDeviceEvent {
  val fractions = when (direction) {
    SwipeDirection.UP -> listOf(0.5f, 0.5f, 0.5f, 0.1f)
    SwipeDirection.DOWN -> listOf(0.5f, 0.2f, 0.5f, 0.9f)
    SwipeDirection.RIGHT -> listOf(0.1f, 0.5f, 0.9f, 0.5f)
    SwipeDirection.LEFT -> listOf(0.9f, 0.5f, 0.1f, 0.5f)
  }
  return ArbigentDeviceEvent.Swipe(
    startX = (screenWidth * fractions[0]).toInt(),
    startY = (screenHeight * fractions[1]).toInt(),
    endX = (screenWidth * fractions[2]).toInt(),
    endY = (screenHeight * fractions[3]).toInt(),
    durationMs = durationMs,
    timestamp = timestamp,
  )
}

private fun parseRelativePoint(
  relative: String,
  screenWidth: Int,
  screenHeight: Int,
): Pair<Int, Int>? {
  val parts = relative.split(",")
  if (parts.size != 2) return null
  val xPercent = parts[0].trim().removeSuffix("%").toFloatOrNull() ?: return null
  val yPercent = parts[1].trim().removeSuffix("%").toFloatOrNull() ?: return null
  return (screenWidth * xPercent / 100f).toInt() to (screenHeight * yPercent / 100f).toInt()
}

/**
 * The event a single Maestro key press performs. Used by the D-pad focus walk, which presses keys
 * through Maestro directly instead of going through a [MaestroCommand].
 */
internal fun arbigentKeyPressEvent(
  code: KeyCode,
  timestamp: Long = TimeProvider.get().currentTimeMillis(),
): ArbigentDeviceEvent = code.toAndroidKeyName()
  ?.let { ArbigentDeviceEvent.KeyPress(it, timestamp) }
  ?: ArbigentDeviceEvent.Unsupported("pressKey $code", timestamp)

/** Android `KEYCODE_*` name for a Maestro key, or null when Android has no equivalent. */
private fun KeyCode.toAndroidKeyName(): String? = when (this) {
  KeyCode.ENTER -> "KEYCODE_ENTER"
  KeyCode.BACKSPACE -> "KEYCODE_DEL"
  KeyCode.BACK -> "KEYCODE_BACK"
  KeyCode.HOME -> "KEYCODE_HOME"
  KeyCode.LOCK -> "KEYCODE_POWER"
  KeyCode.POWER -> "KEYCODE_POWER"
  KeyCode.VOLUME_UP -> "KEYCODE_VOLUME_UP"
  KeyCode.VOLUME_DOWN -> "KEYCODE_VOLUME_DOWN"
  KeyCode.ESCAPE -> "KEYCODE_ESCAPE"
  KeyCode.TAB -> "KEYCODE_TAB"
  KeyCode.REMOTE_UP -> "KEYCODE_DPAD_UP"
  KeyCode.REMOTE_DOWN -> "KEYCODE_DPAD_DOWN"
  KeyCode.REMOTE_LEFT -> "KEYCODE_DPAD_LEFT"
  KeyCode.REMOTE_RIGHT -> "KEYCODE_DPAD_RIGHT"
  KeyCode.REMOTE_CENTER -> "KEYCODE_DPAD_CENTER"
  KeyCode.REMOTE_PLAY_PAUSE -> "KEYCODE_MEDIA_PLAY_PAUSE"
  KeyCode.REMOTE_STOP -> "KEYCODE_MEDIA_STOP"
  KeyCode.REMOTE_NEXT -> "KEYCODE_MEDIA_NEXT"
  KeyCode.REMOTE_PREVIOUS -> "KEYCODE_MEDIA_PREVIOUS"
  KeyCode.REMOTE_REWIND -> "KEYCODE_MEDIA_REWIND"
  KeyCode.REMOTE_FAST_FORWARD -> "KEYCODE_MEDIA_FAST_FORWARD"
  KeyCode.REMOTE_SYSTEM_NAVIGATION_UP -> "KEYCODE_SYSTEM_NAVIGATION_UP"
  KeyCode.REMOTE_SYSTEM_NAVIGATION_DOWN -> "KEYCODE_SYSTEM_NAVIGATION_DOWN"
  KeyCode.REMOTE_BUTTON_A -> "KEYCODE_BUTTON_A"
  KeyCode.REMOTE_BUTTON_B -> "KEYCODE_BUTTON_B"
  KeyCode.REMOTE_MENU -> "KEYCODE_MENU"
  KeyCode.TV_INPUT -> "KEYCODE_TV_INPUT"
  KeyCode.TV_INPUT_HDMI_1 -> "KEYCODE_TV_INPUT_HDMI_1"
  KeyCode.TV_INPUT_HDMI_2 -> "KEYCODE_TV_INPUT_HDMI_2"
  KeyCode.TV_INPUT_HDMI_3 -> "KEYCODE_TV_INPUT_HDMI_3"
  else -> null
}

/**
 * Maestro types launch arguments loosely as `Any`; the runner needs the kind to pick an `am start`
 * flag (`--ez`, `--ei`, `--ef`, `--es`), so booleans and numbers keep their JSON type and everything
 * else travels as text.
 */
private fun Any.toJsonPrimitive(): JsonPrimitive = when (this) {
  is Boolean -> JsonPrimitive(this)
  is Number -> JsonPrimitive(this)
  else -> JsonPrimitive(toString())
}

/** True when the selector narrows the match by anything other than text, id and index. */
private fun ElementSelector.hasConstraintsBeyondTextIdIndex(): Boolean =
  size != null || below != null || above != null || leftOf != null || rightOf != null ||
    containsChild != null || containsDescendants != null || childOf != null || traits != null ||
    enabled != null || selected != null || checked != null || focused != null || css != null

private fun TapOnElementCommand.describeExtras(): String = buildList {
  if (longPress == true) add("longPress")
  if (repeat != null) add("repeat")
  if (relativePoint != null) add("relativePoint")
  if (selector.hasConstraintsBeyondTextIdIndex()) add("constrained selector")
}.joinToString(", ")
