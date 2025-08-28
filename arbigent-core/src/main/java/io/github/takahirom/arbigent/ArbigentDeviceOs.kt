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
      // LIMITATION: iOS connection currently fails due to Maestro 2.0.0 IOSBuildProductsExtractor 
      // requiring XCTest resources in classpath. This is a known architectural constraint.
      // Integration testing requires resolving Maestro's resource loading mechanism.
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
      val customResourcesPath = System.getProperty("arbigent.maestro.resources.path") 
          ?: System.getenv("MAESTRO_RESOURCES_PATH")
      
      return if (customResourcesPath != null) {
          // Custom resources path
          val resourceDir = java.io.File(customResourcesPath)
          if (!resourceDir.exists()) {
              throw IllegalStateException("Maestro resources directory not found: $customResourcesPath")
          }
          
          val requiredFiles = listOf("maestro-driver-ios.zip", "maestro-driver-iosUITests-Runner.zip")
          requiredFiles.forEach { fileName ->
              if (!java.io.File(resourceDir, fileName).exists()) {
                  throw IllegalStateException("Required resource file not found: $fileName in $customResourcesPath")
              }
          }
          
          xcuitest.installer.LocalXCTestInstaller.IOSDriverConfig(
              prebuiltRunner = true,
              sourceDirectory = customResourcesPath,
              context = xcuitest.installer.Context.CLI,
              snapshotKeyHonorModalViews = null
          )
      } else {
          // Default: build on demand
          xcuitest.installer.LocalXCTestInstaller.IOSDriverConfig(
              prebuiltRunner = false,
              sourceDirectory = "driver-iPhoneSimulator",
              context = xcuitest.installer.Context.CLI,
              snapshotKeyHonorModalViews = null
          )
      }
    }
    
    private fun createPlaceholderDevice(): device.IOSDevice {
      // VALIDATION: This placeholder device should NEVER be used for actual operations.
      // It exists solely to resolve circular dependencies in Maestro 2.0.0 initialization.
      // Any operation call indicates architectural misuse.
      return object : device.IOSDevice {
        override val deviceId: String = device.udid
        override fun open() {}
        override fun close() {}
        
        // All operations must fail with clear error indicating misuse
        private val error = { operation: String -> 
          throw IllegalStateException("ARCHITECTURAL ERROR: Placeholder device used for operation '$operation'. This indicates incorrect iOS initialization flow.")
        }
        override fun deviceInfo() = error("deviceInfo")
        override fun viewHierarchy(excludeKeyboardElements: Boolean) = error("viewHierarchy")
        override fun tap(x: Int, y: Int) = error("tap")
        override fun longPress(x: Int, y: Int, durationMs: Long) = error("longPress")
        override fun pressKey(name: String) = error("pressKey")
        override fun pressButton(name: String) = error("pressButton")
        override fun scroll(xStart: Double, yStart: Double, xEnd: Double, yEnd: Double, duration: Double) = error("scroll")
        override fun input(text: String) = error("input")
        override fun install(stream: java.io.InputStream) = error("install")
        override fun uninstall(id: String) = error("uninstall")
        override fun clearAppState(id: String) = error("clearAppState")
        override fun clearKeychain() = error("clearKeychain")
        override fun launch(id: String, launchArguments: Map<String, Any>) = error("launch")
        override fun stop(id: String) = error("stop")
        override fun isKeyboardVisible() = error("isKeyboardVisible")
        override fun openLink(link: String) = error("openLink")
        override fun takeScreenshot(out: okio.Sink, compressed: Boolean) = error("takeScreenshot")
        override fun startScreenRecording(out: okio.Sink) = error("startScreenRecording")
        override fun addMedia(path: String) = error("addMedia")
        override fun setLocation(latitude: Double, longitude: Double) = error("setLocation")
        override fun setOrientation(orientation: String) = error("setOrientation")
        override fun isShutdown() = error("isShutdown")
        override fun isScreenStatic() = error("isScreenStatic")
        override fun setPermissions(id: String, permissions: Map<String, String>) = error("setPermissions")
        override fun eraseText(charactersToErase: Int) = error("eraseText")
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
