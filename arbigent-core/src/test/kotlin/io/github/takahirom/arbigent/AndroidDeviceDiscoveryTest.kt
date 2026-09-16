package io.github.takahirom.arbigent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An `unauthorized` or `offline` serial used to take down the whole Android listing, because
 * enumeration opened a connection to every attached device. These pin the tolerant behavior:
 * such a device is reported, never selected, and never blocks a run that named a working one.
 */
@OptIn(ArbigentInternalApi::class)
class AndroidDeviceDiscoveryTest {
  private fun lister(vararg devices: Pair<String, String>) =
    ArbigentAndroidDeviceLister { devices.map { ArbigentAdbDevice(it.first, it.second) } }

  @Test
  fun `an unauthorized device does not hide a usable one`() {
    val candidates = fetchAndroidDeviceCandidates(
      lister("emulator-5554" to "device", "emulator-5556" to "unauthorized")
    )

    assertEquals(listOf("emulator-5554"), candidates.preferred.map { it.deviceId })
    assertEquals(listOf("emulator-5556"), candidates.unavailable.map { it.deviceId })
  }

  // The usable device is the only candidate, so this must auto-select rather than report ambiguity.
  @Test
  fun `an unusable device does not count towards ambiguity`() {
    val candidates = fetchAndroidDeviceCandidates(
      lister("emulator-5554" to "device", "emulator-5556" to "offline")
    )

    val chosen = selectArbigentDevice(ArbigentDeviceOs.Android, candidates)

    assertEquals("emulator-5554", chosen.deviceId)
  }

  @Test
  fun `requesting an unusable device explains why it cannot be used`() {
    val candidates = fetchAndroidDeviceCandidates(
      lister("emulator-5554" to "device", "emulator-5556" to "unauthorized")
    )

    val error = runCatching {
      selectArbigentDevice(
        ArbigentDeviceOs.Android,
        candidates,
        ArbigentRequestedDevice("emulator-5556", "--device-id"),
      )
    }.exceptionOrNull()

    val message = (error as ArbigentDeviceSelectionException).message.orEmpty()
    assertTrue(message.contains("attached but not usable"), message)
    assertTrue(message.contains("unauthorized"), message)
  }

  @Test
  fun `an ambiguity error names the unusable devices too`() {
    val candidates = fetchAndroidDeviceCandidates(
      lister("emulator-5554" to "device", "emulator-5556" to "device", "emulator-5558" to "offline")
    )

    val message = runCatching { selectArbigentDevice(ArbigentDeviceOs.Android, candidates) }
      .exceptionOrNull()?.message.orEmpty()

    assertTrue(message.contains("Available: emulator-5554, emulator-5556"), message)
    assertTrue(message.contains("emulator-5558 (offline)"), message)
  }

  @Test
  fun `adb output is parsed into serials and states`() {
    val parsed = parseAdbDevices(
      """
      * daemon not running; starting now at tcp:5037
      * daemon started successfully
      List of devices attached
      emulator-5554	device
      emulator-5556	unauthorized
      192.168.1.5:5555	offline

      """.trimIndent()
    )

    assertEquals(listOf("emulator-5554", "emulator-5556", "192.168.1.5:5555"), parsed.map { it.serial })
    assertEquals(listOf("device", "unauthorized", "offline"), parsed.map { it.state })
    assertTrue(parsed.first().isUsable)
    assertTrue(!parsed.last().isUsable)
  }
}
