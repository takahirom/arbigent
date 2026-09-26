@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parsers.OptionInvocation
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.ArbigentIosRealDeviceSettings
import io.github.takahirom.arbigent.ArbigentRequestedDevice
import io.github.takahirom.arbigent.ENV_ARBIGENT_DEVICE_ID
import io.github.takahirom.arbigent.arbigentInfoLog
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * `--device-id`: which device this run executes on, for every OS.
 *
 * Declared per command (not once globally) because clikt does not pass a parent's option down to a
 * subcommand, so `run task` needs its own.
 */
internal fun ParameterHolder.deviceIdOption(): CommandLineTrackedOption<String?, String, String> =
  CommandLineTrackedOption(
    defaultOption(
      "--device-id",
      // Clikt's own envvar support gives the documented precedence: command line > envvar > settings.
      envvar = ENV_ARBIGENT_DEVICE_ID,
      help = "Device to run on: an adb serial for --os=android (e.g. emulator-5554), or a simulator " +
        "or physical iPhone UDID for --os=ios. Matched exactly; arbigent never falls back to another " +
        "device. Falls back to $ENV_ARBIGENT_DEVICE_ID, then the settings key `device-id`. When " +
        "omitted, exactly one connected device is required.",
    )
  )

/**
 * The superseded iOS-only option, kept hidden and rejected on use.
 *
 * Deleting it outright would make a leftover `--ios-real-device-id` flag an unknown-option error
 * (fine) but a leftover `ios-real-device-id` settings key silently ignored (not fine) — the run
 * would then auto-select some other device. Accepting it here lets both be reported.
 */
internal fun ParameterHolder.legacyIosRealDeviceIdOption(): CommandLineTrackedOption<String?, String, String> =
  CommandLineTrackedOption(
    defaultOption(
      "--ios-real-device-id",
      hidden = true,
      help = "Replaced by --device-id.",
    )
  )

/**
 * Wraps an option so it also reports whether it was actually typed on the command line.
 *
 * Clikt exposes no provenance for a finalized option, and the value alone cannot tell "typed on the
 * command line" from "read from a settings key that happens to hold the same value". That
 * distinction decides whether `run --device-id <id> task …` is an error, so it has to be recorded
 * rather than inferred: with the value-comparison heuristic, a flag matching `run.device-id` was
 * mistaken for a settings value, slipped past the rejection, and the run then connected to whatever
 * the `task` subcommand resolved on its own — a different device than the one named.
 */
internal class CommandLineTrackedOption<AllT, EachT, ValueT>(
  private val delegate: OptionWithValues<AllT, EachT, ValueT>,
) : OptionWithValues<AllT, EachT, ValueT> by delegate {
  /** True once parsing is done if the option appeared in argv for the command that declares it. */
  internal var givenOnCommandLine: Boolean = false
    private set

  // Clikt passes only command-line invocations here; the environment variable and the value source
  // are read inside finalize, so an empty list means exactly "not typed on the command line".
  override fun finalize(context: Context, invocations: List<OptionInvocation>) {
    givenOnCommandLine = invocations.isNotEmpty()
    delegate.finalize(context, invocations)
  }

  // Registering the wrapper rather than the wrapped option is what routes finalize() through here.
  // Names are always passed explicitly, so nothing is lost by skipping clikt's name inference.
  override fun provideDelegate(
    thisRef: ParameterHolder,
    prop: KProperty<*>,
  ): ReadOnlyProperty<ParameterHolder, AllT> {
    thisRef.registerOption(this)
    return this
  }
}

private const val LEGACY_IOS_REAL_DEVICE_ID_MIGRATION: String =
  "--ios-real-device-id (settings key `ios-real-device-id`) has been replaced by --device-id " +
    "(settings key `device-id`), which selects the device for every OS. Move the UDID there and " +
    "remove the old option or key."

/**
 * Resolves the requested device, or null when the run should auto-select.
 *
 * Also the one place leftovers of the old iOS-only setting are reported: they are no longer read
 * anywhere, so anything short of an explicit message would silently run on the wrong device.
 */
internal fun CliktCommand.resolveRequestedDevice(
  os: String,
  deviceId: String?,
  deviceIdOption: CommandLineTrackedOption<String?, String, String>,
  legacyIosRealDeviceId: String?,
): ArbigentRequestedDevice? {
  // clikt's own envvar reader, so this sees exactly what the option machinery saw.
  val env: (String) -> String? = currentContext.readEnvvar
  if (!legacyIosRealDeviceId.isNullOrBlank()) {
    throw CliktError(LEGACY_IOS_REAL_DEVICE_ID_MIGRATION)
  }
  val legacyEnv = env(ArbigentIosRealDeviceSettings.LEGACY_ENV_DEVICE_ID)
  if (!legacyEnv.isNullOrBlank()) {
    val replacement = "${ArbigentIosRealDeviceSettings.LEGACY_ENV_DEVICE_ID} has been replaced by " +
      "$ENV_ARBIGENT_DEVICE_ID and is no longer read."
    // Only iOS runs could have been steered by the old variable, so only they are at risk of
    // silently running elsewhere. Failing every --os=android run because the variable happens to be
    // exported in the shell would be pure collateral damage.
    if (os.equals("ios", ignoreCase = true)) {
      throw CliktError("$replacement Unset it and set $ENV_ARBIGENT_DEVICE_ID instead.")
    }
    arbigentInfoLog("$replacement Ignoring it.")
  }
  if (deviceId == null) return null
  if (deviceId.isBlank()) {
    // Treating blank as "unset" would quietly auto-select, which is exactly what an explicit
    // (if empty) request asks us not to do.
    throw CliktError(
      "--device-id must not be empty. Remove the option, unset $ENV_ARBIGENT_DEVICE_ID, or delete " +
        "the `device-id` settings key to let arbigent use the only connected device."
    )
  }
  return ArbigentRequestedDevice(
    id = deviceId.trim(),
    source = deviceIdSource(deviceId.trim(), deviceIdOption, env),
  )
}

/**
 * Rejects device options handed to a parent command whose subcommand does the actual run.
 *
 * Clikt does not pass a parent's option down, so `run --device-id <id> task …` would be parsed and
 * then ignored. Only what was typed on this command's own line is checked: an environment variable
 * or settings key is resolved by the subcommand itself, where a flag still wins, so validating it
 * here would reject runs that are perfectly valid (an empty `ARBIGENT_DEVICE_ID` blocking a real
 * `run task --device-id <id>`, for one).
 */
internal fun CliktCommand.rejectDeviceOptionsBeforeSubcommand(
  deviceIdOption: CommandLineTrackedOption<String?, String, String>,
  legacyIosRealDeviceId: String?,
) {
  // The superseded option is checked whatever its source: nothing reads it any more, so a leftover
  // `ios-real-device-id` under `run:` would otherwise be invisible to `task` and silently ignored.
  if (!legacyIosRealDeviceId.isNullOrBlank()) {
    throw CliktError(LEGACY_IOS_REAL_DEVICE_ID_MIGRATION)
  }
  if (deviceIdOption.givenOnCommandLine) {
    throw CliktError("--device-id must be passed after `task`, as in `run task --device-id <id> \"<goal>\"`.")
  }
}

/** Labels where the value came from, so an error names the knob to change. */
private fun CliktCommand.deviceIdSource(value: String, option: CommandLineTrackedOption<String?, String, String>, env: (String) -> String?): String {
  if (option.givenOnCommandLine) return "--device-id"
  val envValue = env(ENV_ARBIGENT_DEVICE_ID)?.trim()?.takeIf { it.isNotEmpty() }
  if (envValue == value) return ENV_ARBIGENT_DEVICE_ID
  // Nothing else can supply a value (the option has no default), and the chained source does not
  // report which of the .arbigent settings files matched, so name the key rather than a file.
  return "the `device-id` key in a .arbigent settings file"
}
