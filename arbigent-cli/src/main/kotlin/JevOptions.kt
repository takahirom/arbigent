package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.github.takahirom.arbigent.ArbigentJevClient
import io.github.takahirom.arbigent.ArbigentJevConfig
import io.github.takahirom.arbigent.ArbigentJevHttpClient
import io.github.takahirom.arbigent.ArbigentJevMode
import io.github.takahirom.arbigent.ArbigentJevOverrides
import io.github.takahirom.arbigent.ArbigentJevSettings
import io.github.takahirom.arbigent.arbigentInfoLog

/**
 * The key and endpoint are per person, so they come from the command line, the environment (the
 * TypeSafe SDK's variable names) or `.arbigent/settings.local.yml`. The mode and thresholds belong
 * to the project (`settings.jev`); these flags override them for one run.
 */
class JevOptions : OptionGroup("Options for Jev, which decides the steps it is confident about without the AI") {
  val jevApiKey by defaultOption("--jev-api-key", envvar = ArbigentJevHttpClient.ApiKeyEnv, help = "Jev API key. Without one, the AI decides every step")
  val jevBaseUrl by defaultOption("--jev-base-url", envvar = ArbigentJevHttpClient.BaseUrlEnv, help = "Jev API base URL (default: ${ArbigentJevHttpClient.DefaultBaseUrl})")
    .default(ArbigentJevHttpClient.DefaultBaseUrl, defaultForHelp = ArbigentJevHttpClient.DefaultBaseUrl)
  val jevModel by defaultOption("--jev-model", envvar = ArbigentJevHttpClient.ModelEnv, help = "Jev model (default: ${ArbigentJevHttpClient.DefaultModel})")
    .default(ArbigentJevHttpClient.DefaultModel, defaultForHelp = ArbigentJevHttpClient.DefaultModel)
  val jevMode by defaultOption("--jev-mode", help = "Override settings.jev.mode: disabled, shadow (ask Jev but let the AI act, for tuning) or active")
    .choice(ArbigentJevMode.entries.associateBy { it.name.lowercase() })
  val jevActionThreshold by defaultOption("--jev-action-threshold", help = "Override settings.jev.actionThreshold: the confidence Jev needs to act without the AI")
    .double().restrictTo(0.0..1.0)
  val jevGoalThreshold by defaultOption("--jev-goal-threshold", help = "Override settings.jev.goalThreshold: the confidence Jev needs to end a task as achieved")
    .double().restrictTo(0.0..1.0)

  val overrides: ArbigentJevOverrides
    get() = ArbigentJevOverrides(mode = jevMode, actionThreshold = jevActionThreshold, goalThreshold = jevGoalThreshold)

  fun createClient(): ArbigentJevClient? =
    jevApiKey?.takeIf { it.isNotBlank() }?.let { ArbigentJevHttpClient(apiKey = it, baseUrl = jevBaseUrl, model = jevModel) }
}

/** Says which settings Jev runs with, or why it doesn't, so a run's log shows what was in effect. */
internal fun logJevSettings(projectSettings: ArbigentJevSettings?, options: JevOptions, client: ArbigentJevClient?) {
  val settings = ArbigentJevConfig.effectiveSettings(projectSettings, options.overrides) ?: return
  if (client == null) {
    arbigentInfoLog("Jev is ${settings.mode}, but no Jev API key is set (--jev-api-key or ${ArbigentJevHttpClient.ApiKeyEnv}); the AI decides every step")
  } else {
    arbigentInfoLog("Jev: ${settings.description()}, model ${options.jevModel} at ${options.jevBaseUrl}")
  }
}
