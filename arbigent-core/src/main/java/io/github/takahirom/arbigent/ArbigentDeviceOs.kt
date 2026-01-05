package io.github.takahirom.arbigent

import dadb.Dadb
import ios.LocalIOSDevice
import ios.simctl.SimctlIOSDevice
import ios.xctest.XCTestIOSDevice
import maestro.Maestro
import maestro.drivers.AndroidDriver
import maestro.drivers.IOSDriver
import util.SimctlList
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.LocalXCTestInstaller

public enum class ArbigentDeviceOs {
  Android, Ios, Web;

  public fun isAndroid(): Boolean = this == Android
  public fun isIos(): Boolean = this == Ios
  public fun isWeb(): Boolean = this == Web
}

public sealed interface ArbigentAvailableDevice {
  public val deviceOs: ArbigentDeviceOs
  public val name: String

  // Do not use data class because dadb return true for equals
  public class Android(private val dadb: Dadb) : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Android
    override val name: String = dadb.toString()
    override fun connectToDevice(): ArbigentDevice {
      val driver = AndroidDriver(
        dadb,
      )
      val maestro = try {
        Maestro.android(
          driver
        )
      } catch (e: java.util.concurrent.TimeoutException) {
        driver.close()
        dadb.close()
        throw RuntimeException("Arbigent can not connect to device in time. The likely reason why we can't connect is that you have multiple instance of Arbigent like UI and CLI of Arbigent", e)
      } catch (e: Exception) {
        driver.close()
        dadb.close()
        throw e
      }
      return MaestroDevice(maestro, availableDevice = this)
    }
  }

  public class IOS(
    private val device: SimctlList.Device,
    private val port: Int = 8080,
    // local host
    private val host: String = "[::1]",
  ) : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Ios
    override val name: String = device.name
    override fun connectToDevice(): ArbigentDevice {
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
            LocalIOSDevice(
              deviceId = device.udid,
              xcTestDevice = xcTestDevice,
              simctlIOSDevice = SimctlIOSDevice(device.udid)
            )
          )
        ),
        availableDevice = this
      )
    }
  }

  public class RealIOS(
    private val deviceName: String,
    private val udid: String,
    private val port: Int = 6001,  // maestro-ios-device default port
    private val host: String = "[::1]",
  ) : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Ios
    override val name: String = deviceName
    override fun connectToDevice(): ArbigentDevice {
      // For real devices with maestro-ios-device daemon
      arbigentInfoLog("Connecting to real iOS device $deviceName ($udid) via maestro-ios-device on port $port")

      // Create a passthrough installer that connects to existing maestro-ios-device server
      // Based on Maestro PR #2856 approach for real device support
      val passThroughInstaller = object : xcuitest.installer.XCTestInstaller {
        override fun start(): XCTestClient {
          arbigentInfoLog("Connecting to existing maestro-ios-device XCTest server at $host:$port")
          return XCTestClient(host, port)
        }

        override fun uninstall(): Boolean {
          arbigentInfoLog("Skipping XCTest uninstall for real device (managed by maestro-ios-device)")
          return true
        }

        override fun close() {
          arbigentInfoLog("Closing connection to maestro-ios-device")
        }

        override fun isChannelAlive(): Boolean {
          // maestro-ios-device manages the channel, assume alive
          return true
        }

        override val preBuiltRunner: Boolean
          get() = true  // maestro-ios-device already has the runner built and installed
      }

      val xcTestDriverClient = XCTestDriverClient(
        installer = passThroughInstaller,
        client = XCTestClient(host, port),
      )

      val xcTestDevice = XCTestIOSDevice(
        deviceId = udid,
        client = xcTestDriverClient,
        getInstalledApps = { XCRunnerCLIUtils.listApps(udid) },
      )

      // For real devices, use the regular SimctlIOSDevice
      // Note: simctl operations will fail, but maestro-ios-device handles the actual device operations
      arbigentInfoLog("Note: simctl operations (clearState, addMedia, etc.) are not supported on real devices")
      val simctlDevice = SimctlIOSDevice(udid)

      return MaestroDevice(
        Maestro.ios(
          IOSDriver(
            LocalIOSDevice(
              deviceId = udid,
              xcTestDevice = xcTestDevice,
              simctlIOSDevice = simctlDevice
            )
          )
        ),
        availableDevice = this
      )
    }
  }

  public class Web : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Web
    override val name: String = "Chrome"
    public override fun connectToDevice(): ArbigentDevice {
      return MaestroDevice(
        Maestro.web(false, false),
        availableDevice = this
      )
    }
  }

  public class Fake : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Android
    override val name: String = "Fake"
    public override fun connectToDevice(): ArbigentDevice {
      // This is not called
      throw UnsupportedOperationException("Fake device is not supported")
    }
  }

  public fun connectToDevice(): ArbigentDevice
}
