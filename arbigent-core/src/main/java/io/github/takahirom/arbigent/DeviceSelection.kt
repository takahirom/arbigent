package io.github.takahirom.arbigent

/**
 * A device the user asked for, together with where the request came from.
 *
 * The source is captured at resolution time and carried through, rather than inferred later from
 * the value, so an error can tell the user exactly which knob to change — a wrong value inherited
 * from the environment is otherwise indistinguishable from one typed on the command line.
 */
public class ArbigentRequestedDevice(
  public val id: String,
  /** Human-readable origin, e.g. `--device-id`, `ARBIGENT_DEVICE_ID` or a settings file and key. */
  public val source: String,
)

/** Raised when no single device can be chosen. Carries a discovery failure as its cause, if any. */
public class ArbigentDeviceSelectionException(
  message: String,
  cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Picks the one device a run should use. Pure: every input is a parameter, so the rules below are
 * testable without an emulator, a simulator or an iPhone.
 *
 * With a [requested] id the id must match exactly and nothing else is ever substituted — silently
 * running against a different device than the one named is the failure mode this whole option
 * exists to prevent. Without one, exactly one device in the preferred group is required; several
 * is an error rather than a guess, because discovery order is not stable.
 */
@ArbigentInternalApi
public fun selectArbigentDevice(
  deviceOs: ArbigentDeviceOs,
  candidates: ArbigentDeviceCandidates,
  requested: ArbigentRequestedDevice? = null,
): ArbigentAvailableDevice {
  if (requested != null) {
    if (deviceOs.isWeb()) {
      throw ArbigentDeviceSelectionException(
        "--device-id is not supported for --os=web because the browser session has no device id.\n" +
          "Source: ${requested.source}"
      )
    }
    val matches = candidates.all.filter { it.deviceId == requested.id }
    matches.singleOrNull()?.let { return it }
    if (matches.size > 1) {
      // Two devices reporting the same id means discovery is broken, not that the user chose badly.
      throw ArbigentDeviceSelectionException(
        "Several ${osLabel(deviceOs)} devices report the same device id ${displayRequestedId(deviceOs, requested.id)}.\n" +
          "Source: ${requested.source}",
        candidates.discoveryFailure,
      )
    }
    // The requested device may be attached yet unusable (an unaccepted USB-debugging prompt, a
    // still-booting emulator). Saying "not found" there sends the user looking for a cable problem.
    val unusable = candidates.unavailable.firstOrNull { it.deviceId == requested.id }
    if (unusable != null) {
      throw ArbigentDeviceSelectionException(
        buildString {
          append("${osLabel(deviceOs)} device ${displayRequestedId(deviceOs, requested.id)} is attached but not usable (adb reports it as ${unusable.state}).\n")
          append("Source: ${requested.source}\n")
          append("Accept the USB debugging prompt on the device, or wait for it to finish booting, and run again.")
          appendDiscoveryFailure(candidates.discoveryFailure)
        },
        candidates.discoveryFailure,
      )
    }
    throw ArbigentDeviceSelectionException(
      buildString {
        append("No ${osLabel(deviceOs)} device with the requested device id was found.\n")
        append("Requested: ${displayRequestedId(deviceOs, requested.id)}\n")
        append("Source: ${requested.source}\n")
        append(availableLine(candidates.all))
        appendUnavailable(candidates.unavailable)
        append("\nPass --device-id with one of the ids above, or clear the current setting.")
        appendDiscoveryFailure(candidates.discoveryFailure)
      },
      candidates.discoveryFailure,
    )
  }

  // An empty preferred group with a discovery failure does not mean "no such device": it means we
  // could not look. Falling through to the fallback group there would run on a simulator when the
  // iPhone the user set a team id for simply failed to enumerate.
  if (candidates.preferred.isEmpty() && candidates.discoveryFailure != null) {
    throw ArbigentDeviceSelectionException(
      buildString {
        append("${osLabel(deviceOs)} device discovery did not complete, so arbigent will not guess which device to use.\n")
        append(availableLine(candidates.fallback))
        appendUnavailable(candidates.unavailable)
        append("\nFix the problem below, or pass --device-id to name the device explicitly.")
        appendDiscoveryFailure(candidates.discoveryFailure)
      },
      candidates.discoveryFailure,
    )
  }

  val group = candidates.preferred.ifEmpty { candidates.fallback }
  group.singleOrNull()?.let { return it }
  if (group.size > 1) {
    throw ArbigentDeviceSelectionException(
      buildString {
        append("Several ${osLabel(deviceOs)} devices are connected, so arbigent will not guess which one to use.\n")
        append(availableLine(group))
        appendUnavailable(candidates.unavailable)
        append("\nPass --device-id (or set $ENV_ARBIGENT_DEVICE_ID) to choose one.")
        appendDiscoveryFailure(candidates.discoveryFailure)
      },
      candidates.discoveryFailure,
    )
  }
  throw ArbigentDeviceSelectionException(
    buildString {
      append("No available ${osLabel(deviceOs)} device found.")
      appendUnavailable(candidates.unavailable)
      appendDiscoveryFailure(candidates.discoveryFailure)
    },
    candidates.discoveryFailure,
  )
}

/** Environment variable that selects the device for a run, for every OS. */
public const val ENV_ARBIGENT_DEVICE_ID: String = "ARBIGENT_DEVICE_ID"

/**
 * How a chosen or offered device is written in logs and errors. A physical iPhone's hardware UDID
 * is only ever shown as a short, unique-enough prefix; adb serials and simulator UDIDs are not
 * secret and are shown in full so they can be copied straight into `--device-id`.
 */
@ArbigentInternalApi
public fun describeArbigentDevices(devices: List<ArbigentAvailableDevice>): List<String> {
  val realDevices = devices.filterIsInstance<ArbigentAvailableDevice.IosReal>()
  val maskedByDevice = realDevices
    .zip(ArbigentAvailableDevice.IosReal.maskedUdidLabels(realDevices))
    .toMap()
  return devices.map { device ->
    val id = maskedByDevice[device] ?: device.deviceId
    // A name is printed verbatim next to the masked id, so a name that carries the hardware UDID
    // would leak it in full despite the masking — whether the name IS the UDID (what discovery falls
    // back to when the device reports none) or merely contains it. Such a device is shown by its
    // masked id alone.
    val name = device.name.takeUnless { candidate ->
      device is ArbigentAvailableDevice.IosReal && device.deviceId?.let { candidate.contains(it) } == true
    }
    when {
      id == null -> name ?: device.deviceOs.name
      name == null || name == id -> id
      else -> "$name ($id)"
    }
  }
}

private fun availableLine(devices: List<ArbigentAvailableDevice>): String =
  if (devices.isEmpty()) "Available: (none)"
  else "Available: " + describeArbigentDevices(devices).joinToString(", ")

// A requested id that matched nothing cannot be classified, so on iOS it is masked: it may be a
// physical iPhone's hardware UDID, which this project never prints in full.
private fun displayRequestedId(deviceOs: ArbigentDeviceOs, id: String): String =
  if (deviceOs.isIos()) id.take(8) + "…" else id

// Listed separately from "Available" so an unusable device is visible without ever looking like
// something --device-id could be pointed at.
private fun StringBuilder.appendUnavailable(unavailable: List<ArbigentUnavailableDevice>) {
  if (unavailable.isEmpty()) return
  append("\nAttached but not usable: ")
  append(unavailable.joinToString(", ") { "${it.deviceId} (${it.state})" })
}

private fun StringBuilder.appendDiscoveryFailure(failure: Throwable?) {
  if (failure == null) return
  append("\nNote: device discovery did not complete, so this list may be incomplete: ")
  append(failure.message ?: failure::class.simpleName)
}

private fun osLabel(deviceOs: ArbigentDeviceOs): String = when (deviceOs) {
  ArbigentDeviceOs.Android -> "Android"
  ArbigentDeviceOs.Ios -> "iOS"
  ArbigentDeviceOs.Web -> "web"
}
