package io.github.takahirom.arbigent

import dadb.Dadb
import maestro.Maestro
import maestro.drivers.AndroidDriver
import maestro.drivers.IOSDriver
import util.IOSDeviceType
import util.SimctlList
import xcuitest.installer.Context
import xcuitest.installer.IOSBuildProductsExtractor
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
    override suspend fun connectToDevice(): ArbigentDevice {
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
      return MaestroDevice(maestro)
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
    override suspend fun connectToDevice(): ArbigentDevice {
      // TODO: Add integration tests with iOS simulator - verify XCTestIOSDevice -> IOSDriver -> Maestro chain
      val iosDevice = createIOSDevice()
      var iosDriver: maestro.drivers.IOSDriver? = null
      var maestroCreated = false
      
      try {
        iosDriver = maestro.drivers.IOSDriver(iosDevice = iosDevice)
        val maestro = Maestro.ios(iosDriver)
        maestroCreated = true
        return MaestroDevice(maestro)
      } catch (e: java.util.concurrent.TimeoutException) {
        throw RuntimeException("Arbigent can not connect to iOS device in time. The likely reason why we can't connect is that you have multiple instance of Arbigent like UI and CLI of Arbigent", e)
      } catch (e: Exception) {
        throw RuntimeException("Failed to create iOS connection: ${e.message}", e)
      } finally {
        // Proper resource cleanup: close resources in reverse order if creation failed
        if (!maestroCreated) {
          iosDriver?.close()
          iosDevice.close()
        }
      }
    }
    
    private fun createIOSDevice(): device.IOSDevice {
      // Create the iOS device using the simplest approach available in maestro 2.0.0
      return ios.xctest.XCTestIOSDevice(
        deviceId = device.udid,
        client = createXCTestDriverClient(),
        getInstalledApps = { emptySet() }
      )
    }
    
    private fun createXCTestDriverClient(): xcuitest.XCTestDriverClient {
      // Create installer and client for XCTest communication
      val installer = xcuitest.installer.LocalXCTestInstaller(
        deviceId = device.udid,
        host = host,
        deviceType = util.IOSDeviceType.SIMULATOR,
        defaultPort = port,
        iOSDriverConfig = createIOSDriverConfig(),
        deviceController = createPlaceholderDevice()
      )
      
      return xcuitest.XCTestDriverClient(installer)
    }
    
    private fun createIOSDriverConfig(): xcuitest.installer.LocalXCTestInstaller.IOSDriverConfig {
      return xcuitest.installer.LocalXCTestInstaller.IOSDriverConfig(
        prebuiltRunner = true,
        sourceDirectory = System.getProperty("user.dir") + "/maestro-ios-xctest-runner",
        context = xcuitest.installer.Context.CLI,
        snapshotKeyHonorModalViews = null
      )
    }
    
    private fun createPlaceholderDevice(): device.IOSDevice {
      // ARCHITECTURAL NOTE: This placeholder resolves circular dependency in maestro 2.0.0
      // LocalXCTestInstaller requires IOSDevice but XCTestIOSDevice requires installer client
      // This temporary device is replaced by real XCTestIOSDevice after installer initialization
      // All methods throw UnsupportedOperationException to prevent accidental usage
      return object : device.IOSDevice {
        override val deviceId: String = device.udid
        override fun open() {}
        override fun close() {}
        
        // All other methods throw UnsupportedOperationException - this is a placeholder only
        override fun deviceInfo() = throw UnsupportedOperationException("Placeholder device")
        override fun viewHierarchy(excludeKeyboardElements: Boolean) = throw UnsupportedOperationException("Placeholder device")
        override fun tap(x: Int, y: Int) = throw UnsupportedOperationException("Placeholder device")
        override fun longPress(x: Int, y: Int, durationMs: Long) = throw UnsupportedOperationException("Placeholder device")
        override fun pressKey(name: String) = throw UnsupportedOperationException("Placeholder device")
        override fun pressButton(name: String) = throw UnsupportedOperationException("Placeholder device")
        override fun scroll(xStart: Double, yStart: Double, xEnd: Double, yEnd: Double, duration: Double) = throw UnsupportedOperationException("Placeholder device")
        override fun input(text: String) = throw UnsupportedOperationException("Placeholder device")
        override fun install(stream: java.io.InputStream) = throw UnsupportedOperationException("Placeholder device")
        override fun uninstall(id: String) = throw UnsupportedOperationException("Placeholder device")
        override fun clearAppState(id: String) = throw UnsupportedOperationException("Placeholder device")
        override fun clearKeychain() = throw UnsupportedOperationException("Placeholder device")
        override fun launch(id: String, launchArguments: Map<String, Any>) = throw UnsupportedOperationException("Placeholder device")
        override fun stop(id: String) = throw UnsupportedOperationException("Placeholder device")
        override fun isKeyboardVisible() = throw UnsupportedOperationException("Placeholder device")
        override fun openLink(link: String) = throw UnsupportedOperationException("Placeholder device")
        override fun takeScreenshot(out: okio.Sink, compressed: Boolean) = throw UnsupportedOperationException("Placeholder device")
        override fun startScreenRecording(out: okio.Sink) = throw UnsupportedOperationException("Placeholder device")
        override fun addMedia(path: String) = throw UnsupportedOperationException("Placeholder device")
        override fun setLocation(latitude: Double, longitude: Double) = throw UnsupportedOperationException("Placeholder device")
        override fun setOrientation(orientation: String) = throw UnsupportedOperationException("Placeholder device")
        override fun isShutdown() = throw UnsupportedOperationException("Placeholder device")
        override fun isScreenStatic() = throw UnsupportedOperationException("Placeholder device")
        override fun setPermissions(id: String, permissions: Map<String, String>) = throw UnsupportedOperationException("Placeholder device")
        override fun eraseText(charactersToErase: Int) = throw UnsupportedOperationException("Placeholder device")
      }
    }
  }

  public class Web : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Web
    override val name: String = "Chrome"
    public override suspend fun connectToDevice(): ArbigentDevice {
      return MaestroDevice(
        Maestro.web(false, false)
      )
    }
  }

  public class Fake : ArbigentAvailableDevice {
    override val deviceOs: ArbigentDeviceOs = ArbigentDeviceOs.Android
    override val name: String = "Fake"
    public override suspend fun connectToDevice(): ArbigentDevice {
      // This is not called
      throw UnsupportedOperationException("Fake device is not supported")
    }
  }

  public suspend fun connectToDevice(): ArbigentDevice
}
