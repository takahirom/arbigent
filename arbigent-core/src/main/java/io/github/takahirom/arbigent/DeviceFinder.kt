package io.github.takahirom.arbigent

import dadb.Dadb
import maestro.utils.TempFileHandler
import util.LocalSimulatorUtils

@ArbigentInternalApi
public fun fetchAvailableDevicesByOs(deviceType: ArbigentDeviceOs): List<ArbigentAvailableDevice> {
  return when (deviceType) {
    ArbigentDeviceOs.Android -> {
      Dadb.list().map { ArbigentAvailableDevice.Android(it) }
    }

    ArbigentDeviceOs.Ios -> {
      // LocalSimulatorUtils is now a class taking a TempFileHandler instead of an object.
      val simulators = LocalSimulatorUtils(TempFileHandler()).list()
        .devices
        .flatMap { runtime ->
          runtime.value
            .filter { it.isAvailable && it.state == "Booted" }
        }
        .map { ArbigentAvailableDevice.IOS(it) }
      val optedIn = isRealIosDeviceOptedIn()
      // Wake the CoreDevice tunnel (read-only) when the user opted in, or when there is no booted
      // simulator to fall back on so a lone connected iPhone still "just works".
      val realDevices = fetchConnectedIosRealDevices(wakeTunnels = optedIn || simulators.isEmpty())
      // Prefer a physical device only when the user has opted in (Apple team id or a specific
      // real-device id). Otherwise a booted simulator wins, keeping the common `--os=ios` dev flow
      // unchanged. connectDevice() picks the first entry.
      if (optedIn) realDevices + simulators else simulators + realDevices
    }

    else -> {
      listOf(ArbigentAvailableDevice.Web())
    }
  }
}

/**
 * Lists physical iPhones reachable over CoreDevice (`xcrun devicectl`), filtered to the ones with a
 * connected tunnel — the same criterion maestro uses. Returns empty (never throws) when devicectl
 * is unavailable or no device is connected, so simulator discovery keeps working without Xcode
 * command-line tools set up for real devices.
 *
 * CoreDevice only reports `tunnelState == connected` while a tunnel is actively held; a paired,
 * USB-connected iPhone otherwise lists as `disconnected` even though it is perfectly usable. When
 * the user has opted into real devices we first wake the tunnel with a read-only
 * `devicectl device info` (see [wakeIosRealDeviceTunnels]) so discovery is not flaky.
 */
@ArbigentInternalApi
public fun fetchConnectedIosRealDevices(
  wakeTunnels: Boolean = true,
  executor: ArbigentCommandExecutor = DefaultArbigentCommandExecutor(),
): List<ArbigentAvailableDevice.IosReal> {
  val deviceIdFilter = ArbigentIosRealDeviceSettings.resolvedDeviceId()
  return try {
    val localDevice = util.LocalIOSDevice()
    var devices = localDevice.listDeviceViaDeviceCtl()
    fun isConnected(device: util.DeviceCtlResponse.Device) =
      device.connectionProperties?.tunnelState.equals("connected", ignoreCase = true)
    fun matchesFilter(device: util.DeviceCtlResponse.Device) =
      deviceIdFilter == null || device.hardwareProperties?.udid == deviceIdFilter
    // The device we actually need (the configured one, or any device when none was specified) must
    // have a connected tunnel. Waking only when *nothing* is connected would strand a configured
    // UDID that is disconnected while some other iPhone happens to be connected.
    val satisfied = devices.any { isConnected(it) && matchesFilter(it) }
    if (!satisfied && wakeTunnels) {
      val toWake = devices.filter { matchesFilter(it) && !isConnected(it) }.mapNotNull { it.identifier }
      wakeIosRealDeviceTunnels(toWake, executor)
      devices = localDevice.listDeviceViaDeviceCtl()
    }
    devices
      .filter { it.connectionProperties?.tunnelState.equals("connected", ignoreCase = true) }
      .mapNotNull { device ->
        val identifier = device.identifier ?: return@mapNotNull null
        val udid = device.hardwareProperties?.udid ?: return@mapNotNull null
        if (deviceIdFilter != null && udid != deviceIdFilter) return@mapNotNull null
        ArbigentAvailableDevice.IosReal(
          coreDeviceIdentifier = identifier,
          hardwareUdid = udid,
          name = device.deviceProperties?.name ?: udid,
        )
      }
  } catch (e: Exception) {
    arbigentInfoLog("iOS real device discovery skipped: ${e.message}")
    emptyList()
  }
}

// Reads device info to establish the CoreDevice tunnel (read-only; no device state changes), so the
// subsequent `list devices` reports the device as connected. Failures are ignored — a device that
// stays disconnected simply won't be surfaced.
private fun wakeIosRealDeviceTunnels(identifiers: List<String>, executor: ArbigentCommandExecutor) {
  identifiers.forEach { identifier ->
    arbigentInfoLog("iOS real device: waking CoreDevice tunnel for a paired device")
    executor.execute(
      listOf("xcrun", "devicectl", "device", "info", "details", "--device", identifier),
      timeoutMs = 20_000,
    )
  }
}

private fun isRealIosDeviceOptedIn(env: (String) -> String? = System::getenv): Boolean {
  val config = ArbigentIosRealDeviceSettings.current
  if (!config.appleTeamId.isNullOrBlank()) return true
  if (!config.deviceId.isNullOrBlank()) return true
  if (!env(ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID).isNullOrBlank()) return true
  return !env(ArbigentIosRealDeviceSettings.ENV_DEVICE_ID).isNullOrBlank()
}
