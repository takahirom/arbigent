package io.github.takahirom.arbigent.test

import io.github.takahirom.arbigent.ArbigentAvailableDevice
import io.github.takahirom.arbigent.MaestroDevice
import maestro.DeviceInfo
import maestro.Driver
import maestro.Maestro
import maestro.TreeNode
import maestro.device.Platform
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TakeScreenshotCommand
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

  private fun device(fake: FakeDriver, screenshotsDir: File = createTempDirectory().toFile()) = MaestroDevice(
    Maestro(fake.driver),
    screenshotsDir = screenshotsDir,
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
  fun readScreenFetchesTheHierarchyOnce() {
    val fake = FakeDriver()
    val device = device(fake)

    val screen = device.readScreen(includeFocusedTree = true)

    assertEquals(1, fake.contentDescriptorCalls)
    assertEquals(device.elements(), screen.elements)
    assertEquals(device.viewTreeString(), screen.uiTreeStrings)
    assertEquals(device.focusedTreeString(), screen.focusedTreeString)
    assertEquals(device.focusedElement(), screen.focusedElement)
  }

  @Test
  fun aScreenshotThatCannotBeWrittenDoesNotReconnect() {
    val notADirectory = File.createTempFile("screenshots", "")
    val device = device(FakeDriver(), screenshotsDir = notADirectory)

    val exception = assertFailsWith<Exception> {
      device.executeActions(listOf(MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand("shot"))))
    }

    assertTrue(exception.message.orEmpty().contains("Cannot reconnect").not(), "was: $exception")
  }

  @Test
  fun aFailedReadIsRetriedOnTheReconnectedDevice() {
    val broken = FakeDriver(failContentDescriptor = true)
    val replacement = FakeDriver()
    val device = MaestroDevice(
      Maestro(broken.driver),
      screenshotsDir = createTempDirectory().toFile(),
      availableDevice = ArbigentAvailableDevice.Fake(connect = { device(replacement) }),
    )

    val tree = device.viewTreeString()

    assertEquals(1, broken.contentDescriptorCalls)
    assertEquals(1, replacement.contentDescriptorCalls)
    assertTrue(tree.allTreeString.contains("Settings"), "was: $tree")
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
