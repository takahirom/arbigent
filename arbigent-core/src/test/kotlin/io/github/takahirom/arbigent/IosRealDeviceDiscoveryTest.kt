package io.github.takahirom.arbigent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosRealDeviceDiscoveryTest {
  private fun device(identifier: String, udid: String, tunnelState: String, name: String = "iPhone") =
    util.DeviceCtlResponse.Device(
      identifier = identifier,
      deviceProperties = util.DeviceCtlResponse.DeviceProperties(name = name, osVersionNumber = "18.0"),
      hardwareProperties = util.DeviceCtlResponse.HardwareProperties(udid = udid),
      connectionProperties = util.DeviceCtlResponse.ConnectionProperties(tunnelState = tunnelState),
    )

  private class RecordingExecutor : ArbigentCommandExecutor {
    val commands = mutableListOf<List<String>>()
    override fun execute(command: List<String>, timeoutMs: Long): ArbigentCommandResult {
      commands += command
      return ArbigentCommandResult(exitCode = 0, stdout = "", stderr = "")
    }
  }

  @Test
  fun connectedDeviceIsReturnedWithoutWaking() {
    val executor = RecordingExecutor()
    val result = discoverIosRealDevices(
      executor = executor,
      lister = { listOf(device("core-1", "UDID-1", "connected")) },
    )

    assertEquals(listOf("UDID-1"), result.devices.map { it.deviceId })
    assertNull(result.failure)
    assertEquals(emptyList(), executor.commands)
  }

  // A requested device that never comes up must not look like "no such device" when the reason is a
  // devicectl that could not talk to it.
  @Test
  fun aFailedWakeOfTheRequestedDeviceIsReportedAsADiscoveryFailure() {
    val executor = object : ArbigentCommandExecutor {
      override fun execute(command: List<String>, timeoutMs: Long) =
        ArbigentCommandResult(exitCode = 1, stdout = "", stderr = "no connection to the device")
    }

    val result = discoverIosRealDevices(
      executor = executor,
      requestedDeviceId = "UDID-1",
      lister = { listOf(device("core-1", "UDID-1", "disconnected")) },
    )

    assertEquals(emptyList(), result.devices.map { it.deviceId })
    val failure = assertNotNull(result.failure)
    assertTrue(failure.message.orEmpty().contains("exited with 1"), failure.message.orEmpty())
    assertTrue(failure.message.orEmpty().contains("no connection to the device"), failure.message.orEmpty())
  }

  // A paired iPhone that is simply not plugged in never wakes. That is a normal state, so it must not
  // be reported as a failure — doing so would stop a simulator run for anyone holding an old pairing.
  @Test
  fun aFailedWakeIsNotAFailureWhenNoDeviceWasRequested() {
    val executor = object : ArbigentCommandExecutor {
      override fun execute(command: List<String>, timeoutMs: Long) =
        ArbigentCommandResult(exitCode = 1, stdout = "", stderr = "no connection to the device")
    }

    val result = discoverIosRealDevices(
      executor = executor,
      lister = { listOf(device("core-1", "UDID-1", "disconnected")) },
    )

    assertEquals(emptyList(), result.devices.map { it.deviceId })
    assertNull(result.failure)
  }

  // Each wake is a devicectl round trip with a 20s ceiling, so only the requested device may be woken.
  @Test
  fun onlyTheRequestedDeviceIsWoken() {
    val executor = RecordingExecutor()
    var listCalls = 0
    val result = discoverIosRealDevices(
      executor = executor,
      requestedDeviceId = "UDID-2",
      lister = {
        listCalls++
        listOf(
          device("core-1", "UDID-1", "disconnected"),
          device("core-2", "UDID-2", if (listCalls == 1) "disconnected" else "connected"),
        )
      },
    )

    assertEquals(listOf("UDID-2"), result.devices.map { it.deviceId })
    assertEquals(1, executor.commands.size)
    assertTrue(executor.commands.single().containsAll(listOf("--device", "core-2")), "${executor.commands}")
  }

  // A requested id belonging to no paired iPhone (e.g. a simulator UDID) must cost nothing.
  @Test
  fun anUnknownRequestedIdWakesNothing() {
    val executor = RecordingExecutor()
    var listCalls = 0
    val result = discoverIosRealDevices(
      executor = executor,
      requestedDeviceId = "SIMULATOR-UDID",
      lister = {
        listCalls++
        listOf(device("core-1", "UDID-1", "disconnected"))
      },
    )

    assertEquals(emptyList(), result.devices.map { it.deviceId })
    assertEquals(emptyList(), executor.commands)
    assertEquals(1, listCalls)
  }

  @Test
  fun aRequestedDeviceThatIsGoneIsNotSubstitutedByAnotherConnectedOne() {
    val result = discoverIosRealDevices(
      executor = RecordingExecutor(),
      requestedDeviceId = "UDID-2",
      lister = { listOf(device("core-1", "UDID-1", "connected")) },
    )

    assertEquals(emptyList(), result.devices.map { it.deviceId })
    assertNull(result.failure)
  }

  // "we could not look" must stay distinguishable from "nothing is connected".
  @Test
  fun aListingFailureIsReportedRatherThanLookingLikeZeroDevices() {
    val result = discoverIosRealDevices(
      executor = RecordingExecutor(),
      lister = { throw IllegalStateException("xcrun: command not found") },
    )

    assertEquals(emptyList(), result.devices)
    assertNotNull(result.failure)
    assertEquals("xcrun: command not found", result.failure?.message)
  }

  // Discovery applies no implicit filter: which device to use now arrives as an explicit argument,
  // so a leftover export of the superseded variable can no longer narrow this list (which is what
  // also silently narrowed the UI's device picker).
  @Test
  fun withNoRequestEveryConnectedDeviceIsReturned() {
    val result = discoverIosRealDevices(
      executor = RecordingExecutor(),
      lister = {
        listOf(
          device("core-1", "UDID-1", "connected"),
          device("core-2", "UDID-2", "connected"),
        )
      },
    )

    assertEquals(listOf("UDID-1", "UDID-2"), result.devices.map { it.deviceId })
  }

  // Waking only when *nothing* is connected would strand the requested device behind an unrelated
  // iPhone that happens to hold a tunnel.
  @Test
  fun aDisconnectedRequestedDeviceIsWokenEvenWhileAnotherIsConnected() {
    val executor = RecordingExecutor()
    var listCalls = 0
    val result = discoverIosRealDevices(
      executor = executor,
      requestedDeviceId = "UDID-2",
      lister = {
        listCalls++
        listOf(
          device("core-1", "UDID-1", "connected"),
          device("core-2", "UDID-2", if (listCalls == 1) "disconnected" else "connected"),
        )
      },
    )

    assertEquals(listOf("UDID-2"), result.devices.map { it.deviceId })
    assertTrue(executor.commands.single().containsAll(listOf("--device", "core-2")), "${executor.commands}")
  }

  // The UI lists devices on a background refresh, where a 20s wake per device is not acceptable.
  @Test
  fun wakingCanBeDisabledSoOnlyAlreadyConnectedDevicesAreReported() {
    val executor = RecordingExecutor()
    val result = discoverIosRealDevices(
      wakeTunnels = false,
      executor = executor,
      lister = {
        listOf(
          device("core-1", "UDID-1", "connected"),
          device("core-2", "UDID-2", "disconnected"),
        )
      },
    )

    assertEquals(listOf("UDID-1"), result.devices.map { it.deviceId })
    assertEquals(emptyList(), executor.commands)
  }

  // The name is displayed next to the masked id, so it must never fall back to the hardware UDID.
  @Test
  fun aDeviceWithNoReportedNameDoesNotUseItsUdidAsTheName() {
    val result = discoverIosRealDevices(
      executor = RecordingExecutor(),
      lister = {
        listOf(
          util.DeviceCtlResponse.Device(
            identifier = "core-1",
            deviceProperties = null,
            hardwareProperties = util.DeviceCtlResponse.HardwareProperties(udid = "00008110-0123456789ABCDEF"),
            connectionProperties = util.DeviceCtlResponse.ConnectionProperties(tunnelState = "connected"),
          )
        )
      },
    )

    val device = result.devices.single()
    assertEquals("00008110-0123456789ABCDEF", device.deviceId)
    assertTrue(!device.name.contains(device.deviceId), device.name)
    assertEquals(listOf("iOS device (00008110…)"), describeArbigentDevices(result.devices))
  }
}
