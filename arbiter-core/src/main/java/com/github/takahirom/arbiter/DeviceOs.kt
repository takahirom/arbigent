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

enum class DeviceOs {
  Android,
  iOS;

  fun isAndroid() = this == Android
  fun isIOS() = this == iOS
}

sealed interface AvailableDevice {
  val deviceOs: DeviceOs
  val name: String

  // Do not use data class because dadb return true for equals
  class Android(val dadb: Dadb) : AvailableDevice {
    override val deviceOs: DeviceOs = DeviceOs.Android
    override val name: String = dadb.toString()
    override fun connectToDevice(): ArbiterDevice {
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
      return MaestroDevice(maestro)
    }
  }

  class IOS(val device: SimctlList.Device) : AvailableDevice {
    override val deviceOs: DeviceOs = DeviceOs.iOS
    override val name: String = device.name
    override fun connectToDevice(): ArbiterDevice {
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
      return MaestroDevice(
        Maestro.ios(
          IOSDriver(
            xcTestDevice
          )
        )
      )
    }
  }

  fun connectToDevice(): ArbiterDevice
}
