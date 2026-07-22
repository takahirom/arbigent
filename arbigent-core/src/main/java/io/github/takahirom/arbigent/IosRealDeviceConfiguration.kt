package io.github.takahirom.arbigent

/**
 * Runtime configuration for the iOS real-device (physical iPhone) XCTest backend.
 *
 * These knobs only apply to physical devices discovered via `xcrun devicectl`; simulators are
 * unaffected. The CLI/settings build one of these and thread it explicitly through discovery
 * ([fetchAvailableDevicesByOs]) into each discovered [ArbigentAvailableDevice.IosReal], which
 * carries it to connection time. Each field falls back to an environment variable and finally a
 * sensible default (see the resolver functions on [ArbigentIosRealDeviceSettings]).
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
 * Environment-variable names, the default port, and the resolver functions for
 * [ArbigentIosRealDeviceConfiguration]. This is a stateless namespace: every resolver takes the
 * config explicitly (there is no process-wide `current`), so precedence lives in one place while the
 * config itself is threaded through discovery and carried on each device instance.
 */
public object ArbigentIosRealDeviceSettings {
  public const val ENV_APPLE_TEAM_ID: String = "ARBIGENT_IOS_XCTEST_APPLE_TEAM_ID"
  public const val ENV_DEVICE_ID: String = "ARBIGENT_IOS_REAL_DEVICE_ID"
  public const val ENV_PORT: String = "ARBIGENT_IOS_REAL_DEVICE_PORT"

  // The maestro XCTest runner's default loopback port (XCTestHTTPServer defaults to 22087 when the
  // PORT env is unset). Keeping this as the host/iproxy port keeps both ends in sync.
  public const val DEFAULT_PORT: Int = 22087

  /** Resolved device UDID filter: explicit config, then env, else null (auto-select single device). */
  public fun resolvedDeviceId(
    config: ArbigentIosRealDeviceConfiguration,
    env: (String) -> String? = System::getenv,
  ): String? =
    config.deviceId?.takeIf { it.isNotBlank() } ?: env(ENV_DEVICE_ID)?.takeIf { it.isNotBlank() }

  /**
   * Resolved runner port: explicit config, then env, else [DEFAULT_PORT]. A configured or env value
   * outside `1..65535` — or non-numeric env text — fails loudly rather than silently falling back to
   * the default, which would mask a misconfiguration.
   */
  public fun resolvedPort(
    config: ArbigentIosRealDeviceConfiguration,
    env: (String) -> String? = System::getenv,
  ): Int {
    config.port?.let { return requireValidPort(it) { "configured iOS real-device port" } }
    val raw = env(ENV_PORT)?.trim()
    if (!raw.isNullOrEmpty()) {
      val parsed = raw.toIntOrNull()
        ?: throw IllegalArgumentException("$ENV_PORT must be an integer in 1..65535 but was \"$raw\"")
      return requireValidPort(parsed) { ENV_PORT }
    }
    return DEFAULT_PORT
  }

  /**
   * Whether the user opted into physical-iPhone selection — an explicit Apple team id or device id
   * (config or env). Discovery uses this to decide whether a physical device should be preferred
   * over a booted simulator.
   */
  public fun isOptedIn(
    config: ArbigentIosRealDeviceConfiguration,
    env: (String) -> String? = System::getenv,
  ): Boolean {
    if (!config.appleTeamId.isNullOrBlank()) return true
    if (!config.deviceId.isNullOrBlank()) return true
    if (!env(ENV_APPLE_TEAM_ID).isNullOrBlank()) return true
    return !env(ENV_DEVICE_ID).isNullOrBlank()
  }

  private inline fun requireValidPort(port: Int, name: () -> String): Int {
    require(port in 1..65535) { "${name()} must be in 1..65535 but was $port" }
    return port
  }
}
