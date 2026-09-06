package io.github.takahirom.arbigent

import kotlinx.coroutines.delay
import maestro.MaestroException
import maestro.Point
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.InputTextCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command

/**
 * Replays a recorded log against a device, with no AI in the loop.
 *
 * The point is reachability: the recorded run already proved these events reach the screen, so the
 * events are sent again in order and the only judgement made is "is the screen the recording acted
 * on here yet". That judgement is the same one the in-run trace replay makes, and it lives in
 * [ArbigentReplayWait] so the two cannot drift apart.
 *
 * Nothing is sent until the whole selection has been checked ([verifyReplayable]): a log that stops
 * halfway leaves the device on a screen nobody asked for, which is worse than refusing up front.
 */
@ArbigentInternalApi
public class ArbigentReplayRunner(
  private val log: ArbigentReplayLog,
  private val device: ArbigentDevice,
  private val out: Appendable,
  private val err: Appendable,
  /** When false, every event is sent as fast as the device accepts it. For a settled screen. */
  private val waitForScreens: Boolean = true,
) {
  private var currentScreenSize: Pair<Int, Int>? = null

  /**
   * Why this selection cannot be replayed on this device, or null when it can.
   *
   * Checked before the first event so a refusal leaves the device untouched.
   */
  public fun verifyReplayable(steps: List<ArbigentReplayLogStep>): String? {
    verifySelection(steps)?.let { return it }
    val deviceOs = device.os()
    if (deviceOs != log.platform) {
      return "the log was recorded on ${log.platform.name.lowercase()} but the connected device is " +
        "${deviceOs.name.lowercase()}"
    }
    return null
  }

  /**
   * Sends [steps] to the device and returns the process exit code: [EXIT_OK] when every step went
   * through and the end screen looks like the recorded one, [EXIT_DIVERGED] when the screen was not
   * what the recording acted on, and [EXIT_DEVICE] when the device itself could not be driven.
   */
  public suspend fun run(steps: List<ArbigentReplayLogStep>): Int {
    steps.forEachIndexed { position, step ->
      out.append("step ${step.number}: ${step.label()}\n")
      if (waitForScreens && !step.isInit) {
        val target = step.target
        val deadline = ArbigentReplayWait.deadlineMillis(recordedGapMillis(steps, position))
        if (target != null) {
          val result = ArbigentReplayWait.awaitSettled(device, deadline) { elements ->
            target.identity.findMatch(elements) != null
          }
          when (result) {
            is ArbigentReplayWait.WaitResult.Settled -> Unit
            ArbigentReplayWait.WaitResult.TimedOut -> {
              reportDivergence(
                step = step,
                remaining = steps.drop(position + 1),
                reason = "the target never appeared within ${deadline}ms",
              )
              return EXIT_DIVERGED
            }

            ArbigentReplayWait.WaitResult.Unreadable -> {
              err.append("\nstep ${step.number}: the screen could not be read while waiting\n")
              writeResumeHint(step, steps.drop(position + 1))
              return EXIT_DEVICE
            }
          }
        } else if (step.screen.isNotEmpty()) {
          // The hints say which screen the decision was looking at, not what it acted on, so
          // neither running out of time nor an unreadable screen stops the step; a device that
          // really is broken fails on the next event with a reason worth printing.
          when (ArbigentReplayWait.awaitSettled(device, deadline) { elements ->
            step.screen.any { hint -> hint.findMatch(elements) != null }
          }) {
            is ArbigentReplayWait.WaitResult.Settled -> Unit
            ArbigentReplayWait.WaitResult.TimedOut -> err.append(
              "  wait: none of the recorded screen hints appeared within ${deadline}ms, continuing\n",
            )

            ArbigentReplayWait.WaitResult.Unreadable -> err.append(
              "  wait: the screen could not be read within ${deadline}ms, continuing\n",
            )
          }
        }
      }
      step.events.forEach { event ->
        when (val outcome = send(event)) {
          SendOutcome.Sent -> Unit
          is SendOutcome.Diverged -> {
            reportDivergence(step, steps.drop(position + 1), outcome.reason)
            return EXIT_DIVERGED
          }

          is SendOutcome.DeviceFailure -> {
            err.append("\nstep ${step.number}: ${outcome.reason}\n")
            writeResumeHint(step, steps.drop(position + 1))
            return EXIT_DEVICE
          }
        }
      }
    }
    return checkArrival(steps)
  }

  /**
   * Whether the end screen carries any of the resource ids the recorded run left behind.
   *
   * Only asked when the selection ran to the recorded end: a partial replay stops somewhere in the
   * middle by design, and the recorded end screen says nothing about where it stopped.
   */
  private suspend fun checkArrival(steps: List<ArbigentReplayLogStep>): Int {
    val lastNumber = log.lastStepNumber() ?: return EXIT_OK
    if (steps.none { !it.isInit && it.number == lastNumber }) return EXIT_OK
    if (log.signature.isEmpty()) return EXIT_OK
    if (waitForScreens) {
      // The last event has only just been sent, so the end screen is usually still arriving. Wait
      // for it the same way every other step is waited for, then look regardless of how the wait
      // ended: an end screen that keeps animating is still the right screen.
      val result = ArbigentReplayWait.awaitSettled(device, ArbigentReplayWait.deadlineMillis(null)) { elements ->
        log.signature.any { resourceId ->
          ArbigentElementIdentity(resourceId = resourceId).findMatch(elements) != null
        }
      }
      if (result == ArbigentReplayWait.WaitResult.Unreadable) {
        err.append("\ncould not read the screen to check where the replay arrived\n")
        return EXIT_DEVICE
      }
    }
    val elements = try {
      device.elements()
    } catch (exception: Exception) {
      err.append("\ncould not read the screen to check where the replay arrived: $exception\n")
      return EXIT_DEVICE
    }
    val present = log.signature.filter { resourceId ->
      ArbigentElementIdentity(resourceId = resourceId).findMatch(elements) != null
    }
    if (present.isEmpty()) {
      err.append("\nDIVERGED\n")
      err.append("  goal: ${log.goal}\n")
      err.append("  reason: none of the recorded end-screen ids are present\n")
      err.append("  expected any of: ${log.signature.joinToString()}\n")
      appendScreen(err, elements)
      return EXIT_DIVERGED
    }
    out.append("arrived: the end screen carries ${present.size} of ${log.signature.size} recorded ids\n")
    return EXIT_OK
  }

  private sealed interface SendOutcome {
    object Sent : SendOutcome
    data class Diverged(val reason: String) : SendOutcome
    data class DeviceFailure(val reason: String) : SendOutcome
  }

  private suspend fun send(event: ArbigentDeviceEvent): SendOutcome {
    if (event is ArbigentDeviceEvent.Wait) {
      delay(event.millis.coerceAtMost(ArbigentReplayWait.MAX_WAIT_MILLIS))
      return SendOutcome.Sent
    }
    val command = try {
      toMaestroCommand(event)
    } catch (exception: ArbigentReplayLogException) {
      return SendOutcome.Diverged(exception.message ?: "the event could not be replayed")
    }
    return try {
      device.executeActions(listOf(command))
      SendOutcome.Sent
    } catch (exception: Exception) {
      // An element the recorded run tapped that is not on screen now is a divergence, not a broken
      // device: the replay reached a different screen. Anything else means the device could not be
      // driven at all, and a later step would fail for the same reason.
      if (exception.hasCause<MaestroException.ElementNotFound>()) {
        SendOutcome.Diverged("${describeEvent(event)}: the element is not on screen")
      } else {
        SendOutcome.DeviceFailure("${describeEvent(event)} failed: $exception")
      }
    }
  }

  /**
   * The Maestro command that replays [event].
   *
   * Coordinates recorded on a differently sized screen are scaled, because a pixel that was inside
   * a button on the recording device is often outside it here. Percentages would be the obvious
   * alternative, but Maestro parses a percentage point as a whole number, which on a 1080px screen
   * rounds to the nearest 11 pixels.
   */
  private fun toMaestroCommand(event: ArbigentDeviceEvent): MaestroCommand = when (event) {
    is ArbigentDeviceEvent.Tap -> {
      val (x, y) = scale(event.x, event.y)
      MaestroCommand(tapOnPointV2Command = TapOnPointV2Command(point = "$x,$y"))
    }

    is ArbigentDeviceEvent.TapElement -> MaestroCommand(
      tapOnElement = TapOnElementCommand(
        selector = ElementSelector(
          textRegex = event.textRegex,
          idRegex = event.idRegex,
          index = event.index.takeIf { it > 0 }?.toString(),
        ),
      ),
    )

    is ArbigentDeviceEvent.KeyPress -> {
      // Refused by verifySelection before anything is sent; reaching here means a caller skipped it.
      val code = KeyPressAgentAction.resolveKeyCode(event.keyName)
        ?: throw ArbigentReplayLogException(unknownKeyReason(event.keyName))
      MaestroCommand(pressKeyCommand = PressKeyCommand(code))
    }

    is ArbigentDeviceEvent.InputText -> MaestroCommand(
      inputTextCommand = InputTextCommand(text = event.text),
    )

    is ArbigentDeviceEvent.Swipe -> {
      val (startX, startY) = scale(event.startX, event.startY)
      val (endX, endY) = scale(event.endX, event.endY)
      MaestroCommand(
        swipeCommand = SwipeCommand(
          startPoint = Point(startX, startY),
          endPoint = Point(endX, endY),
          duration = event.durationMs,
        ),
      )
    }

    is ArbigentDeviceEvent.LaunchApp -> MaestroCommand(
      launchAppCommand = LaunchAppCommand(
        appId = event.appId,
        clearState = event.clearState,
        stopApp = event.stopApp,
        launchArguments = event.launchArguments
          .mapValues { (_, value) -> value.launchArgumentValue() }
          .takeIf { it.isNotEmpty() },
      ),
    )

    is ArbigentDeviceEvent.StopApp -> MaestroCommand(
      stopAppCommand = StopAppCommand(appId = event.appId),
    )

    is ArbigentDeviceEvent.ClearState -> MaestroCommand(
      clearStateCommand = ClearStateCommand(appId = event.appId),
    )

    is ArbigentDeviceEvent.OpenLink -> MaestroCommand(
      openLinkCommand = OpenLinkCommand(link = event.url),
    )

    // Refused by verifyReplayable before anything is sent; reaching here means a caller skipped it.
    is ArbigentDeviceEvent.Unsupported ->
      throw ArbigentReplayLogException("the recorded run used ${event.command}, which cannot be replayed")

    is ArbigentDeviceEvent.Wait ->
      throw ArbigentReplayLogException("a wait is not a device command")
  }

  /**
   * [x] and [y] on this screen, given where they were on the recording's screen.
   *
   * Left alone when either size is unknown: guessing a scale from one known size would move a tap
   * further from the element than leaving it where it was recorded.
   */
  private fun scale(x: Int, y: Int): Pair<Int, Int> {
    val recordedWidth = log.screenWidth ?: return x to y
    val recordedHeight = log.screenHeight ?: return x to y
    val (currentWidth, currentHeight) = currentScreenSize() ?: return x to y
    if (currentWidth == recordedWidth && currentHeight == recordedHeight) return x to y
    return (x.toLong() * currentWidth / recordedWidth).toInt() to
      (y.toLong() * currentHeight / recordedHeight).toInt()
  }

  private fun currentScreenSize(): Pair<Int, Int>? {
    currentScreenSize?.let { return it }
    val elements = try {
      device.elements()
    } catch (exception: Exception) {
      arbigentDebugLog("Replay: could not read the screen size: $exception")
      return null
    }
    if (elements.screenWidth <= 0 || elements.screenHeight <= 0) return null
    return (elements.screenWidth to elements.screenHeight).also { currentScreenSize = it }
  }

  private fun recordedGapMillis(steps: List<ArbigentReplayLogStep>, position: Int): Long? {
    val previous = steps.getOrNull(position - 1) ?: return null
    val current = steps[position]
    return (current.timestamp - previous.timestamp).takeIf { it > 0 }
  }

  private fun reportDivergence(
    step: ArbigentReplayLogStep,
    remaining: List<ArbigentReplayLogStep>,
    reason: String,
  ) {
    err.append("\nDIVERGED\n")
    err.append("  goal: ${step.goal.ifBlank { log.goal }}\n")
    err.append("  step ${step.number}: ${step.label()}\n")
    step.memo?.let { err.append("  memo: ${it.replace("\n", " ")}\n") }
    err.append("  reason: $reason\n")
    err.append("  expected: ${step.target?.identity?.description() ?: "(no recorded target)"}\n")
    val elements = try {
      device.elements()
    } catch (exception: Exception) {
      err.append("  on screen now: (the screen could not be read: $exception)\n")
      writeResumeHint(step, remaining)
      return
    }
    appendScreen(err, elements)
    writeResumeHint(step, remaining)
  }

  private fun appendScreen(sink: Appendable, elements: ArbigentElementList) {
    sink.append("  on screen now:\n")
    val shown = elements.elements.asSequence()
      .mapNotNull { element -> ArbigentElementIdentity.from(element, elements.elements)?.description() }
      .distinct()
      .take(MaxShownElements + 1)
      .toList()
    shown.take(MaxShownElements).forEach { sink.append("    - $it\n") }
    if (shown.size > MaxShownElements) sink.append("    ... (more elements not shown)\n")
  }

  /**
   * How to carry on from where this stopped.
   *
   * Step zero is not a valid `--from`, so a failed setup block points at the first numbered step it
   * was preparing for instead.
   */
  private fun writeResumeHint(step: ArbigentReplayLogStep, remaining: List<ArbigentReplayLogStep>) {
    val resumeFrom = if (step.isInit) {
      remaining.firstOrNull { !it.isInit }?.number ?: return
    } else {
      step.number
    }
    val withInit = if (step.isInit) " --with-init" else ""
    err.append("  resume with: arbigent replay <log> --from $resumeFrom$withInit\n")
  }

  @ArbigentInternalApi
  public companion object {
    public const val EXIT_OK: Int = 0

    /** The screen was not the one the recording acted on. */
    public const val EXIT_DIVERGED: Int = 2

    /** The device could not be driven, so nothing is known about where the replay got to. */
    public const val EXIT_DEVICE: Int = 3

    private const val MaxShownElements = 40

    /**
     * Prints a selection without touching a device, so a caller can see what a range would do.
     * Deliberately not a member: showing a log needs no device to be connected first.
     */
    /**
     * Why this selection cannot be replayed at all, or null when only the device is left to check.
     *
     * Separate from [verifyReplayable] because neither reason needs a device, and a caller that
     * refuses here avoids starting a driver only to throw the session away.
     */
    public fun verifySelection(steps: List<ArbigentReplayLogStep>): String? {
      if (steps.isEmpty()) return "nothing to replay for the given range"
      val unsupported = steps.flatMap { step ->
        step.events.filterIsInstance<ArbigentDeviceEvent.Unsupported>().map { step.number to it.command }
      }
      if (unsupported.isNotEmpty()) {
        return "the recorded run used commands this replay cannot reproduce: " +
          unsupported.joinToString(separator = "; ") { (number, command) -> "step $number: $command" }
      }
      // A key this Maestro cannot resolve would otherwise only be noticed as the event is sent,
      // half way through the selection, leaving the device on a screen nobody asked for.
      val unknownKeys = steps.flatMap { step ->
        step.events.filterIsInstance<ArbigentDeviceEvent.KeyPress>()
          .filter { KeyPressAgentAction.resolveKeyCode(it.keyName) == null }
          .map { step.number to it.keyName }
      }
      if (unknownKeys.isNotEmpty()) {
        return unknownKeys.joinToString(separator = "; ") { (number, keyName) ->
          "step $number: ${unknownKeyReason(keyName)}"
        }
      }
      return null
    }

    /** Said the same way whether a key is refused up front or, impossibly, as it is sent. */
    private fun unknownKeyReason(keyName: String): String =
      "the recorded key '$keyName' is not a key this Maestro knows"

    public fun show(log: ArbigentReplayLog, steps: List<ArbigentReplayLogStep>, out: Appendable) {
      out.append("scenario: ${log.scenarioId}\n")
      out.append("goal: ${log.goal}\n")
      log.appId?.let { out.append("app: $it\n") }
      out.append("platform: ${log.platform.name.lowercase()}\n")
      steps.forEach { step ->
        out.append("step ${step.number}: ${step.label()}\n")
        step.target?.let { out.append("  target: ${it.identity.description()}\n") }
        step.events.forEach { out.append("  ${describeEvent(it)}\n") }
      }
      if (log.signature.isNotEmpty()) {
        out.append("expect: resource ids ${log.signature.joinToString()}\n")
      }
    }
  }
}

/** A one-line description of what an event does, for logs a person reads. */
private fun describeEvent(event: ArbigentDeviceEvent): String = when (event) {
  is ArbigentDeviceEvent.Tap -> "tap (${event.x}, ${event.y})"
  is ArbigentDeviceEvent.TapElement -> "tap on " + listOfNotNull(
    event.textRegex?.let { "text='$it'" },
    event.idRegex?.let { "id='$it'" },
    event.index.takeIf { it > 0 }?.let { "index=$it" },
  ).joinToString()

  is ArbigentDeviceEvent.KeyPress -> "press ${event.keyName}"
  is ArbigentDeviceEvent.InputText -> "input text"
  is ArbigentDeviceEvent.Swipe ->
    "swipe (${event.startX}, ${event.startY}) -> (${event.endX}, ${event.endY})"

  is ArbigentDeviceEvent.LaunchApp -> "launch ${event.appId}"
  is ArbigentDeviceEvent.StopApp -> "stop ${event.appId}"
  is ArbigentDeviceEvent.ClearState -> "clear state of ${event.appId}"
  is ArbigentDeviceEvent.Wait -> "wait ${event.millis}ms"
  is ArbigentDeviceEvent.OpenLink -> "open ${event.url}"
  is ArbigentDeviceEvent.Unsupported -> event.command
}

/** True when [T] is this exception or anywhere in its cause chain. */
private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
  var current: Throwable? = this
  val seen = mutableSetOf<Throwable>()
  while (current != null && seen.add(current)) {
    if (current is T) return true
    current = current.cause
  }
  return false
}

/**
 * The value Maestro wants for a recorded launch argument. Maestro types launch arguments as `Any`
 * and decides the `am start` flag from the runtime type, so a recorded boolean or number has to go
 * back as one rather than as its text.
 */
private fun kotlinx.serialization.json.JsonPrimitive.launchArgumentValue(): Any {
  if (isString) return content
  content.toBooleanStrictOrNull()?.let { return it }
  content.toIntOrNull()?.let { return it }
  content.toLongOrNull()?.let { return it }
  content.toDoubleOrNull()?.let { return it }
  return content
}
