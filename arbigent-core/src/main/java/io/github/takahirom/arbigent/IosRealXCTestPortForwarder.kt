package io.github.takahirom.arbigent

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bridges the host to the XCTest runner's HTTP server on a physical device.
 *
 * The maestro runner binds `127.0.0.1:<port>` on the DEVICE's loopback, and maestro ships no
 * forwarding code, so on a USB-connected iPhone the host's `127.0.0.1:<port>` never reaches it.
 * This forwarder spawns and supervises `iproxy` (libimobiledevice) to publish the device port on
 * the same host port, and ties its lifecycle to the arbigent session: it is killed on [close], via
 * a JVM shutdown hook, and it reaps `iproxy` processes that a *previous* arbigent run left holding
 * the port after crashing. Reaping is limited to processes we can prove we own (a pidfile records
 * both the iproxy and the owning arbigent, with start times to defeat PID reuse); an iproxy owned
 * by a still-running arbigent, or one arbigent never started, is never killed — the port is
 * reported as busy instead.
 */
public class IosRealXCTestPortForwarder(
  private val deviceUdid: String,
  private val port: Int,
  private val executor: ArbigentCommandExecutor = DefaultArbigentCommandExecutor(),
) : AutoCloseable {
  private var process: Process? = null
  private var shutdownHook: Thread? = null
  // The record this instance published, so close() only deletes the shared pidfile while it still
  // names us (never a record a newer instance rewrote in our place).
  @Volatile private var ownershipRecord: IproxyOwnershipRecord? = null

  private val ownershipRecordFile: File
    get() = File(File(System.getProperty("user.home"), ".arbigent/iproxy-locks"), "port-$port.pid")

  // Sibling lock file guarding every read/write/delete of the shared per-port pidfile, so
  // concurrent arbigent runs racing the same port can't clobber or half-observe each other's
  // record. The lock file itself is never a payload, so creating it is harmless.
  private val ownershipLockFile: File
    get() = File(ownershipRecordFile.parentFile, "port-$port.pid.lock")

  private fun <T> withOwnershipLock(block: () -> T): T =
    // Serialize same-JVM callers on a shared per-port monitor BEFORE touching the OS file lock:
    // a FileChannel lock is held by the whole JVM, so two threads (or two instances) in this
    // process locking the same file would throw OverlappingFileLockException. The monitor makes
    // same-JVM access sequential; the file lock still guards against other processes.
    synchronized(portMonitor(port)) {
      val lock = ownershipLockFile.apply { parentFile?.mkdirs() }
      RandomAccessFile(lock, "rw").use { raf ->
        raf.channel.lock().use { block() }
      }
    }

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
    // Register the shutdown hook and record ownership only after confirming iproxy stayed up; on any
    // failure below, close() removes the hook and record so we never leak a hook or a stale pidfile.
    val hook = Thread { started.destroyForcibly() }
    Runtime.getRuntime().addShutdownHook(hook)
    shutdownHook = hook
    try {
      // iproxy exits immediately if the port is taken or the device is gone; give it a moment and
      // confirm it is still alive before we rely on the tunnel.
      if (started.waitFor(1_000, TimeUnit.MILLISECONDS)) {
        val log = logFile.takeIf { it.exists() }?.readText().orEmpty()
        throw IllegalStateException(
          "iproxy exited immediately (port $port). It may be in use or the device may be " +
            "disconnected. Log: ${logFile.absolutePath}\n$log"
        )
      }
      writeOwnershipRecord(started.pid())
    } catch (t: Throwable) {
      close()
      throw t
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
    deleteOwnershipRecordIfOurs()
    ownershipRecord = null
  }

  // Delete the shared pidfile only if it still names the record we wrote. If start() failed before
  // publishing (ownershipRecord null) or a newer instance already rewrote it, we leave it intact so
  // a live forwarder never appears unowned.
  private fun deleteOwnershipRecordIfOurs() {
    // Read our published record under the same per-port lock that writeOwnershipRecord assigns it
    // under, so a close() racing an in-progress start() is serialized against the publish: either it
    // sees the record and deletes the matching pidfile, or it runs before the publish (when no file
    // exists yet) — it can never see a null record while the pidfile is already on disk.
    withOwnershipLock {
      val ours = ownershipRecord ?: return@withOwnershipLock
      if (readOwnershipRecord() == ours) {
        runCatching { ownershipRecordFile.delete() }
      }
    }
  }

  private fun ensureIproxyInstalled() {
    val which = executor.execute(listOf("which", "iproxy"))
    if (!which.isSuccess) throw IllegalStateException(iproxyMissingMessage())
  }

  /**
   * Reaps only `iproxy` processes that a crashed arbigent run left holding [port]. An iproxy owned
   * by a live arbigent, or one no arbigent pidfile claims, is treated as busy and reported rather
   * than killed.
   */
  private fun reapOrphans() {
    val listening = orphanIproxyPids(
      lsofOutput = executor.execute(
        listOf("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN", "-t")
      ).stdout,
      commandNameOf = { pid ->
        executor.execute(listOf("ps", "-p", pid, "-o", "comm=")).stdout.trim()
      },
    )
    // Read the record, decide, and clear it all under the lock so a concurrent instance can't
    // publish a fresh record between our decision and our delete (which would clobber it).
    withOwnershipLock {
      val record = readOwnershipRecord()
      val decision = reapDecision(
        port = port,
        listeningIproxyPids = listening,
        record = record,
        isOwnerAlive = ::isProcessAlive,
        iproxyStartOf = { pid -> processStartMillis(pid) },
      )
      when (decision) {
        is ReapDecision.Fail -> throw IllegalStateException("iOS real device: ${decision.reason}")
        is ReapDecision.Reap -> {
          decision.pids.forEach { pid ->
            arbigentInfoLog("iOS real device: reaping orphaned iproxy (pid $pid) from a crashed run holding port $port")
            executor.execute(listOf("kill", "-9", pid))
          }
          // Only clear the record we just validated as belonging to a dead owner; if another
          // instance rewrote it meanwhile it no longer matches and we leave it alone.
          if (decision.pids.isNotEmpty() && readOwnershipRecord() == record) {
            runCatching { ownershipRecordFile.delete() }
          }
        }
      }
    }
  }

  private fun writeOwnershipRecord(iproxyPid: Long) {
    val self = ProcessHandle.current()
    val record = IproxyOwnershipRecord(
      iproxyPid = iproxyPid,
      iproxyStart = processStartMillis(iproxyPid.toString()) ?: 0L,
      ownerPid = self.pid(),
      ownerStart = self.info().startInstant().map { it.toEpochMilli() }.orElse(0L),
    )
    // Publish atomically under the lock: write a sibling temp then rename into place, so a reader
    // never sees a half-written record and a concurrent writer can't interleave with ours. Assign
    // the in-memory ownershipRecord under the SAME lock as (and only after) the file publish, so a
    // close() serialized on the same per-port monitor never observes a null record while the
    // pidfile already exists — which would leave the pidfile orphaned.
    withOwnershipLock {
      val dir = ownershipRecordFile.parentFile.apply { mkdirs() }
      val tmp = File.createTempFile("port-$port", ".pid", dir)
      try {
        tmp.writeText(record.serialize())
        Files.move(
          tmp.toPath(), ownershipRecordFile.toPath(),
          StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
        )
        ownershipRecord = record
      } finally {
        tmp.delete()
      }
    }
  }

  private fun readOwnershipRecord(): IproxyOwnershipRecord? =
    ownershipRecordFile.takeIf { it.exists() }?.let { IproxyOwnershipRecord.parse(it.readText()) }

  private fun iproxyMissingMessage(): String =
    "iproxy is not installed. Real iOS devices need libimobiledevice to forward the XCTest port. " +
      "Install it with: brew install libimobiledevice"

  /** Ownership pidfile contents: the spawned iproxy and the arbigent that owns it, with start times. */
  public data class IproxyOwnershipRecord(
    val iproxyPid: Long,
    val iproxyStart: Long,
    val ownerPid: Long,
    val ownerStart: Long,
  ) {
    public fun serialize(): String = "$iproxyPid:$iproxyStart:$ownerPid:$ownerStart"

    public companion object {
      public fun parse(text: String): IproxyOwnershipRecord? {
        val parts = text.trim().split(":")
        if (parts.size != 4) return null
        val nums = parts.map { it.toLongOrNull() ?: return null }
        return IproxyOwnershipRecord(nums[0], nums[1], nums[2], nums[3])
      }
    }
  }

  public sealed interface ReapDecision {
    /** [pids] are orphaned iproxy processes safe to kill (may be empty). */
    public data class Reap(val pids: List<String>) : ReapDecision
    /** The port is legitimately busy; [reason] explains why nothing was killed. */
    public data class Fail(val reason: String) : ReapDecision
  }

  public companion object {
    // One monitor per port, shared across all forwarder instances in this JVM, so same-JVM callers
    // serialize before acquiring the per-port OS file lock (which is JVM-wide and would otherwise
    // throw OverlappingFileLockException). See [withOwnershipLock].
    private val portMonitors = ConcurrentHashMap<Int, Any>()

    private fun portMonitor(port: Int): Any = portMonitors.computeIfAbsent(port) { Any() }

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

    /**
     * Decides which listening `iproxy` processes may be reaped. Only a process the ownership
     * [record] claims (matching pid and start time) AND whose owning arbigent is dead is an orphan;
     * an unrecorded iproxy, or one owned by a live arbigent, makes the port busy.
     */
    public fun reapDecision(
      port: Int,
      listeningIproxyPids: List<String>,
      record: IproxyOwnershipRecord?,
      isOwnerAlive: (pid: Long, startMillis: Long) -> Boolean,
      iproxyStartOf: (pid: String) -> Long?,
    ): ReapDecision {
      if (listeningIproxyPids.isEmpty()) return ReapDecision.Reap(emptyList())
      for (pid in listeningIproxyPids) {
        val ownedByRecord = record != null &&
          record.iproxyPid.toString() == pid &&
          iproxyStartOf(pid) == record.iproxyStart
        if (!ownedByRecord) {
          return ReapDecision.Fail(
            "port $port is held by an iproxy (pid $pid) arbigent did not start. Stop it (kill $pid) " +
              "or configure a different port."
          )
        }
        if (isOwnerAlive(record!!.ownerPid, record.ownerStart)) {
          return ReapDecision.Fail(
            "port $port is in use by another running arbigent (pid ${record.ownerPid}). Finish that " +
              "run or configure a different port."
          )
        }
      }
      return ReapDecision.Reap(listeningIproxyPids)
    }

    private fun processStartMillis(pid: String): Long? =
      pid.toLongOrNull()?.let { p ->
        ProcessHandle.of(p).flatMap { it.info().startInstant() }.map { it.toEpochMilli() }.orElse(null)
      }

    /** Alive AND (start time matches, or is unavailable), so a reused PID is not mistaken for the owner. */
    private fun isProcessAlive(pid: Long, startMillis: Long): Boolean =
      ProcessHandle.of(pid).map { handle ->
        handle.isAlive && handle.info().startInstant().map { it.toEpochMilli() == startMillis }.orElse(true)
      }.orElse(false)
  }
}
