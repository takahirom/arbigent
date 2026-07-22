package io.github.takahirom.arbigent

import java.util.concurrent.TimeUnit

/** Result of running an external command. */
public data class ArbigentCommandResult(
  public val exitCode: Int,
  public val stdout: String,
  public val stderr: String,
) {
  public val isSuccess: Boolean get() = exitCode == 0
}

/**
 * Thin abstraction over running host CLI tools (`xcrun devicectl`, `security`, `xcodebuild`,
 * `lsof`, ...). A single seam so the iOS real-device logic can be unit-tested with a fake executor
 * instead of shelling out.
 */
public interface ArbigentCommandExecutor {
  public fun execute(command: List<String>, timeoutMs: Long = 120_000): ArbigentCommandResult
}

/** Runs commands with [ProcessBuilder], capturing stdout/stderr and enforcing a timeout. */
public class DefaultArbigentCommandExecutor : ArbigentCommandExecutor {
  override fun execute(command: List<String>, timeoutMs: Long): ArbigentCommandResult {
    val process = ProcessBuilder(command)
      .redirectErrorStream(false)
      .start()
    // Drain both streams concurrently so a chatty tool can't deadlock on a full pipe buffer. Daemon
    // threads so a reader stuck on a pipe inherited by a surviving descendant can never keep the JVM
    // alive. Appends/reads on each StringBuilder are guarded so the final read is safe even if a
    // reader has not finished.
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val outThread = drainThread("arbigent-cmd-stdout", process.inputStream, stdout)
    val errThread = drainThread("arbigent-cmd-stderr", process.errorStream, stderr)
    outThread.start()
    errThread.start()

    var timedOut = false
    try {
      timedOut = !process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    } finally {
      // Guaranteed cleanup within a bounded ~5s budget: kill the whole process tree, then wait for
      // the process AND its descendants to actually die — re-enumerating descendants once so a child
      // spawned or reparented during teardown is also awaited — before joining the drain threads
      // (closing the pipes to unblock any that are still stuck). Anything still alive past the budget
      // is abandoned deliberately: readers are daemon threads, so we never block the caller
      // indefinitely on a wedged external tool.
      val deadline = System.currentTimeMillis() + TEARDOWN_BUDGET_MS
      var descendants = if (timedOut) destroyTree(process) else emptyList()
      if (!process.waitFor(remainingMs(deadline), TimeUnit.MILLISECONDS)) {
        descendants = destroyTree(process)
      }
      awaitHandles(descendants, deadline)
      awaitHandles(runCatching { process.descendants().toList() }.getOrDefault(emptyList()), deadline)
      joinQuietly(outThread, 2_000)
      joinQuietly(errThread, 2_000)
      if (outThread.isAlive || errThread.isAlive) {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        joinQuietly(outThread, 1_000)
        joinQuietly(errThread, 1_000)
      }
    }

    val out = synchronized(stdout) { stdout.toString() }
    val err = synchronized(stderr) { stderr.toString() }
    return if (timedOut) {
      ArbigentCommandResult(
        exitCode = -1,
        stdout = out,
        stderr = err + "\nCommand timed out after ${timeoutMs}ms: ${command.joinToString(" ")}",
      )
    } else {
      ArbigentCommandResult(process.exitValue(), out, err)
    }
  }

  private fun drainThread(name: String, stream: java.io.InputStream, sink: StringBuilder): Thread =
    Thread {
      runCatching {
        stream.bufferedReader().forEachLine { line -> synchronized(sink) { sink.appendLine(line) } }
      }
    }.apply { isDaemon = true; this.name = name }

  // Snapshot the descendant handles before killing so we can await them afterwards: once the parent
  // is destroyed its children are reparented and no longer enumerate as its descendants, but the
  // captured ProcessHandles stay valid for awaiting termination.
  //
  // Accepted tradeoff: a grandchild spawned by a descendant *between* this snapshot and the destroy
  // can escape teardown. Killing a process tree race-free needs OS process groups, which the JDK
  // Process API does not expose; this is intentionally bounded-effort teardown, not a guarantee.
  private fun destroyTree(process: Process): List<ProcessHandle> {
    val descendants = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
    descendants.forEach { runCatching { it.destroyForcibly() } }
    process.destroyForcibly()
    return descendants
  }

  private fun awaitHandles(handles: List<ProcessHandle>, deadline: Long) {
    handles.forEach { handle ->
      val timeLeft = remainingMs(deadline)
      if (timeLeft <= 0L) return
      runCatching { handle.onExit().get(timeLeft, TimeUnit.MILLISECONDS) }
    }
  }

  private fun remainingMs(deadline: Long): Long =
    (deadline - System.currentTimeMillis()).coerceAtLeast(0L)

  private fun joinQuietly(thread: Thread, millis: Long) {
    try {
      thread.join(millis)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private companion object {
    const val TEARDOWN_BUDGET_MS = 5_000L
  }
}
