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
    // Drain both streams concurrently so a chatty tool can't deadlock on a full pipe buffer.
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val outThread = Thread { process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }
    val errThread = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
    outThread.start()
    errThread.start()
    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!finished) {
      process.destroyForcibly()
      outThread.join(1_000)
      errThread.join(1_000)
      return ArbigentCommandResult(
        exitCode = -1,
        stdout = stdout.toString(),
        stderr = stderr.toString() + "\nCommand timed out after ${timeoutMs}ms: ${command.joinToString(" ")}",
      )
    }
    outThread.join(1_000)
    errThread.join(1_000)
    return ArbigentCommandResult(process.exitValue(), stdout.toString(), stderr.toString())
  }
}
