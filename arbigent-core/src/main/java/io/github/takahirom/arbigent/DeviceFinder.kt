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
      val realDevices = fetchConnectedIosRealDevices()
      // Prefer a physical device only when the user has opted in by configuring an Apple team id
      // (real devices need it to build a signed runner). Otherwise a booted simulator wins, keeping
      // the common `--os=ios` dev flow unchanged. connectDevice() picks the first entry.
      if (isRealIosDeviceOptedIn()) realDevices + simulators else simulators + realDevices
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
 */
@ArbigentInternalApi
public fun fetchConnectedIosRealDevices(): List<ArbigentAvailableDevice.IosReal> {
  val deviceIdFilter = ArbigentIosRealDeviceSettings.resolvedDeviceId()
  return try {
    util.LocalIOSDevice().listDeviceViaDeviceCtl()
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

private fun isRealIosDeviceOptedIn(env: (String) -> String? = System::getenv): Boolean {
  val config = ArbigentIosRealDeviceSettings.current
  if (!config.appleTeamId.isNullOrBlank()) return true
  return !env(ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID).isNullOrBlank()
}
