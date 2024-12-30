package com.github.takahirom.arbiter

import dadb.Dadb
import ios.xctest.XCTestIOSDevice
import maestro.Maestro
import maestro.drivers.AndroidDriver
import maestro.drivers.IOSDriver
import util.SimctlList
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.LocalXCTestInstaller

enum class DeviceType {
  Android,
  iOS;
  fun isAndroid() = this == Android
  fun isIOS() = this == iOS
}

sealed interface AvailableDevice {
  val deviceType: DeviceType
  val name: String
  data class Android(val dadb: Dadb) : AvailableDevice {
    override val deviceType: DeviceType = DeviceType.Android
    override val name: String = dadb.toString()
  }
  data class IOS(val device: SimctlList.Device) : AvailableDevice {
    override val deviceType: DeviceType = DeviceType.iOS
    override val name: String = device.name
  }
}

fun connectToDevice(
  availableDevice: AvailableDevice
): MaestroDevice {
  val deviceType = availableDevice.deviceType
  return when (deviceType) {
    DeviceType.Android -> {
      val dadb = (availableDevice as? AvailableDevice.Android)?.dadb
        ?: throw IllegalStateException("No device selected")
      val driver = AndroidDriver(
        dadb,
      )
      val maestro = try {
        Maestro.android(
          driver
        )
      } catch (e: Exception) {
        driver.close()
        dadb.close()
        throw e
      }
      MaestroDevice(maestro)
    }

    DeviceType.iOS -> {
      val device = (availableDevice as? AvailableDevice.IOS)?.device
        ?: throw IllegalStateException("No device selected")
      val port = 8080
      val host = "[::1]"

      val xcTestInstaller = LocalXCTestInstaller(
        deviceId = device.udid, // Use the device's UDID
        // local host
        host = host,
        defaultPort = port,
        enableXCTestOutputFileLogging = true,
      )

      val xcTestDriverClient = XCTestDriverClient(
        installer = xcTestInstaller,
        client = XCTestClient(host, port), // Use the same host and port as above
      )

      val xcTestDevice = XCTestIOSDevice(
        deviceId = device.udid,
        client = xcTestDriverClient,
        getInstalledApps = { XCRunnerCLIUtils.listApps(device.udid) },
      )
      MaestroDevice(
        Maestro.ios(
          IOSDriver(
            xcTestDevice
          )
        )
      )
    }
  }
}
