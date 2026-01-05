package io.github.takahirom.arbigent

import dadb.Dadb
import util.LocalSimulatorUtils
import util.SimctlList
import java.io.BufferedReader
import java.io.InputStreamReader

@ArbigentInternalApi
public fun fetchAvailableDevicesByOs(deviceType: ArbigentDeviceOs): List<ArbigentAvailableDevice> {
  return when (deviceType) {
    ArbigentDeviceOs.Android -> {
      Dadb.list().map { ArbigentAvailableDevice.Android(it) }
    }

    ArbigentDeviceOs.Ios -> {
      // Get simulators
      val simulators = LocalSimulatorUtils.list()
        .devices
        .flatMap { runtime ->
          runtime.value
            .filter { it.isAvailable && it.state == "Booted" }
        }
        .map { ArbigentAvailableDevice.IOS(it) }

      // Get real devices
      val realDevices = getRealIOSDevices()

      // Return combined list (real devices first, then simulators)
      realDevices + simulators
    }

    else -> {
      listOf(ArbigentAvailableDevice.Web())
    }
  }
}

@ArbigentInternalApi
private fun getRealIOSDevices(): List<ArbigentAvailableDevice> {
  return try {
    val process = ProcessBuilder("xcrun", "xctrace", "list", "devices")
      .redirectErrorStream(true)
      .start()

    val reader = BufferedReader(InputStreamReader(process.inputStream))
    val devices = mutableListOf<ArbigentAvailableDevice>()
    var inDevicesSection = false

    reader.forEachLine { line ->
      when {
        line.startsWith("== Devices ==") -> {
          inDevicesSection = true
        }
        line.startsWith("== Devices Offline ==") || line.startsWith("== Simulators ==") -> {
          inDevicesSection = false
        }
        inDevicesSection && line.isNotBlank() -> {
          // Parse real device line: "Qa test 15  (26.0.1) (00008130-001651342231001C)"
          val devicePattern = """^(.+?)\s+\((.+?)\)\s+\(([A-F0-9-]+)\)$""".toRegex()
          devicePattern.find(line.trim())?.let { match ->
            val (name, version, udid) = match.destructured
            devices.add(ArbigentAvailableDevice.RealIOS(
              deviceName = name.trim(),
              udid = udid
            ))
          }
        }
      }
    }

    process.waitFor()
    arbigentInfoLog("Found ${devices.size} real iOS device(s): ${devices.map { it.name }}")
    devices
  } catch (e: Exception) {
    arbigentInfoLog("Failed to detect real iOS devices: ${e.message}")
    emptyList()
  }
}