package io.github.takahirom.arbigent.test

import io.github.takahirom.arbigent.ArbigentAvailableDevice
import io.github.takahirom.arbigent.ArbigentTvCompatDevice
import io.github.takahirom.arbigent.MaestroDevice
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
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
  private class FakeDriver(
    var failContentDescriptor: Boolean = false,
    // Served in order, the last one repeating; defaults to a single on-screen, focused node.
    val trees: List<TreeNode> = listOf(node("Settings", "[0,0][1080,1920]")),
  ) {
    var contentDescriptorCalls = 0
    val pressedKeys = mutableListOf<KeyCode>()

    val driver: Driver = Proxy.newProxyInstance(
      Driver::class.java.classLoader,
      arrayOf(Driver::class.java),
      InvocationHandler { _, method, args ->
        when (method.name) {
          "pressKey" -> pressedKeys += args[0] as KeyCode

          "name" -> "fake"
          "deviceInfo" -> DeviceInfo(Platform.ANDROID, 1080, 1920, 1080, 1920)
          "contentDescriptor" -> {
            contentDescriptorCalls++
            if (failContentDescriptor) throw IllegalStateException("device is gone")
            trees[minOf(contentDescriptorCalls, trees.size) - 1]
          }

          else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
        }
      }
    ) as Driver
  }

  private companion object {
    fun node(text: String, bounds: String) = TreeNode(
      attributes = mutableMapOf("text" to text, "bounds" to bounds, "class" to "android.widget.TextView"),
      focused = true,
    )

    fun screen(focusedText: String) = TreeNode(
      attributes = mutableMapOf("bounds" to "[0,0][1080,1920]"),
      children = listOf("Top" to "[0,0][100,100]", "Bottom" to "[0,500][100,600]").map { (text, bounds) ->
        TreeNode(
          attributes = mutableMapOf("text" to text, "bounds" to bounds, "class" to "android.widget.TextView"),
          focused = text == focusedText,
        )
      },
    )
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
  fun readScreenTakesFocusFromTheFrameItsElementsCameFrom() {
    // The first frame is laid out off screen, so deriving elements from it fails and is retried.
    val fake = FakeDriver(trees = listOf(node("Old", "[2000,2000][3000,3000]"), node("New", "[0,0][1080,1920]")))
    val device = device(fake)

    val screen = device.readScreen(includeFocusedTree = true)

    assertEquals(2, fake.contentDescriptorCalls)
    assertTrue(screen.elements.elements.single().rawText.contains("New"))
    // The new frame spans the screen; the rejected one was 1000 wide.
    assertEquals(1080, screen.focusedElement?.width)
  }

  @Test
  fun movingFocusFetchesTheHierarchyOncePerKeyPress() {
    val fake = FakeDriver(trees = listOf(screen(focusedText = "Top"), screen(focusedText = "Top"), screen(focusedText = "Bottom")))
    val device = device(fake)

    device.moveFocusToElement(ArbigentTvCompatDevice.Selector.ByText("Bottom", 0))

    assertEquals(listOf(KeyCode.REMOTE_DOWN), fake.pressedKeys)
    // The connection check, then one fetch before the key press and one after it.
    assertEquals(3, fake.contentDescriptorCalls)
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
