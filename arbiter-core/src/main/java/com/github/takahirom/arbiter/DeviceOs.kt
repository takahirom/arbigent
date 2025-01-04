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

public enum class DeviceOs {
  Android,
  iOS;

  public fun isAndroid(): Boolean = this == Android
  public fun isIOS(): Boolean = this == iOS
}

public sealed interface AvailableDevice {
  public val deviceOs: DeviceOs
  public val name: String

  // Do not use data class because dadb return true for equals
  public class Android(private val dadb: Dadb) : AvailableDevice {
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

  public class IOS(
    private val device: SimctlList.Device,
    private val port: Int = 8080,
    // local host
    private val host: String = "[::1]",
  ) : AvailableDevice {
    override val deviceOs: DeviceOs = DeviceOs.iOS
    override val name: String = device.name
    override fun connectToDevice(): ArbiterDevice {
      val port = port
      val host = host

      val xcTestInstaller = LocalXCTestInstaller(
        deviceId = device.udid, // Use the device's UDID
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

  public fun connectToDevice(): ArbiterDevice
}
