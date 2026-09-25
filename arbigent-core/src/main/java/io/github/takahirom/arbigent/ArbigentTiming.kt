package io.github.takahirom.arbigent

/**
 * Logs how long [block] took as `[timing] <label> <ms>ms`, so a run's non-AI time (device reads,
 * actions, settle waits, initializers, connection) can be broken down from arbigent.log.
 */
public inline fun <T> arbigentTimed(label: String, block: () -> T): T {
  val start = System.nanoTime()
  try {
    return block()
  } finally {
    val elapsedMs = (System.nanoTime() - start) / 1_000_000
    arbigentDebugLog("[timing] $label ${elapsedMs}ms")
  }
}
