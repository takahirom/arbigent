package io.github.takahirom.arbigent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeviceSelectionTest {
  private fun android(serial: String) =
    ArbigentAvailableDevice.Fake(name = serial, deviceId = serial, deviceOs = ArbigentDeviceOs.Android)

  private fun requested(id: String, source: String = "--device-id") =
    ArbigentRequestedDevice(id = id, source = source)

  // --- explicit id -----------------------------------------------------------------------

  @Test
  fun requestedId_picksTheMatchingDeviceRegardlessOfOrder() {
    val target = android("emulator-5556")
    val forward = ArbigentDeviceCandidates(listOf(android("emulator-5554"), target))
    val reversed = ArbigentDeviceCandidates(listOf(target, android("emulator-5554")))

    assertSame(target, selectArbigentDevice(ArbigentDeviceOs.Android, forward, requested("emulator-5556")))
    assertSame(target, selectArbigentDevice(ArbigentDeviceOs.Android, reversed, requested("emulator-5556")))
  }

  // The regression this whole option exists to prevent: a requested device that is not attached must
  // never silently fall through to the single other device that happens to be there.
  @Test
  fun requestedId_neverFallsBackToAnotherDevice() {
    val candidates = ArbigentDeviceCandidates(listOf(android("emulator-5554")))

    val error = assertFailsWith<ArbigentDeviceSelectionException> {
      selectArbigentDevice(ArbigentDeviceOs.Android, candidates, requested("emulator-5556", "ARBIGENT_DEVICE_ID"))
    }

    assertTrue(error.message!!.contains("emulator-5556"), error.message)
    assertTrue(error.message!!.contains("emulator-5554"), error.message)
    // The source must be named, so an id inherited from the environment is fixable without guessing.
    assertTrue(error.message!!.contains("ARBIGENT_DEVICE_ID"), error.message)
  }

  @Test
  fun requestedId_matchesAcrossThePreferenceGroups() {
    val fallbackDevice = android("emulator-5556")
    val candidates = ArbigentDeviceCandidates(
      preferred = listOf(android("emulator-5554")),
      fallback = listOf(fallbackDevice),
    )

    assertSame(
      fallbackDevice,
      selectArbigentDevice(ArbigentDeviceOs.Android, candidates, requested("emulator-5556")),
    )
  }

  @Test
  fun requestedId_isMaskedForIosSoAHardwareUdidIsNeverPrintedInFull() {
    val udid = "00008110-0123456789ABCDEF"
    val error = assertFailsWith<ArbigentDeviceSelectionException> {
      selectArbigentDevice(ArbigentDeviceOs.Ios, ArbigentDeviceCandidates(emptyList()), requested(udid))
    }

    assertTrue(error.message!!.contains("00008110…"), error.message)
    assertTrue(!error.message!!.contains(udid), error.message)
  }

  @Test
  fun requestedId_isRejectedForWeb() {
    val error = assertFailsWith<ArbigentDeviceSelectionException> {
      selectArbigentDevice(
        ArbigentDeviceOs.Web,
        ArbigentDeviceCandidates(listOf(ArbigentAvailableDevice.Web())),
        requested("emulator-5554"),
      )
    }

    assertTrue(error.message!!.contains("--os=web"), error.message)
  }

  // --- auto-selection --------------------------------------------------------------------

  @Test
  fun noRequest_picksTheOnlyPreferredDevice() {
    val only = android("emulator-5554")

    assertSame(only, selectArbigentDevice(ArbigentDeviceOs.Android, ArbigentDeviceCandidates(listOf(only))))
  }

  @Test
  fun noRequest_refusesToGuessBetweenSeveralDevices() {
    val candidates = ArbigentDeviceCandidates(listOf(android("emulator-5554"), android("emulator-5556")))

    val error = assertFailsWith<ArbigentDeviceSelectionException> {
      selectArbigentDevice(ArbigentDeviceOs.Android, candidates)
    }

    assertTrue(error.message!!.contains("emulator-5554"), error.message)
    assertTrue(error.message!!.contains("emulator-5556"), error.message)
    assertTrue(error.message!!.contains("--device-id"), error.message)
  }

  // A plugged-in iPhone must not turn "one booted simulator" into an ambiguity for someone who only
  // runs simulators, which is why candidates are grouped rather than flattened.
  @Test
  fun noRequest_ignoresTheFallbackGroupWhenThePreferredGroupDecides() {
    val preferred = android("emulator-5554")
    val candidates = ArbigentDeviceCandidates(
      preferred = listOf(preferred),
      fallback = listOf(android("emulator-5556")),
    )

    assertSame(preferred, selectArbigentDevice(ArbigentDeviceOs.Android, candidates))
  }

  @Test
  fun noRequest_fallsBackOnlyWhenThePreferredGroupIsEmpty() {
    val fallbackDevice = android("emulator-5556")
    val candidates = ArbigentDeviceCandidates(preferred = emptyList(), fallback = listOf(fallbackDevice))

    assertSame(fallbackDevice, selectArbigentDevice(ArbigentDeviceOs.Android, candidates))
  }

  // "devicectl is not installed" must not be reported as "no iPhone is connected".
  @Test
  fun discoveryFailure_isReportedAndKeptAsTheCause() {
    val failure = IllegalStateException("xcrun devicectl is unavailable")
    val candidates = ArbigentDeviceCandidates(emptyList(), discoveryFailure = failure)

    val error = assertFailsWith<ArbigentDeviceSelectionException> {
      selectArbigentDevice(ArbigentDeviceOs.Ios, candidates)
    }

    assertTrue(error.message!!.contains("xcrun devicectl is unavailable"), error.message)
    assertSame(failure, error.cause)
  }

  // With a team id set, a paired iPhone is the preferred group and a booted simulator only the
  // fallback. A devicectl that failed to enumerate must not be read as "no iPhone", or the run
  // silently happens on the simulator.
  @Test
  fun noRequest_discoveryFailureInThePreferredGroupDoesNotFallBack() {
    val failure = IllegalStateException("xcrun devicectl is unavailable")
    val candidates = ArbigentDeviceCandidates(
      preferred = emptyList(),
      fallback = listOf(android("emulator-5554")),
      discoveryFailure = failure,
    )

    val error = assertFailsWith<ArbigentDeviceSelectionException> {
      selectArbigentDevice(ArbigentDeviceOs.Ios, candidates)
    }

    assertTrue(error.message!!.contains("did not complete"), error.message)
    assertTrue(error.message!!.contains("xcrun devicectl is unavailable"), error.message)
    assertSame(failure, error.cause)
  }

  @Test
  fun noRequest_fallsBackWhenDiscoveryDidComplete() {
    val fallbackDevice = android("emulator-5556")
    val candidates = ArbigentDeviceCandidates(preferred = emptyList(), fallback = listOf(fallbackDevice))

    assertSame(fallbackDevice, selectArbigentDevice(ArbigentDeviceOs.Ios, candidates))
  }

  @Test
  fun noRequest_withNoCandidatesFails() {
    val error = assertFailsWith<ArbigentDeviceSelectionException> {
      selectArbigentDevice(ArbigentDeviceOs.Android, ArbigentDeviceCandidates(emptyList()))
    }

    assertTrue(error.message!!.contains("No available Android device"), error.message)
  }

  // --- display ---------------------------------------------------------------------------

  // Discovery has no name for an iPhone that does not report one. The name is printed verbatim next
  // to the masked id, so a name that is the UDID would leak the very value masking protects.
  @Test
  fun describe_neverPrintsAHardwareUdidUsedAsTheName() {
    val udid = "00008110-0123456789ABCDEF"
    val real = ArbigentAvailableDevice.IosReal(
      coreDeviceIdentifier = "core-1",
      hardwareUdid = udid,
      name = udid,
    )

    val labels = describeArbigentDevices(listOf(real))

    assertEquals(listOf("00008110…"), labels)
    assertTrue(labels.none { it.contains(udid) }, labels.toString())
  }

  @Test
  fun describe_showsSerialsInFullAndHardwareUdidsMasked() {
    val real = ArbigentAvailableDevice.IosReal(
      coreDeviceIdentifier = "core-1",
      hardwareUdid = "00008110-0123456789ABCDEF",
      name = "Test iPhone",
    )

    val labels = describeArbigentDevices(listOf(android("emulator-5554"), real))

    assertEquals("emulator-5554", labels[0])
    assertNotNull(labels[1])
    assertTrue(labels[1].contains("Test iPhone"), labels[1])
    assertTrue(labels[1].contains("00008110…"), labels[1])
    assertTrue(!labels[1].contains("0123456789ABCDEF"), labels[1])
  }
}
