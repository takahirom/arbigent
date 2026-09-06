package io.github.takahirom.arbigent

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waiting for the screen a recorded step acted on, without asking the AI whether it has arrived.
 *
 * Both replay paths need the same rule — the trace replay inside a scenario run
 * ([ArbigentReplayPacingStepInterceptor]) and `arbigent replay`, which drives a recorded log with no
 * AI at all — so the deadline, the poll interval and what "settled" means live here rather than in
 * either caller.
 */
internal object ArbigentReplayWait {
  /** A recorded gap can be pathological (a hiccup while recording); do not inherit it unbounded. */
  const val MAX_WAIT_MILLIS: Long = 60_000L

  /** A short recorded gap says the recording run was fast, not that the replayed screen will be. */
  const val MIN_WAIT_MILLIS: Long = 10_000L

  const val POLL_INTERVAL_MILLIS: Long = 500L

  /** How long a wait may take: the recorded gap, never below 10 seconds nor above a minute. */
  fun deadlineMillis(recordedGapMillis: Long?): Long {
    val recordedGap = recordedGapMillis ?: return MIN_WAIT_MILLIS
    return recordedGap.coerceAtMost(MAX_WAIT_MILLIS).coerceAtLeast(MIN_WAIT_MILLIS)
  }

  /** How a wait ended. */
  sealed interface WaitResult {
    /** [isReady] held and the screen stopped changing, after [waitedMillis]. */
    data class Settled(val waitedMillis: Long) : WaitResult

    /** The screen was readable throughout but never settled on what was asked for. */
    object TimedOut : WaitResult

    /**
     * The hierarchy could not be read even once. A screen that is merely mid-transition answers at
     * least one poll, so this says the device could not be driven rather than that the target is
     * absent, and callers report it as a device failure instead of a divergence.
     */
    object Unreadable : WaitResult
  }

  /**
   * Waits until [isReady] holds for the element list and that list stops changing, which is what
   * "the screen has settled on the recorded target" means without asking the AI.
   *
   * Elapsed time is counted in poll intervals rather than read from the clock, so the loop advances
   * with the suspending [delay] instead of spinning against a clock that the caller may be
   * controlling.
   *
   * The deadline is soft by design: it is checked between polls, and a poll is one blocking
   * [ArbigentDevice.elements] read, so the wait can overrun by at most one hierarchy dump (bounded
   * by the device's own retry policy). Interrupting a dump midway would leave Maestro's driver in an
   * undefined state, and a step captured a few seconds late is still a correct step.
   */
  suspend fun awaitSettled(
    device: ArbigentDevice,
    deadlineMillis: Long,
    isReady: (ArbigentElementList) -> Boolean,
  ): WaitResult {
    var readAtLeastOnce = false
    val waited = withTimeoutOrNull(deadlineMillis) {
      var previousSignature: String? = null
      var waited = 0L
      while (true) {
        val elements = readElements(device)
        if (elements != null) {
          readAtLeastOnce = true
          val signature = if (isReady(elements)) signatureOf(elements) else null
          if (signature != null && signature == previousSignature) break
          previousSignature = signature
        } else {
          // An unreadable poll says nothing about the screen, so the next match has to repeat again.
          previousSignature = null
        }
        delay(POLL_INTERVAL_MILLIS)
        waited += POLL_INTERVAL_MILLIS
      }
      waited
    }
    return when {
      waited != null -> WaitResult.Settled(waited)
      readAtLeastOnce -> WaitResult.TimedOut
      else -> WaitResult.Unreadable
    }
  }

  private fun readElements(device: ArbigentDevice): ArbigentElementList? = try {
    device.elements()
  } catch (exception: Exception) {
    // Reading the hierarchy can fail while the screen is mid-transition, which is exactly the state
    // this waits out. Only a wait in which every single poll failed is treated as a broken device.
    arbigentDebugLog("Replay wait: could not read elements: $exception")
    null
  }

  /**
   * A cheap stand-in for the current screen. Built from the same element snapshot the readiness
   * check is made against, because the UI tree string is expensive enough that polling it would
   * itself slow replay down.
   *
   * It covers what an action resolves against: order, text (which Arbigent already reads from the
   * descendants), resource id, bounds and visibility. A list in which the same elements are still
   * sliding into place therefore reads as unsettled, while a repaint that changes nothing an action
   * could see does not hold the replay up.
   */
  private fun signatureOf(elements: ArbigentElementList): String =
    elements.elements.joinToString(separator = "|", prefix = "${elements.elements.size}#") {
      "${it.rawText}/${it.treeNode.attributes["resource-id"].orEmpty()}" +
        "@${it.x},${it.y},${it.width},${it.height},${it.isVisible}"
    }
}
