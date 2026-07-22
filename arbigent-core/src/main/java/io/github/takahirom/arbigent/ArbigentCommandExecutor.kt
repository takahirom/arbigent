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
      // Guaranteed cleanup: kill the whole process tree, wait for it to actually die, and join the
      // drain threads — closing the pipes to unblock them if they are still stuck.
      if (timedOut) destroyTree(process)
      if (!process.waitFor(5, TimeUnit.SECONDS)) destroyTree(process)
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

  private fun destroyTree(process: Process) {
    runCatching { process.descendants().forEach { it.destroyForcibly() } }
    process.destroyForcibly()
  }

  private fun joinQuietly(thread: Thread, millis: Long) {
    try {
      thread.join(millis)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }
}
