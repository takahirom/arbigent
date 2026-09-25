package io.github.takahirom.arbigent.test

import io.github.takahirom.arbigent.MaestroDevice
import maestro.DeviceInfo
import maestro.Driver
import maestro.Maestro
import maestro.TreeNode
import maestro.device.Platform
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MaestroDeviceReadTest {
  private class FakeDriver(var failContentDescriptor: Boolean = false) {
    var contentDescriptorCalls = 0

    val driver: Driver = Proxy.newProxyInstance(
      Driver::class.java.classLoader,
      arrayOf(Driver::class.java),
      InvocationHandler { _, method, _ ->
        when (method.name) {
          "name" -> "fake"
          "deviceInfo" -> DeviceInfo(Platform.ANDROID, 1080, 1920, 1080, 1920)
          "contentDescriptor" -> {
            contentDescriptorCalls++
            if (failContentDescriptor) throw IllegalStateException("device is gone")
            TreeNode(
              attributes = mutableMapOf("text" to "Settings", "bounds" to "[0,0][1080,1920]"),
              focused = true,
            )
          }

          else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
        }
      }
    ) as Driver
  }

  private fun device(fake: FakeDriver) = MaestroDevice(
    Maestro(fake.driver),
    screenshotsDir = createTempDirectory().toFile(),
  )

  @Test
  fun readsFetchTheHierarchyOnceWhenConnected() {
    val fake = FakeDriver()
    val device = device(fake)

    device.viewTreeString()
    device.elements()
    device.focusedElement()

    assertEquals(3, fake.contentDescriptorCalls)
  }

  @Test
  fun aFailedReadReconnects() {
    val fake = FakeDriver(failContentDescriptor = true)
    val device = device(fake)

    // No available device to reconnect to, so reaching the reconnect is what fails.
    val exception = assertFailsWith<IllegalStateException> { device.viewTreeString() }

    assertEquals("Cannot reconnect: no available device reference", exception.message)
  }
}
