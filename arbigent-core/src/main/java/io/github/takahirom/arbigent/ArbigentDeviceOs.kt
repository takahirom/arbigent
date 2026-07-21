package io.github.takahirom.arbigent

import dadb.Dadb
import kotlinx.coroutines.runBlocking
import device.SimctlIOSDevice
import ios.LocalIOSDevice
import ios.xctest.XCTestIOSDevice
import maestro.Maestro
import maestro.android.AndroidDeviceConnection
import maestro.drivers.AndroidDriver
import maestro.drivers.IOSDriver
import maestro.utils.NoopInsights
import maestro.utils.TempFileHandler
import util.IOSDeviceType
import util.SimctlList
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.Context
import xcuitest.installer.LocalXCTestInstaller
import xcuitest.installer.LocalXCTestInstaller.IOSDriverConfig
import java.io.File

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
      // Maestro's AndroidDeviceConnection refactor (#3372) made AndroidDriver take an
      // AndroidDeviceConnection instead of a raw Dadb. byId() selects the same device by
      // serial that Dadb.list() gave us, so this keeps behavior identical.
      val connection = AndroidDeviceConnection.byId(dadb.toString())
        ?: throw RuntimeException("Arbigent could not open an AndroidDeviceConnection for device: $dadb")
      val driver = AndroidDriver(
        connection,
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
    // Maestro 2.x's FlyingFox runner binds to 127.0.0.1 (IPv4 only), so [::1] is refused.
    private val host: String = "127.0.0.1",
  ) : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Ios
    override val name: String = device.name
    override fun connectToDevice(): ArbigentDevice {
      val port = port
      val host = host

      // Maestro's iOS driver refactor split simctl control into a device.SimctlIOSDevice
      // built from a shared TempFileHandler, and LocalXCTestInstaller now takes an explicit
      // IOSDriverConfig plus that device controller. This wires up the simulator path with
      // the same host/port/UDID as before; XCTest output now goes to a logs dir rather than
      // the removed enableXCTestOutputFileLogging flag.
      val tempFileHandler = TempFileHandler()
      val deviceController = SimctlIOSDevice(
        deviceId = device.udid,
        tempFileHandler = tempFileHandler,
      )

      val xcTestInstaller = LocalXCTestInstaller(
        deviceId = device.udid, // Use the device's UDID
        host = host,
        deviceType = IOSDeviceType.SIMULATOR,
        defaultPort = port,
        reinstallDriver = true,
        iOSDriverConfig = IOSDriverConfig(
          prebuiltRunner = false,
          sourceDirectory = "driver-iPhoneSimulator",
          context = Context.CLI,
          snapshotKeyHonorModalViews = null,
        ),
        deviceController = deviceController,
        tempFileHandler = tempFileHandler,
        logsDir = xctestLogsDir(),
      )

      val xcTestDriverClient = XCTestDriverClient(
        installer = xcTestInstaller,
        client = XCTestClient(host, port), // Use the same host and port as above
        reinstallDriver = true,
      )
      val xcRunnerCLIUtils = XCRunnerCLIUtils(tempFileHandler)

      val xcTestDevice = XCTestIOSDevice(
        deviceId = device.udid,
        client = xcTestDriverClient,
        getInstalledApps = { xcRunnerCLIUtils.listApps(device.udid) },
      )

      val maestro = Maestro.ios(
        IOSDriver(
          LocalIOSDevice(
            deviceId = device.udid,
            xcTestDevice = xcTestDevice,
            deviceController = deviceController,
            insights = NoopInsights,
          )
        )
      )
      try {
        warmUp(maestro)
        return MaestroDevice(
          maestro,
          availableDevice = this
        )
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        maestro.close()
        throw e
      } catch (e: Exception) {
        maestro.close()
        throw e
      }
    }

    // A freshly booted iOS simulator is still doing post-boot work (e.g. Data
    // Migration), and hitting it with the first scenario action tends to crash the
    // XCTest runner. Warm it up first: poll the view hierarchy until the runner
    // responds (this also starts the runner), then let it settle, so the first real
    // interaction runs on a warm, stable device.
    private fun warmUp(maestro: Maestro) {
      val warmUpTimeoutMs = 60_000L
      val settleMs = 10_000L
      val pollIntervalMs = 2_000L
      val start = System.currentTimeMillis()
      var responsive = false
      while (System.currentTimeMillis() - start < warmUpTimeoutMs) {
        try {
          runBlocking { maestro.viewHierarchy() }
          responsive = true
          break
        } catch (e: Exception) {
          arbigentInfoLog("iOS warm-up: device not responsive yet, retrying: ${e.message}")
          Thread.sleep(pollIntervalMs)
        }
      }
      if (!responsive) {
        arbigentInfoLog("iOS warm-up: device did not become responsive within ${warmUpTimeoutMs}ms; continuing anyway")
        return
      }
      // Let post-boot work settle before the first scenario interaction.
      Thread.sleep(settleMs)
      arbigentInfoLog("iOS warm-up done")
    }

    // LocalXCTestInstaller writes XCTest runner logs here, replacing the removed
    // enableXCTestOutputFileLogging flag.
    private fun xctestLogsDir(): File =
      File(ArbigentFiles.parentDir, "xctest-logs").apply { mkdirs() }
  }

  public class Web : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Web
    override val name: String = "Chrome"
    public override fun connectToDevice(): ArbigentDevice {
      return MaestroDevice(
        // Maestro.web() gained a third arg (custom Chrome binary path); null keeps the default.
        Maestro.web(false, false, null),
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
