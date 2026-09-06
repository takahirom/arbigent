package io.github.takahirom.arbigent

import kotlinx.coroutines.delay

/**
 * Waiting for the screen a recorded step acted on, without asking the AI whether it has arrived.
 *
 * Both replay paths need the same rule — the trace replay inside a scenario run
 * ([ArbigentReplayPacingStepInterceptor]) and `arbigent replay`, which drives a recorded log with no
 * AI at all — so the poll interval, what counts as arrived, and how long a step may wait for it live
 * here rather than in either caller.
 *
 * What the two paths do not share is how generous the budget is, which is why there are two ways to
 * ask for one. Inside a run the AI is standing behind replay: a wait that ends too early costs a
 * fallback, so the budget is exactly what the recording had left ([inRunBudgetMillis]). `arbigent
 * replay` has nobody behind it and stops the run instead, so it keeps a floor under every wait
 * ([standaloneBudgetMillis]).
 */
internal object ArbigentReplayWait {
  /** A recorded gap can be pathological (a hiccup while recording); do not inherit it unbounded. */
  const val MAX_WAIT_MILLIS: Long = 60_000L

  /** Nothing recorded to derive a budget from, and a screen still on its way is the likely case. */
  const val MIN_WAIT_MILLIS: Long = 10_000L

  const val POLL_INTERVAL_MILLIS: Long = 500L

  /**
   * How long a step inside a scenario run may wait: what is left of [recordedGapMillis] after
   * [alreadySpentMillis], the time replay has spent since the previous step began.
   *
   * The recorded gap is the recording run's own time from one capture to the next, so it already
   * contains everything the app was given then, including the AI latency the recording paid and any
   * `Wait` the AI itself decided on. Replay is therefore never slower than the recording and never
   * faster than the app needed. The cap is applied to the recorded gap itself, so a pathological
   * recording does not become a minute of waiting plus whatever the previous action took.
   *
   * A null gap or a null elapsed time is what is not known, not zero: with no gap there is nothing
   * to derive a budget from and the result is [MIN_WAIT_MILLIS]; with no elapsed time there is
   * nothing to subtract and the result is the gap in full.
   */
  fun inRunBudgetMillis(recordedGapMillis: Long?, alreadySpentMillis: Long?): Long {
    val recordedGap = recordedGapMillis ?: return MIN_WAIT_MILLIS
    val budget = recordedGap.coerceAtMost(MAX_WAIT_MILLIS)
    val alreadySpent = alreadySpentMillis ?: return budget
    return (budget - alreadySpent).coerceAtLeast(0)
  }

  /**
   * How long a step of `arbigent replay` may wait: the recorded gap, never below
   * [MIN_WAIT_MILLIS] nor above [MAX_WAIT_MILLIS].
   *
   * A wait that ends too early here ends the whole replay, and there is no AI to take over from the
   * screen it stopped on, so a recording that happened to be fast does not get to make the replay
   * impatient.
   */
  fun standaloneBudgetMillis(recordedGapMillis: Long?): Long =
    (recordedGapMillis ?: MIN_WAIT_MILLIS)
      .coerceAtMost(MAX_WAIT_MILLIS)
      .coerceAtLeast(MIN_WAIT_MILLIS)

  /** How a wait ended. */
  sealed interface WaitResult {
    /** [isReady] held after [waitedMillis]. */
    data class Ready(val waitedMillis: Long) : WaitResult

    /** The screen was readable but never became what was asked for before the budget ran out. */
    object Exhausted : WaitResult

    /**
     * The hierarchy could not be read even once. A screen that is merely mid-transition answers at
     * least one poll, so this says the device could not be driven rather than that the target is
     * absent, and callers report it as a device failure instead of a divergence.
     */
    object Unreadable : WaitResult
  }

  /**
   * Polls until [isReady] holds for the element list, or until [budgetMillis] is spent.
   *
   * The screen is read once before the budget is consulted, so a step whose budget is already gone
   * still gets the chance to find what it is waiting for on a screen that is in fact ready.
   *
   * Waiting further for the element list to stop changing was tried and dropped: a screen with
   * anything animating, ticking or streaming never stops changing, so the wait ran to the budget
   * every time and a screen that was in fact ready was reported as one the target never reached.
   *
   * Elapsed time is counted in poll intervals rather than read from the clock, so the loop advances
   * with the suspending [delay] instead of spinning against a clock that the caller may be
   * controlling. The budget is checked between polls, and a poll is one blocking
   * [ArbigentDevice.elements] read, so a wait can overrun by at most one hierarchy dump (bounded by
   * the device's own retry policy). Interrupting a dump midway would leave Maestro's driver in an
   * undefined state, and a step captured a few seconds late is still a correct step.
   */
  suspend fun awaitReady(
    device: ArbigentDevice,
    budgetMillis: Long,
    isReady: (ArbigentElementList) -> Boolean,
  ): WaitResult {
    var readAtLeastOnce = false
    var waitedMillis = 0L
    while (true) {
      val elements = readElements(device)
      if (elements != null) {
        readAtLeastOnce = true
        if (isReady(elements)) return WaitResult.Ready(waitedMillis)
      }
      if (waitedMillis >= budgetMillis) break
      val pollMillis = POLL_INTERVAL_MILLIS.coerceAtMost(budgetMillis - waitedMillis)
      delay(pollMillis)
      waitedMillis += pollMillis
    }
    return if (readAtLeastOnce) WaitResult.Exhausted else WaitResult.Unreadable
  }

  private fun readElements(device: ArbigentDevice): ArbigentElementList? = try {
    device.elements()
  } catch (exception: Exception) {
    // Reading the hierarchy can fail while the screen is mid-transition, which is exactly the state
    // this waits out. Only a wait in which every single poll failed is treated as a broken device.
    arbigentDebugLog("Replay wait: could not read elements: $exception")
    null
  }
}
