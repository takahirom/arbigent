package io.github.takahirom.arbigent

import dadb.Dadb
import maestro.utils.TempFileHandler
import util.LocalSimulatorUtils

/**
 * The devices one run may choose from, split by how auto-selection treats them.
 *
 * [preferred] is the group auto-selection picks from; [fallback] is only consulted when [preferred]
 * is empty. The split exists because iOS has two kinds of device (booted simulators and paired
 * iPhones) and which one a bare `--os=ios` should mean depends on whether the user configured an
 * Apple team id. Keeping the split here — rather than a flat, ordered list — lets the selector
 * report "several devices, pick one" without treating a plugged-in iPhone as an ambiguity for
 * someone who only ever runs simulators.
 *
 * [discoveryFailure] is non-null when part of discovery threw. It is NOT the same as "no device":
 * a devicectl that is not set up must never be reported as "your iPhone is not connected", so the
 * selector attaches this as the cause of whatever error it raises.
 */
@ArbigentInternalApi
public class ArbigentDeviceCandidates(
  public val preferred: List<ArbigentAvailableDevice>,
  public val fallback: List<ArbigentAvailableDevice> = emptyList(),
  public val discoveryFailure: Throwable? = null,
  // Devices that are attached but cannot be driven (an `unauthorized` or `offline` adb serial).
  // They are never selectable and never count towards ambiguity, but naming them turns "device not
  // found" into "you still have to accept the USB-debugging prompt".
  public val unavailable: List<ArbigentUnavailableDevice> = emptyList(),
) {
  public val all: List<ArbigentAvailableDevice> get() = preferred + fallback
}

/** An attached device that discovery saw but cannot offer, with the state that disqualified it. */
@ArbigentInternalApi
public class ArbigentUnavailableDevice(
  public val deviceId: String,
  public val state: String,
)

/** Result of physical-iPhone discovery, keeping "found nothing" and "could not look" apart. */
@ArbigentInternalApi
public class ArbigentIosRealDeviceDiscovery(
  public val devices: List<ArbigentAvailableDevice.IosReal>,
  public val failure: Throwable? = null,
)

/**
 * Test seam for `xcrun devicectl list devices`. Production uses maestro's LocalIOSDevice; tests
 * substitute a canned list so selection and wake behavior are verifiable without an iPhone.
 */
@ArbigentInternalApi
public fun interface ArbigentIosRealDeviceLister {
  public fun list(): List<util.DeviceCtlResponse.Device>
}

/**
 * Lists the devices a CLI run may select from.
 *
 * @param requestedDeviceId the id the user asked for (`--device-id`), already resolved by the
 * caller. It is passed in rather than read from the environment here so that a stale environment
 * variable can never narrow another caller's device list, and so discovery can skip work that the
 * requested device does not need.
 */
@ArbigentInternalApi
public fun fetchDeviceCandidates(
  deviceOs: ArbigentDeviceOs,
  requestedDeviceId: String? = null,
  iosConfig: ArbigentIosRealDeviceConfiguration = ArbigentIosRealDeviceConfiguration(),
): ArbigentDeviceCandidates = when (deviceOs) {
  ArbigentDeviceOs.Android -> fetchAndroidDeviceCandidates()

  ArbigentDeviceOs.Web -> ArbigentDeviceCandidates(listOf(ArbigentAvailableDevice.Web()))

  ArbigentDeviceOs.Ios -> {
    val simulators = fetchBootedIosSimulators()
    if (requestedDeviceId != null && simulators.any { it.deviceId == requestedDeviceId }) {
      // The requested id is a booted simulator, so no physical-device lookup is needed at all. This
      // keeps a simulator run working on a machine where `xcrun devicectl` is absent or broken, and
      // costs nothing when it is present. A simulator UDID therefore wins over an identical
      // physical UDID — a collision that cannot occur in practice, as the two id formats differ.
      ArbigentDeviceCandidates(simulators)
    } else {
      val optedIn = ArbigentIosRealDeviceSettings.isOptedIn(iosConfig)
      val real = discoverIosRealDevices(
        // Wake the CoreDevice tunnel when a specific device was asked for, when the user opted into
        // physical devices, or when there is no booted simulator to fall back on.
        wakeTunnels = requestedDeviceId != null || optedIn || simulators.isEmpty(),
        config = iosConfig,
        requestedDeviceId = requestedDeviceId,
      )
      when {
        // An explicit id bypasses the preference rules entirely: it must match exactly, so both
        // kinds of device are equal candidates and there is nothing left to prefer.
        requestedDeviceId != null ->
          ArbigentDeviceCandidates(real.devices + simulators, discoveryFailure = real.failure)

        optedIn -> ArbigentDeviceCandidates(real.devices, simulators, real.failure)
        else -> ArbigentDeviceCandidates(simulators, real.devices, real.failure)
      }
    }
  }
}

@ArbigentInternalApi
public fun fetchAvailableDevicesByOs(
  deviceType: ArbigentDeviceOs,
  // The CLI auto-selects from [fetchDeviceCandidates]; this entry point exists for the UI, which
  // lets the user pick explicitly and so passes true to list every connected iOS device (booted
  // simulators and paired iPhones) at once.
  includeAllIosDevices: Boolean = false,
  // iOS real-device knobs (Apple team id / port), threaded explicitly from the caller and baked into
  // each discovered IosReal so connection reads them off the instance rather than a global.
  iosConfig: ArbigentIosRealDeviceConfiguration = ArbigentIosRealDeviceConfiguration(),
): List<ArbigentAvailableDevice> {
  if (deviceType == ArbigentDeviceOs.Ios && includeAllIosDevices) {
    // Interactive selection: surface both kinds. Always wake tunnels so a paired iPhone shows up
    // even when a simulator is also booted.
    return discoverIosRealDevices(wakeTunnels = true, config = iosConfig).devices +
      fetchBootedIosSimulators()
  }
  return fetchDeviceCandidates(deviceType, iosConfig = iosConfig).all
}

/** One line of `adb devices`: an attached serial and the state adb reports for it. */
@ArbigentInternalApi
public class ArbigentAdbDevice(
  public val serial: String,
  public val state: String,
) {
  /** Only `device` can actually be driven; `unauthorized`, `offline`, `bootloader` cannot. */
  public val isUsable: Boolean get() = state.equals("device", ignoreCase = true)
}

/**
 * Test seam for `adb devices`. Production shells out through [ArbigentCommandExecutor]; tests hand
 * back a canned list so the unauthorized/offline rules are verifiable without an emulator.
 */
@ArbigentInternalApi
public fun interface ArbigentAndroidDeviceLister {
  public fun list(): List<ArbigentAdbDevice>
}

/**
 * Lists Android devices, keeping usable ones apart from attached-but-unusable ones.
 *
 * `adb devices` is used rather than `Dadb.list()` because the latter opens a connection to every
 * attached device while enumerating, so a single `unauthorized` phone — the normal state of a
 * device whose USB-debugging prompt has not been accepted yet, and of an emulator that is still
 * booting — makes the whole listing throw and takes down a run that named a different, working
 * device. When the adb binary cannot be found or fails we fall back to `Dadb.list()`, which keeps
 * the direct-TCP port scan that dadb does when no adb server is reachable.
 */
@ArbigentInternalApi
public fun fetchAndroidDeviceCandidates(
  lister: ArbigentAndroidDeviceLister = ArbigentAndroidDeviceLister { listAdbDevices() },
): ArbigentDeviceCandidates {
  val listed = try {
    lister.list()
  } catch (e: Exception) {
    arbigentDebugLog("adb devices failed (${e.message}); falling back to dadb enumeration")
    return try {
      ArbigentDeviceCandidates(Dadb.list().map { ArbigentAvailableDevice.Android(it) })
    } catch (fallbackFailure: Exception) {
      ArbigentDeviceCandidates(emptyList(), discoveryFailure = fallbackFailure)
    }
  }
  return ArbigentDeviceCandidates(
    preferred = listed.filter { it.isUsable }.map { ArbigentAvailableDevice.Android(it.serial) },
    unavailable = listed.filterNot { it.isUsable }
      .map { ArbigentUnavailableDevice(it.serial, it.state) },
  )
}

// Throws when adb is missing or fails, so the caller can fall back rather than report "no device".
private fun listAdbDevices(
  executor: ArbigentCommandExecutor = DefaultArbigentCommandExecutor(),
): List<ArbigentAdbDevice> {
  val adb = adbExecutablePath() ?: throw IllegalStateException("adb was not found on PATH or under ANDROID_HOME")
  // `adb devices` starts the adb server if it is not running, exactly like Dadb.list() does.
  val result = executor.execute(listOf(adb, "devices"), timeoutMs = 30_000)
  if (!result.isSuccess) {
    throw IllegalStateException("`adb devices` exited with ${result.exitCode}: ${result.stderr.trim()}")
  }
  return parseAdbDevices(result.stdout)
}

// Output is a "List of devices attached" header followed by tab-separated "<serial>\t<state>"
// lines. The tab is what tells a device line apart from adb's daemon-startup chatter ("* daemon
// not running; starting now ..."), which is printed on the same stream.
@ArbigentInternalApi
public fun parseAdbDevices(output: String): List<ArbigentAdbDevice> = output.lineSequence()
  .mapNotNull { line ->
    if (!line.contains('\t')) return@mapNotNull null
    val serial = line.substringBefore('\t').trim()
    val state = line.substringAfter('\t').trim().substringBefore(' ')
    if (serial.isEmpty() || state.isEmpty()) return@mapNotNull null
    ArbigentAdbDevice(serial = serial, state = state)
  }
  .toList()

// Both file names are tried everywhere we look: a File lookup does not apply Windows PATHEXT, so
// an SDK that ships `adb.exe` is invisible if only `adb` is probed.
private val ADB_EXECUTABLE_NAMES = listOf("adb", "adb.exe")

private fun adbExecutablePath(): String? {
  val fromEnv = sequenceOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
    .filterNotNull()
    .flatMap { sdk -> ADB_EXECUTABLE_NAMES.asSequence().map { java.io.File(sdk, "platform-tools/$it") } }
    .firstOrNull { it.canExecute() }
  if (fromEnv != null) return fromEnv.absolutePath
  val onPath = System.getenv("PATH")
    ?.split(java.io.File.pathSeparator)
    ?.asSequence()
    ?.flatMap { dir -> ADB_EXECUTABLE_NAMES.asSequence().map { java.io.File(dir, it) } }
    ?.firstOrNull { it.canExecute() }
  return onPath?.absolutePath
}

// LocalSimulatorUtils is now a class taking a TempFileHandler instead of an object. TempFileHandler
// is Closeable and we own this instance, so scope it with use {} to clean up any temp files.
private fun fetchBootedIosSimulators(): List<ArbigentAvailableDevice.IOS> =
  TempFileHandler().use { tempFileHandler ->
    LocalSimulatorUtils(tempFileHandler).list()
      .devices
      .flatMap { runtime ->
        runtime.value
          .filter { it.isAvailable && it.state == "Booted" }
      }
      .map { ArbigentAvailableDevice.IOS(it) }
  }

/**
 * Lists physical iPhones reachable over CoreDevice (`xcrun devicectl`), filtered to the ones with a
 * connected tunnel — the same criterion maestro uses. Never throws: a devicectl that is unavailable
 * is reported through [ArbigentIosRealDeviceDiscovery.failure] so callers can tell "no iPhone is
 * connected" apart from "we could not look", instead of both surfacing as an empty list.
 *
 * CoreDevice only reports `tunnelState == connected` while a tunnel is actively held; a paired,
 * USB-connected iPhone otherwise lists as `disconnected` even though it is perfectly usable. When a
 * wake is allowed we first establish the tunnel with a read-only `devicectl device info` (see
 * [wakeIosRealDeviceTunnels]) so discovery is not flaky.
 */
@ArbigentInternalApi
public fun discoverIosRealDevices(
  wakeTunnels: Boolean = true,
  executor: ArbigentCommandExecutor = DefaultArbigentCommandExecutor(),
  config: ArbigentIosRealDeviceConfiguration = ArbigentIosRealDeviceConfiguration(),
  requestedDeviceId: String? = null,
  lister: ArbigentIosRealDeviceLister = ArbigentIosRealDeviceLister {
    util.LocalIOSDevice().listDeviceViaDeviceCtl()
  },
): ArbigentIosRealDeviceDiscovery {
  return try {
    var devices = lister.list()
    fun isConnected(device: util.DeviceCtlResponse.Device) =
      device.connectionProperties?.tunnelState.equals("connected", ignoreCase = true)
    fun matchesRequest(device: util.DeviceCtlResponse.Device) =
      requestedDeviceId == null || device.hardwareProperties?.udid == requestedDeviceId
    // When a specific device is requested we only need THAT device's tunnel up, so it is enough
    // that it is connected — waking only when *nothing* is connected would strand a requested UDID
    // that is disconnected while some other iPhone happens to be connected. With no request (the
    // UI's all-device list) every paired-but-disconnected phone should be surfaced, so we wake all
    // of them rather than stopping at the first connected one.
    val needsWake = if (requestedDeviceId != null) {
      devices.none { isConnected(it) && matchesRequest(it) }
    } else {
      devices.any { matchesRequest(it) && !isConnected(it) }
    }
    var wakeFailure: Throwable? = null
    if (needsWake && wakeTunnels) {
      val toWake = devices.filter { matchesRequest(it) && !isConnected(it) }.mapNotNull { it.identifier }
      // Each wake costs a devicectl round trip with a 20s ceiling, so skip the re-list entirely when
      // there is nothing to wake (e.g. the requested id belongs to no paired device).
      if (toWake.isNotEmpty()) {
        wakeFailure = wakeIosRealDeviceTunnels(toWake, executor)
        devices = lister.list()
      }
    }
    val found = devices
      .filter { it.connectionProperties?.tunnelState.equals("connected", ignoreCase = true) }
      .mapNotNull { device ->
        val identifier = device.identifier ?: return@mapNotNull null
        val udid = device.hardwareProperties?.udid ?: return@mapNotNull null
        if (requestedDeviceId != null && udid != requestedDeviceId) return@mapNotNull null
        ArbigentAvailableDevice.IosReal(
          coreDeviceIdentifier = identifier,
          hardwareUdid = udid,
          // Never the UDID: the name is shown next to the masked id, so using it as a fallback
          // would print the full hardware UDID that masking exists to keep out of logs.
          name = device.deviceProperties?.name ?: "iOS device",
          config = config,
        )
      }
    // A wake that failed is only reported when it left us without the device the user named: that is
    // the case where "no device with that id" would otherwise hide a broken devicectl. With no
    // requested id, a paired iPhone that is simply not plugged in never wakes, which is a normal
    // state rather than a discovery failure — reporting it would stop a simulator run for anyone
    // holding an old pairing.
    val requestedDeviceMissing = requestedDeviceId != null && found.isEmpty()
    ArbigentIosRealDeviceDiscovery(found, wakeFailure.takeIf { requestedDeviceMissing })
  } catch (e: Exception) {
    arbigentInfoLog("iOS real device discovery failed: ${e.message}")
    ArbigentIosRealDeviceDiscovery(emptyList(), e)
  }
}

// Reads device info to establish the CoreDevice tunnel (read-only; no device state changes), so the
// subsequent `list devices` reports the device as connected. Returns the first failure so the
// caller can tell "your iPhone is not connected" apart from "devicectl could not talk to it"; the
// wake is still attempted for every identifier, because one unreachable device must not stop the
// others from coming up.
private fun wakeIosRealDeviceTunnels(
  identifiers: List<String>,
  executor: ArbigentCommandExecutor,
): Throwable? {
  var failure: Throwable? = null
  identifiers.forEach { identifier ->
    arbigentInfoLog("iOS real device: waking CoreDevice tunnel for a paired device")
    val result = executor.execute(
      listOf("xcrun", "devicectl", "device", "info", "details", "--device", identifier),
      timeoutMs = 20_000,
    )
    if (!result.isSuccess && failure == null) {
      // The stderr of a timeout carries the command line, so this message can contain the CoreDevice
      // identifier. That is a pairing-local UUID, not the hardware UDID this project never prints.
      failure = IllegalStateException(
        "`xcrun devicectl device info` exited with ${result.exitCode} while establishing the " +
          "CoreDevice tunnel: ${result.stderr.trim().take(500)}"
      )
    }
  }
  return failure
}
