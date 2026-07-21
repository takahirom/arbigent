package io.github.takahirom.arbigent

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bridges the host to the XCTest runner's HTTP server on a physical device.
 *
 * The maestro runner binds `127.0.0.1:<port>` on the DEVICE's loopback, and maestro ships no
 * forwarding code, so on a USB-connected iPhone the host's `127.0.0.1:<port>` never reaches it.
 * This forwarder spawns and supervises `iproxy` (libimobiledevice) to publish the device port on
 * the same host port, and ties its lifecycle to the arbigent session: it is killed on [close], via
 * a JVM shutdown hook, and it proactively reaps orphaned `iproxy` processes left holding the port by
 * a previously crashed run.
 */
public class IosRealXCTestPortForwarder(
  private val deviceUdid: String,
  private val port: Int,
  private val executor: ArbigentCommandExecutor = DefaultArbigentCommandExecutor(),
) : AutoCloseable {
  private var process: Process? = null
  private var shutdownHook: Thread? = null

  public fun start() {
    ensureIproxyInstalled()
    reapOrphans()

    val logFile = File(ArbigentFiles.parentDir, "iproxy-$port.log").apply { parentFile?.mkdirs() }
    val builder = ProcessBuilder(
      "iproxy", "--udid", deviceUdid, "$port:$port"
    )
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
    val started = try {
      builder.start()
    } catch (e: java.io.IOException) {
      throw IllegalStateException(iproxyMissingMessage(), e)
    }
    process = started
    val hook = Thread { started.destroyForcibly() }
    Runtime.getRuntime().addShutdownHook(hook)
    shutdownHook = hook

    // iproxy exits immediately if the port is taken or the device is gone; give it a moment and
    // confirm it is still alive before we rely on the tunnel.
    if (started.waitFor(1_000, TimeUnit.MILLISECONDS)) {
      val log = logFile.takeIf { it.exists() }?.readText().orEmpty()
      throw IllegalStateException(
        "iproxy exited immediately (port $port). It may be in use or the device may be " +
          "disconnected. Log: ${logFile.absolutePath}\n$log"
      )
    }
    arbigentInfoLog("iOS real device: iproxy forwarding host 127.0.0.1:$port to device $port")
  }

  override fun close() {
    process?.destroyForcibly()
    process = null
    shutdownHook?.let {
      runCatching { Runtime.getRuntime().removeShutdownHook(it) }
    }
    shutdownHook = null
  }

  private fun ensureIproxyInstalled() {
    val which = executor.execute(listOf("which", "iproxy"))
    if (!which.isSuccess) throw IllegalStateException(iproxyMissingMessage())
  }

  /** Kills only `iproxy` processes still listening on [port] from a previous crashed run. */
  private fun reapOrphans() {
    val pids = orphanIproxyPids(
      lsofOutput = executor.execute(
        listOf("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN", "-t")
      ).stdout,
      commandNameOf = { pid ->
        executor.execute(listOf("ps", "-p", pid, "-o", "comm=")).stdout.trim()
      },
    )
    pids.forEach { pid ->
      arbigentInfoLog("iOS real device: reaping orphaned iproxy (pid $pid) holding port $port")
      executor.execute(listOf("kill", "-9", pid))
    }
  }

  private fun iproxyMissingMessage(): String =
    "iproxy is not installed. Real iOS devices need libimobiledevice to forward the XCTest port. " +
      "Install it with: brew install libimobiledevice"

  public companion object {
    /**
     * Filters [lsofOutput] (newline-separated PIDs) to those whose process command name is
     * `iproxy`, so we never kill an unrelated process that happens to hold the port.
     */
    public fun orphanIproxyPids(lsofOutput: String, commandNameOf: (String) -> String): List<String> =
      lsofOutput.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        .distinct()
        .filter { commandNameOf(it).contains("iproxy") }
        .toList()
  }
}
