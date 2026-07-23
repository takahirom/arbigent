package io.github.takahirom.arbigent

import java.io.File

/**
 * Resolves the Apple developer team id used to re-sign the XCTest runner for a physical device
 * (`DEVELOPMENT_TEAM`).
 *
 * Resolution order (per PR-B design):
 *   1. Explicit config/CLI option ([ArbigentIosRealDeviceConfiguration.appleTeamId]).
 *   2. Environment variable [ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID].
 *   3. Auto-detection from the keychain, but ONLY when exactly one team owns the installed
 *      "Apple Development" certificates. Zero or multiple → fail with an actionable message.
 *
 * The team id is the certificate's Organizational Unit (OU), NOT the parenthetical in the common
 * name "Apple Development: Name (XXXXXXXXXX)" — that parenthetical is the individual/user id and is
 * rejected by xcodebuild as an unknown team. We therefore read the OU from the certificate subject.
 *
 * Team ids are treated as sensitive: never logged in full (see [maskTeamId]).
 */
public object IosCodeSigningTeamResolver {
  private val TEAM_ID_REGEX = Regex("^[A-Z0-9]{10}$")

  // Matches the OU in an openssl `-nameopt multiline` subject dump, e.g.
  //   "organizationalUnitName    = 84ABCDE123"
  private val OU_REGEX = Regex("""organizationalUnitName\s*=\s*([A-Z0-9]{10})""")
  private val PEM_CERT_REGEX =
    Regex("""-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----""", RegexOption.DOT_MATCHES_ALL)

  public fun resolve(
    config: ArbigentIosRealDeviceConfiguration = ArbigentIosRealDeviceSettings.current,
    env: (String) -> String? = System::getenv,
    executor: ArbigentCommandExecutor = DefaultArbigentCommandExecutor(),
  ): String {
    config.appleTeamId?.trim()?.takeIf { it.isNotBlank() }?.let { return validate(it, "CLI option/settings") }
    env(ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID)?.trim()?.takeIf { it.isNotBlank() }
      ?.let { return validate(it, "environment ${ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID}") }

    val teamId = autoDetectFromKeychain(executor)
    arbigentInfoLog("iOS real device: auto-detected Apple team id ${maskTeamId(teamId)} from codesigning identities")
    return validate(teamId, "keychain auto-detection")
  }

  private fun autoDetectFromKeychain(executor: ArbigentCommandExecutor): String {
    val pem = executor.execute(
      listOf("security", "find-certificate", "-a", "-c", "Apple Development", "-p")
    )
    val subjects = PEM_CERT_REGEX.findAll(pem.stdout).mapNotNull { match ->
      val tmp = File.createTempFile("arbigent-appledev-cert", ".pem").apply { writeText(match.value) }
      try {
        executor.execute(
          listOf("openssl", "x509", "-in", tmp.absolutePath, "-noout", "-subject", "-nameopt", "multiline")
        ).takeIf { it.isSuccess }?.stdout
      } finally {
        tmp.delete()
      }
    }.toList()
    return teamIdFromSubjects(subjects)
  }

  /** Extracts the single team id (certificate OU) from openssl subject dumps. */
  public fun teamIdFromSubjects(subjects: List<String>): String {
    val teamIds = subjects.flatMap { subject -> OU_REGEX.findAll(subject).map { it.groupValues[1] } }.toSet()
    return when {
      teamIds.isEmpty() -> throw IllegalStateException(
        "No 'Apple Development' code-signing team found in your keychain. Set the Apple team id via " +
          "the ios-xctest-apple-team-id option or the ${ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID} " +
          "environment variable, and ensure a signing certificate is installed."
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
   * Masks a team id for logging, keeping only the first two characters (e.g. "84********"). Team
   * ids are never logged in full.
   */
  public fun maskTeamId(teamId: String): String =
    if (teamId.length <= 2) "****" else teamId.take(2) + "*".repeat(teamId.length - 2)
}
