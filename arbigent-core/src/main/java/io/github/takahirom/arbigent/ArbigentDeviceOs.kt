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
      val serial = dadb.toString()
      // AndroidDriver/Maestro opens and manages its own connection via byId(serial), so the
      // Dadb we were handed from Dadb.list() is no longer needed once we have the serial.
      dadb.close()
      val connection = AndroidDeviceConnection.byId(serial)
        ?: throw RuntimeException("Arbigent could not open an AndroidDeviceConnection for device: $serial")
      val driver = AndroidDriver(
        connection,
      )
      val maestro = try {
        Maestro.android(
          driver
        )
      } catch (e: java.util.concurrent.TimeoutException) {
        driver.close()
        throw RuntimeException("Arbigent can not connect to device in time. The likely reason why we can't connect is that you have multiple instance of Arbigent like UI and CLI of Arbigent", e)
      } catch (e: Exception) {
        driver.close()
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
        warmUpIosDevice(maestro)
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
  }

  /**
   * Physical iPhone driven over XCTest, discovered via `xcrun devicectl`.
   *
   * Unlike a simulator, a real device needs three things maestro's consumable jars do not provide
   * (see the PR-B research): a runner re-signed with the user's Apple team (built on demand by
   * [IosRealDriverProducts]), `iproxy` port forwarding from host to device
   * ([IosRealXCTestPortForwarder]), and a working app-lifecycle controller
   * ([ArbigentDevicectlIOSDevice]). Everything else — the XCTest HTTP protocol, UI interaction —
   * is identical to the simulator path once the runner port is reachable.
   *
   * @param coreDeviceIdentifier CoreDevice identifier used with `xcrun devicectl --device`.
   * @param hardwareUdid hardware UDID used for xcodebuild's `-destination id=` and iproxy.
   */
  public class IosReal(
    private val coreDeviceIdentifier: String,
    private val hardwareUdid: String,
    override val name: String,
  ) : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Ios

    // First 8 chars of the UDID: enough to point at a device in a message without ever printing the
    // full hardware UDID. When several candidates share this prefix, use [maskedUdidLabels] instead
    // so the shown prefix is extended until unique.
    public val maskedUdid: String get() = hardwareUdid.take(8) + "…"

    override fun connectToDevice(): ArbigentDevice {
      val config = ArbigentIosRealDeviceSettings.current
      val host = "127.0.0.1"
      val port = ArbigentIosRealDeviceSettings.resolvedPort()
      val teamId = IosCodeSigningTeamResolver.resolve(config)
      val productsDir = IosRealDriverProducts(teamId = teamId, deviceUdid = hardwareUdid)
        .resolveBuildProductsDir()

      val forwarder = if (config.autoStartIproxy) {
        IosRealXCTestPortForwarder(deviceUdid = hardwareUdid, port = port).also { it.start() }
      } else {
        null
      }

      // Hoisted so a warm-up failure can close the maestro session (and its XCTest driver) as well
      // as the forwarder; both must be released exactly once on the failure path.
      var maestro: Maestro? = null
      try {
        val tempFileHandler = TempFileHandler()
        // Our devicectl-backed controller fills maestro's stubbed real-device lifecycle methods.
        val deviceController = ArbigentDevicectlIOSDevice(coreDeviceIdentifier)

        val xcTestInstaller = LocalXCTestInstaller(
          deviceId = hardwareUdid,
          host = host,
          deviceType = IOSDeviceType.REAL,
          defaultPort = port,
          reinstallDriver = true,
          iOSDriverConfig = IOSDriverConfig(
            prebuiltRunner = false,
            // For REAL the installer reads the locally-built, user-signed products from this path
            // (unlike SIMULATOR, which loads a bundled classpath driver).
            sourceDirectory = productsDir.absolutePath,
            context = Context.CLI,
            snapshotKeyHonorModalViews = null,
          ),
          deviceController = deviceController,
          tempFileHandler = tempFileHandler,
          logsDir = xctestLogsDir(),
        )

        val xcTestDriverClient = XCTestDriverClient(
          installer = xcTestInstaller,
          client = XCTestClient(host, port),
          reinstallDriver = true,
        )
        val xcRunnerCLIUtils = XCRunnerCLIUtils(tempFileHandler)

        val xcTestDevice = XCTestIOSDevice(
          deviceId = hardwareUdid,
          client = xcTestDriverClient,
          getInstalledApps = { xcRunnerCLIUtils.listApps(hardwareUdid) },
        )

        maestro = Maestro.ios(
          IOSDriver(
            LocalIOSDevice(
              deviceId = hardwareUdid,
              xcTestDevice = xcTestDevice,
              deviceController = deviceController,
              insights = NoopInsights,
            )
          )
        )
        warmUpIosDevice(maestro)
        return MaestroDevice(
          maestro,
          availableDevice = this,
          onClose = { forwarder?.close() },
        )
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        maestro?.close()
        forwarder?.close()
        throw e
      } catch (e: Exception) {
        maestro?.close()
        forwarder?.close()
        throw e
      }
    }

    public companion object {
      /**
       * Labels each candidate with the shortest masked UDID prefix that is unique among
       * [candidates], so colliding 8-char prefixes are disambiguated by revealing a few more
       * characters. The full UDID is never printed: the prefix is capped one character short of the
       * shortest UDID, and if a collision somehow survives that cap a positional #index is appended
       * instead of more UDID characters.
       */
      public fun maskedUdidLabels(candidates: List<IosReal>): List<String> {
        val maxPrefix = (candidates.minOfOrNull { it.hardwareUdid.length } ?: 1).minus(1).coerceAtLeast(1)
        fun collides(udid: String, length: Int): Boolean =
          candidates.count { it.hardwareUdid.take(length) == udid.take(length) } > 1
        return candidates.mapIndexed { index, candidate ->
          val udid = candidate.hardwareUdid
          var length = 8.coerceAtMost(maxPrefix)
          while (length < maxPrefix && collides(udid, length)) length++
          val prefix = udid.take(length) + "…"
          if (collides(udid, length)) "$prefix #${index + 1}" else prefix
        }
      }
    }
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

// A freshly booted iOS simulator (or a real device whose runner just launched) is still doing
// post-launch work, and hitting it with the first scenario action tends to crash the XCTest
// runner. Warm it up first: poll the view hierarchy until the runner responds (this also starts
// the runner), then let it settle, so the first real interaction runs on a warm, stable device.
internal fun warmUpIosDevice(maestro: Maestro) {
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
  // Let post-launch work settle before the first scenario interaction.
  Thread.sleep(settleMs)
  arbigentInfoLog("iOS warm-up done")
}

// LocalXCTestInstaller writes XCTest runner logs here, replacing the removed
// enableXCTestOutputFileLogging flag.
internal fun xctestLogsDir(): File =
  File(ArbigentFiles.parentDir, "xctest-logs").apply { mkdirs() }
