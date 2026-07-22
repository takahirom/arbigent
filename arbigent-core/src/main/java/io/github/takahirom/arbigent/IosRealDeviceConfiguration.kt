package io.github.takahirom.arbigent

/**
 * Runtime configuration for the iOS real-device (physical iPhone) XCTest backend.
 *
 * These knobs only apply to physical devices discovered via `xcrun devicectl`; simulators are
 * unaffected. Values flow from the CLI/settings into [ArbigentIosRealDeviceSettings.current] before
 * device discovery, and each field falls back to an environment variable and finally a sensible
 * default (see [ArbigentIosRealDeviceSettings]).
 */
public class ArbigentIosRealDeviceConfiguration(
  /**
   * Apple developer team id used to re-sign the XCTest runner for the device
   * (`DEVELOPMENT_TEAM`). When null, resolution falls back to the environment and then to
   * auto-detection (see [resolveAppleTeamId]).
   */
  public val appleTeamId: String? = null,
  /** Hardware UDID selecting a specific connected device; null means "the only connected one". */
  public val deviceId: String? = null,
  /** Host/device loopback port the XCTest runner binds and iproxy forwards. */
  public val port: Int? = null,
  /** When true, arbigent spawns and manages `iproxy` to bridge the device loopback to the host. */
  public val autoStartIproxy: Boolean = true,
)

/**
 * Process-wide holder for [ArbigentIosRealDeviceConfiguration], mirroring [ArbigentFiles]. The CLI
 * populates [current] from options/settings; discovery and connection read it. Environment-variable
 * fallbacks live here so both the holder default and callers agree on precedence.
 */
public object ArbigentIosRealDeviceSettings {
  public const val ENV_APPLE_TEAM_ID: String = "ARBIGENT_IOS_XCTEST_APPLE_TEAM_ID"
  public const val ENV_DEVICE_ID: String = "ARBIGENT_IOS_REAL_DEVICE_ID"
  public const val ENV_PORT: String = "ARBIGENT_IOS_REAL_DEVICE_PORT"

  // The maestro XCTest runner's default loopback port (XCTestHTTPServer defaults to 22087 when the
  // PORT env is unset). Keeping this as the host/iproxy port keeps both ends in sync.
  public const val DEFAULT_PORT: Int = 22087

  public var current: ArbigentIosRealDeviceConfiguration = ArbigentIosRealDeviceConfiguration()

  /** Resolved device UDID filter: explicit config, then env, else null (auto-select single device). */
  public fun resolvedDeviceId(env: (String) -> String? = System::getenv): String? =
    current.deviceId?.takeIf { it.isNotBlank() } ?: env(ENV_DEVICE_ID)?.takeIf { it.isNotBlank() }

  /**
   * Resolved runner port: explicit config, then env, else [DEFAULT_PORT]. A configured or env value
   * outside `1..65535` — or non-numeric env text — fails loudly rather than silently falling back to
   * the default, which would mask a misconfiguration.
   */
  public fun resolvedPort(env: (String) -> String? = System::getenv): Int {
    current.port?.let { return requireValidPort(it) { "configured iOS real-device port" } }
    val raw = env(ENV_PORT)?.trim()
    if (!raw.isNullOrEmpty()) {
      val parsed = raw.toIntOrNull()
        ?: throw IllegalArgumentException("$ENV_PORT must be an integer in 1..65535 but was \"$raw\"")
      return requireValidPort(parsed) { ENV_PORT }
    }
    return DEFAULT_PORT
  }

  private inline fun requireValidPort(port: Int, name: () -> String): Int {
    require(port in 1..65535) { "${name()} must be in 1..65535 but was $port" }
    return port
  }
}
