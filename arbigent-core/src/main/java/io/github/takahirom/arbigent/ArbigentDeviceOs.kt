package io.github.takahirom.arbigent

import dadb.Dadb
import device.SimctlIOSDevice
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
      val simctlIOSDevice = SimctlIOSDevice(device.udid)
      
      val iOSDriverConfig = LocalXCTestInstaller.IOSDriverConfig(
        prebuiltRunner = true,
        sourceDirectory = System.getProperty("user.dir") + "/maestro-ios-xctest-runner",
        context = Context.CLI,
        snapshotKeyHonorModalViews = null
      )
      
      val iosDriver = IOSDriver(simctlIOSDevice)
      
      val maestro = try {
        Maestro.ios(iosDriver)
      } catch (e: java.util.concurrent.TimeoutException) {
        iosDriver.close()
        simctlIOSDevice.close()
        throw RuntimeException("Arbigent can not connect to iOS device in time. The likely reason why we can't connect is that you have multiple instance of Arbigent like UI and CLI of Arbigent", e)
      } catch (e: Exception) {
        iosDriver.close()
        simctlIOSDevice.close()
        throw e
      }
      return MaestroDevice(maestro)
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
