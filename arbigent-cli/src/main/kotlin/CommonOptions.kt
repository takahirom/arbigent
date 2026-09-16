@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.choice
import kotlinx.coroutines.CoroutineDispatcher
import io.github.takahirom.arbigent.*
import io.ktor.client.request.*
import io.ktor.util.*
import java.io.File

const val defaultResultPath = "arbigent-result"
const val defaultCachePath = "arbigent-cache"

fun CliktCommand.projectFileOption() = defaultOption("--project-file", help = "Path to the project YAML file")

fun CliktCommand.workingDirectoryOption() = defaultOption("--working-directory", help = "Working directory for the project")

fun CliktCommand.logLevelOption() = defaultOption("--log-level", help = "Log level")
  .choice("debug", "info", "warn", "error")
  .default("info")

fun CliktCommand.logFileOption() = defaultOption("--log-file", help = "Log file path")
  .default("$defaultResultPath/arbigent.log")

fun resolveFile(workingDirectory: String?, fileName: String): File {
  return if (workingDirectory.isNullOrBlank()) {
    File(fileName)
  } else {
    File(workingDirectory, fileName)
  }
}

fun validateAiConfig(aiType: AiConfig) {
  when (aiType) {
    is OpenAIAiConfig -> {
      if (aiType.openAiApiKey.isNullOrBlank()) {
        throw CliktError("Missing OpenAI API key. Please provide via --openai-api-key, OPENAI_API_KEY environment variable, or in .arbigent/settings.local.yml")
      }
    }
    is GeminiAiConfig -> {
      if (aiType.geminiApiKey.isNullOrBlank()) {
        throw CliktError("Missing Gemini API key. Please provide via --gemini-api-key, GEMINI_API_KEY environment variable, or in .arbigent/settings.local.yml")
      }
    }
    is AzureOpenAiConfig -> {
      if (aiType.azureOpenAIEndpoint.isNullOrBlank()) {
        throw CliktError("Missing Azure OpenAI endpoint. Please provide via --azure-openai-endpoint or in .arbigent/settings.local.yml")
      }
      if (aiType.azureOpenAIKey.isNullOrBlank()) {
        throw CliktError("Missing Azure OpenAI API key. Please provide via --azure-openai-api-key, AZURE_OPENAI_API_KEY environment variable, or in .arbigent/settings.local.yml")
      }
    }
    is AnthropicAiConfig -> {
      if (aiType.anthropicApiKey.isNullOrBlank()) {
        throw CliktError("Missing Anthropic API key. Please provide via --anthropic-api-key, ANTHROPIC_API_KEY environment variable, or in .arbigent/settings.local.yml")
      }
    }
  }
}

fun applyLogLevel(logLevel: String) {
  arbigentLogLevel =
    ArbigentLogLevel.entries.find { it.name.toLowerCasePreservingASCIIRules() == logLevel.toLowerCasePreservingASCIIRules() }
      ?: throw IllegalArgumentException(
        "Invalid log level. The log level should be one of ${
          ArbigentLogLevel.entries
            .joinToString(", ") { it.name.toLowerCasePreservingASCIIRules() }
        }")
}

data class ArbigentResultDirs(val resultDir: File, val resultFile: File)

fun setupArbigentFiles(workingDirectory: String?, logFile: String): ArbigentResultDirs {
  val resultDir = resolveFile(workingDirectory, defaultResultPath)
  resultDir.mkdirs()
  ArbigentFiles.parentDir = resultDir.absolutePath
  ArbigentFiles.screenshotsDir = File(resultDir, "screenshots")
  ArbigentFiles.jsonlsDir = File(resultDir, "jsonls")
  ArbigentFiles.usagesDir = File(resultDir, "usages")
  ArbigentFiles.logFile = resolveFile(workingDirectory, logFile)
  ArbigentFiles.cacheDir = resolveFile(workingDirectory, defaultCachePath + File.separator + BuildConfig.VERSION_NAME)
  ArbigentFiles.cacheDir.mkdirs()
  // Traces live beside the cache, not under the result dir: they must survive across runs, and
  // keeping them out of the version subdir means a cache reset does not discard them.
  ArbigentFiles.traceDir = resolveFile(workingDirectory, defaultCachePath + File.separator + "traces")
  val resultFile = File(resultDir, "result.yml")
  return ArbigentResultDirs(resultDir, resultFile)
}

fun createAi(aiType: AiConfig, aiApiLoggingEnabled: Boolean): ArbigentAi {
  return when (aiType) {
    is OpenAIAiConfig -> OpenAIAi(
      apiKey = aiType.openAiApiKey!!,
      baseUrl = aiType.openAiEndpoint,
      modelName = aiType.openAiModelName,
      loggingEnabled = aiApiLoggingEnabled,
    )

    is GeminiAiConfig -> OpenAIAi(
      apiKey = aiType.geminiApiKey!!,
      baseUrl = aiType.geminiEndpoint,
      modelName = aiType.geminiModelName,
      loggingEnabled = aiApiLoggingEnabled,
      jsonSchemaType = ArbigentAi.JsonSchemaType.GeminiOpenAICompatible
    )

    is AzureOpenAiConfig -> OpenAIAi(
      apiKey = aiType.azureOpenAIKey!!,
      baseUrl = aiType.azureOpenAIEndpoint!!,
      modelName = aiType.azureOpenAIModelName,
      loggingEnabled = aiApiLoggingEnabled,
      requestBuilderModifier = {
        parameter("api-version", aiType.azureOpenAIApiVersion)
        header("api-key", aiType.azureOpenAIKey!!)
      }
    )

    is AnthropicAiConfig -> AnthropicAi(
      apiKey = aiType.anthropicApiKey!!,
      baseUrl = aiType.anthropicEndpoint,
      modelName = aiType.anthropicModelName,
      loggingEnabled = aiApiLoggingEnabled,
    )
  }
}

/**
 * Returns the non-null project file path, or fails with the same CLI error every command shows
 * when `--project-file` is missing.
 */
fun requireProjectFile(projectFile: String?): String =
  projectFile
    ?: throw CliktError("Missing option '--project-file'. Please provide a project file path via command line argument or in .arbigent/settings.local.yml")

/**
 * Returns true when [projectFile] points to an Android Studio Journeys source: a
 * `*.journey.xml` / `*_journey.xml` file, or a directory (directories are only valid as journey
 * sources — the YAML loader requires a file — so the journey loader owns them and reports a clear
 * error when no journey files are found).
 */
fun isJourneyProjectSource(projectFile: String): Boolean {
  val file = File(projectFile)
  if (!file.exists()) return false
  return file.isDirectory || ArbigentJourneyXmlImporter.isJourneyFile(file)
}

/**
 * Loads [projectFile] as project content, choosing the Journeys XML importer or the YAML loader.
 * A project file that fails validation is reported as a plain [CliktError] message rather than an
 * uncaught exception, since the user's fix is in the YAML, not in the command line.
 */
fun loadArbigentProjectFileContent(projectFile: String): ArbigentProjectFileContent =
  asCliktError(projectFile) {
    if (isJourneyProjectSource(projectFile)) {
      ArbigentJourneyXmlImporter.loadProjectContent(File(projectFile))
    } else {
      ArbigentProjectSerializer().load(File(projectFile))
    }
  }

/**
 * Turns a validation failure into a [CliktError], naming the file it came from — the core reports
 * the violations but has no idea which path was loaded.
 */
private fun <T> asCliktError(projectFile: String, block: () -> T): T = try {
  block()
} catch (e: ArbigentProjectValidationException) {
  throw CliktError(
    if (e.violations.isEmpty()) e.message
    else arbigentValidationReport(e.violations, source = projectFile)
  )
}

/**
 * Builds an [ArbigentProject] from [projectFile], loading Journeys XML when the path is a
 * journey source and falling back to the normal YAML loader otherwise.
 */
fun loadArbigentProject(
  projectFile: String,
  aiFactory: () -> ArbigentAi,
  deviceFactory: () -> ArbigentDevice,
  appSettings: ArbigentAppSettings,
  dispatcher: CoroutineDispatcher,
): ArbigentProject = asCliktError(projectFile) {
  if (isJourneyProjectSource(projectFile)) {
    val projectFileContent = ArbigentJourneyXmlImporter.loadProjectContent(File(projectFile))
    ArbigentProject(
      projectFileContent = projectFileContent,
      aiFactory = aiFactory,
      deviceFactory = deviceFactory,
      appSettings = appSettings,
      dispatcher = dispatcher,
    )
  } else {
    ArbigentProject(
      file = File(projectFile),
      aiFactory = aiFactory,
      deviceFactory = deviceFactory,
      appSettings = appSettings,
      dispatcher = dispatcher,
    )
  }
}

/**
 * Seam for the "the command handed the chosen device id to connection" step. Selection can be tested
 * as a pure function and option resolution through --dry-run, but neither catches an option that is
 * parsed and then never passed on, which is the failure this indirection exists to make testable.
 */
fun interface ArbigentDeviceConnector {
  fun connect(
    os: String,
    requestedDevice: ArbigentRequestedDevice?,
    iosAppleTeamId: String?,
    iosRealDevicePort: Int?,
  ): ArbigentDevice
}

val defaultDeviceConnector: ArbigentDeviceConnector =
  ArbigentDeviceConnector { os, requestedDevice, iosAppleTeamId, iosRealDevicePort ->
    connectDevice(os, requestedDevice, iosAppleTeamId, iosRealDevicePort)
  }

fun connectDevice(
  os: String,
  requestedDevice: ArbigentRequestedDevice? = null,
  iosAppleTeamId: String? = null,
  iosRealDevicePort: Int? = null,
): ArbigentDevice {
  val deviceOs =
    ArbigentDeviceOs.entries.find { it.name.toLowerCasePreservingASCIIRules() == os.toLowerCasePreservingASCIIRules() }
      ?: throw IllegalArgumentException(
        "Invalid OS. The OS should be one of ${
          ArbigentDeviceOs.entries
            .joinToString(", ") { it.name.toLowerCasePreservingASCIIRules() }
        }")
  // iOS real-device connection knobs, threaded into discovery so each discovered IosReal carries
  // them to connection time. These configure *how* to talk to a physical iPhone once chosen; which
  // device to run on is [requestedDevice] and is deliberately kept separate.
  val iosConfig = ArbigentIosRealDeviceConfiguration(
    appleTeamId = iosAppleTeamId?.takeIf { it.isNotBlank() },
    port = iosRealDevicePort,
  )
  val candidates = fetchDeviceCandidates(
    deviceOs = deviceOs,
    requestedDeviceId = requestedDevice?.id,
    iosConfig = iosConfig,
  )
  // Maestro's AndroidDeviceConnection.byId() enumerates every attached device (dadb opens a
  // connection to each one), so an unauthorized or offline device can break the connection even
  // when a different, healthy device was selected. Discovery tolerates it; connection cannot, so
  // say so up front instead of letting a raw dadb IOException be the first hint.
  if (candidates.unavailable.isNotEmpty()) {
    arbigentInfoLog(
      "Attached but not usable: " +
        candidates.unavailable.joinToString(", ") { "${it.deviceId} (${it.state})" } +
        ". Authorize or disconnect it — connecting to any device can fail while it is attached."
    )
  }
  // A device that cannot be chosen is a configuration problem, not a crash: rethrow as a CliktError
  // so the user gets the message alone instead of a stack trace. The message already carries any
  // discovery failure's text, which the cause chain would otherwise drop.
  val chosen = try {
    selectArbigentDevice(deviceOs, candidates, requestedDevice)
  } catch (e: ArbigentDeviceSelectionException) {
    throw CliktError(e.message)
  }
  // Logged on success too: an unexpected device is otherwise invisible until the report is read, and
  // a stale environment variable is precisely the kind of mistake that needs to be loud.
  val chosenLabel = describeArbigentDevices(listOf(chosen)).single()
  arbigentInfoLog(
    if (requestedDevice == null) "Selected device: $chosenLabel (only connected device)"
    else "Selected device: $chosenLabel (requested via ${requestedDevice.source})"
  )
  return chosen.connectToDevice()
}

// Parses the --ios-real-device-port string option, failing loudly on a non-integer or out-of-range
// value instead of silently falling back to the default (which would hide a typo'd port).
internal fun parseIosRealDevicePort(raw: String?): Int? {
  val trimmed = raw?.trim()
  if (trimmed.isNullOrEmpty()) return null
  val port = trimmed.toIntOrNull()
    ?: throw CliktError("--ios-real-device-port must be an integer in 1..65535 but was \"$trimmed\"")
  if (port !in 1..65535) {
    throw CliktError("--ios-real-device-port must be in 1..65535 but was $port")
  }
  return port
}
