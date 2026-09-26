package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.github.takahirom.arbigent.ArbigentJevClient
import io.github.takahirom.arbigent.ArbigentJevConfig
import io.github.takahirom.arbigent.ArbigentJevHttpClient
import io.github.takahirom.arbigent.ArbigentJevMode
import io.github.takahirom.arbigent.ArbigentJevOverrides
import io.github.takahirom.arbigent.ArbigentJevResolution
import io.github.takahirom.arbigent.ArbigentJevSettings
import io.github.takahirom.arbigent.arbigentInfoLog

/**
 * The key and endpoint are per person, so they come from the command line, the environment (the
 * TypeSafe SDK's variable names) or `.arbigent/settings.local.yml`. The mode and thresholds belong
 * to the project (`settings.jev`); these flags override them for one run.
 */
class JevOptions : OptionGroup("Options for Jev, which decides the steps it is confident about without the AI") {
  // Which of these were typed on the command line, as opposed to read from the environment or a
  // settings file: only those are lost when they come before a subcommand.
  private val trackedOptions = mutableListOf<CommandLineTrackedOption<*, *, *>>()

  private fun <AllT, EachT, ValueT> tracked(option: OptionWithValues<AllT, EachT, ValueT>) =
    CommandLineTrackedOption(option).also { trackedOptions += it }

  val jevApiKey by tracked(defaultOption("--jev-api-key", envvar = ArbigentJevHttpClient.ApiKeyEnv, help = "Jev API key, required while Jev is on (turn it off with --jev-mode=disabled)"))
  val jevBaseUrl by tracked(defaultOption("--jev-base-url", envvar = ArbigentJevHttpClient.BaseUrlEnv, help = "Jev API base URL (default: ${ArbigentJevHttpClient.DefaultBaseUrl})")
    .default(ArbigentJevHttpClient.DefaultBaseUrl, defaultForHelp = ArbigentJevHttpClient.DefaultBaseUrl))
  val jevModel by tracked(defaultOption("--jev-model", envvar = ArbigentJevHttpClient.ModelEnv, help = "Jev model (default: ${ArbigentJevHttpClient.DefaultModel})")
    .default(ArbigentJevHttpClient.DefaultModel, defaultForHelp = ArbigentJevHttpClient.DefaultModel))
  val jevMode by tracked(defaultOption("--jev-mode", help = "Override settings.jev.mode: disabled, shadow (ask Jev but let the AI act, for tuning) or active")
    .choice(ArbigentJevMode.entries.associateBy { it.name.lowercase() }))
  val jevActionThreshold by tracked(defaultOption("--jev-action-threshold", help = "Override settings.jev.actionThreshold: the confidence Jev needs to act without the AI")
    .double().restrictTo(0.0..1.0))
  val jevGoalThreshold by tracked(defaultOption("--jev-goal-threshold", help = "Override settings.jev.goalThreshold: the confidence Jev needs to end a task as achieved")
    .double().restrictTo(0.0..1.0))

  internal fun namesGivenOnCommandLine(): List<String> =
    trackedOptions.filter { it.givenOnCommandLine }.map { it.names.first() }

  val overrides: ArbigentJevOverrides
    get() = ArbigentJevOverrides(mode = jevMode, actionThreshold = jevActionThreshold, goalThreshold = jevGoalThreshold)

  private fun createClient(): ArbigentJevClient? {
    val apiKey = jevApiKey?.takeIf { it.isNotBlank() } ?: return null
    return try {
      ArbigentJevHttpClient(apiKey = apiKey, baseUrl = jevBaseUrl, model = jevModel)
    } catch (e: IllegalArgumentException) {
      throw CliktError(e.message)
    }
  }

  /**
   * The config this run's Jev uses, or null when Jev is off, and says which in the log. Jev turned on
   * without a key fails the run rather than quietly leaving every step to the AI.
   */
  fun resolve(projectSettings: ArbigentJevSettings?): ArbigentJevConfig? =
    when (val resolution = ArbigentJevConfig.resolve(projectSettings, overrides, createClient())) {
      ArbigentJevResolution.Off -> {
        if (projectSettings == null && !overrides.isEmpty()) {
          arbigentInfoLog("Jev thresholds were given, but Jev is off: the project has no settings.jev, so pass --jev-mode to turn it on")
        }
        null
      }
      is ArbigentJevResolution.MissingKey -> throw CliktError(
        "Jev is ${resolution.settings.mode}, but no Jev API key is set (--jev-api-key or ${ArbigentJevHttpClient.ApiKeyEnv}). " +
          "Set one, or pass --jev-mode=disabled to run without Jev."
      )
      is ArbigentJevResolution.On -> {
        arbigentInfoLog("Jev: ${resolution.config.settings.description()}, model $jevModel at $jevBaseUrl")
        resolution.config
      }
    }
}

/**
 * clikt does not pass a parent's option to a subcommand, so `run --jev-mode=active task ...` would be
 * accepted and then ignored. Values from the environment or the settings file need no check: `task`
 * reads those itself.
 */
internal fun CliktCommand.rejectJevOptionsBeforeSubcommand(options: JevOptions) {
  val subcommand = currentContext.invokedSubcommand?.commandName ?: return
  val name = options.namesGivenOnCommandLine().firstOrNull() ?: return
  throw CliktError("$name must be passed after `$subcommand`, as in `run $subcommand $name ... \"<goal>\"`.")
}
