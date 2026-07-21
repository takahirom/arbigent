package io.github.takahirom.arbigent

/**
 * Resolves the Apple developer team id used to re-sign the XCTest runner for a physical device.
 *
 * Resolution order (per PR-B design):
 *   1. Explicit config/CLI option ([ArbigentIosRealDeviceConfiguration.appleTeamId]).
 *   2. Environment variable [ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID].
 *   3. Auto-detection via `security find-identity -v -p codesigning`, but ONLY when exactly one
 *      "Apple Development" identity is present. Zero or multiple → fail with an actionable message.
 *
 * Team ids are treated as sensitive: they are never logged in full (see [maskTeamId]).
 */
public object IosCodeSigningTeamResolver {
  private val TEAM_ID_REGEX = Regex("^[A-Z0-9]{10}$")

  // "  1) ABC123... \"Apple Development: Jane Doe (TEAMID1234)\""
  private val APPLE_DEVELOPMENT_LINE = Regex("""Apple Development:[^(]*\(([A-Z0-9]{10})\)""")

  public fun resolve(
    config: ArbigentIosRealDeviceConfiguration = ArbigentIosRealDeviceSettings.current,
    env: (String) -> String? = System::getenv,
    executor: ArbigentCommandExecutor = DefaultArbigentCommandExecutor(),
  ): String {
    config.appleTeamId?.trim()?.takeIf { it.isNotBlank() }?.let { return validate(it, "CLI option/settings") }
    env(ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID)?.trim()?.takeIf { it.isNotBlank() }
      ?.let { return validate(it, "environment ${ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID}") }

    val result = executor.execute(listOf("security", "find-identity", "-v", "-p", "codesigning"))
    val teamId = autoDetect(result.stdout)
    arbigentInfoLog("iOS real device: auto-detected Apple team id ${maskTeamId(teamId)} from codesigning identities")
    return teamId
  }

  /** Extracts a single "Apple Development" team id from `security find-identity` output. */
  public fun autoDetect(findIdentityOutput: String): String {
    val teamIds = APPLE_DEVELOPMENT_LINE.findAll(findIdentityOutput)
      .map { it.groupValues[1] }
      .toSet()
    return when {
      teamIds.isEmpty() -> throw IllegalStateException(
        "No 'Apple Development' code-signing identity found. Set the Apple team id via the " +
          "ios-xctest-apple-team-id option or the ${ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID} " +
          "environment variable, and ensure a signing certificate is installed in your keychain."
      )
      teamIds.size > 1 -> throw IllegalStateException(
        "Multiple Apple developer teams found in your keychain; cannot auto-detect. Choose one by " +
          "setting the ios-xctest-apple-team-id option or the " +
          "${ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID} environment variable."
      )
      else -> teamIds.first()
    }
  }

  private fun validate(teamId: String, source: String): String {
    require(TEAM_ID_REGEX.matches(teamId)) {
      "Invalid Apple team id from $source: expected 10 uppercase alphanumeric characters."
    }
    return teamId
  }

  /**
   * Masks a team id for logging, keeping only the first two characters (e.g. "TN********"). Team
   * ids are never logged in full.
   */
  public fun maskTeamId(teamId: String): String =
    if (teamId.length <= 2) "****" else teamId.take(2) + "*".repeat(teamId.length - 2)
}
