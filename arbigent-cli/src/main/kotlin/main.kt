package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.defaultByName
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.github.ajalt.clikt.parameters.types.choice
import io.github.takahirom.arbigent.ArbigentAi
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.ArbigentProject
import io.github.takahirom.arbigent.DeviceOs
import io.github.takahirom.arbigent.OpenAIAi
import io.github.takahirom.arbigent.fetchAvailableDevicesByOs
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.util.toLowerCasePreservingASCIIRules
import kotlinx.coroutines.runBlocking
import java.io.File

sealed class AiConfig(name: String) : OptionGroup(name)

class OpenAIAiConfig : AiConfig("Options for OpenAI API AI") {
  private val defaultEndpoint = "https://api.openai.com/v1/"
  val openAiEndpoint by option(help = "Endpoint URL (default: $defaultEndpoint)")
    .default(defaultEndpoint, defaultForHelp = defaultEndpoint)
  val openAiModelName by option(help = "Model name (default: gpt-4o-mini)")
    .default("gpt-4o-mini", "gpt-4o-mini")
}

class GeminiAiConfig : AiConfig("Options for Gemini API AI") {
  private val defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta/openai/"
  val geminiEndpoint by option(help = "Endpoint URL (default: $defaultEndpoint)")
    .default(defaultEndpoint, defaultForHelp = defaultEndpoint)
  val geminiModelName by option(help = "Model name (default: gemini-1.5-flash)")
    .default("gemini-1.5-flash", "gemini-1.5-flash")
}

class AzureOpenAiConfig : AiConfig("Options for Azure OpenAI") {
  val azureOpenAIEndpoint by option(help = "Endpoint URL")
    .prompt("Endpoint URL")
  val azureOpenAIApiVersion by option(help = "API version")
    .default("2024-10-21")
  val azureOpenAIModelName by option(help = "Model name (default: gpt-4o-mini)")
    .default("gpt-4o-mini")
}

class ArbigentCli : CliktCommand() {
  private val aiType by option(help = "Type of AI to use")
    .groupChoice(
      "openai" to OpenAIAiConfig(),
      "gemini" to GeminiAiConfig(),
      "azureopenai" to AzureOpenAiConfig()
    )
    .defaultByName("openai")

  private val os by option(help = "Target operating system")
    .choice("android", "ios", "web")
    .default("android")

  val scenarioFile by option(help = "Path to the scenario YAML file")
    .prompt("Scenario file path")

  @OptIn(ArbigentInternalApi::class)
  override fun run() {
    val ai: ArbigentAi = aiType.let { aiType ->
      when (aiType) {
        is OpenAIAiConfig -> OpenAIAi(
          apiKey = System.getenv("OPENAI_API_KEY")
            ?: throw IllegalArgumentException("Environment variable OPENAI_API_KEY is not set"),
          baseUrl = aiType.openAiEndpoint,
          modelName = aiType.openAiModelName,
        )

        is GeminiAiConfig -> OpenAIAi(
          apiKey = System.getenv("GEMINI_API_KEY")
            ?: throw IllegalArgumentException("Environment variable GEMINI_API_KEY is not set"),
          baseUrl = aiType.geminiEndpoint,
          modelName = aiType.geminiModelName,
        )

        is AzureOpenAiConfig -> OpenAIAi(
          apiKey = System.getenv("AZURE_OPENAI_API_KEY")
            ?: throw IllegalArgumentException("Environment variable AZURE_OPENAI_API_KEY is not set"),
          baseUrl = aiType.azureOpenAIEndpoint,
          modelName = aiType.azureOpenAIModelName,
          requestBuilderModifier = {
            parameter("api-version", aiType.azureOpenAIApiVersion)
            header("api-key", System.getenv("AZURE_OPENAI_API_KEY").orEmpty())
          }
        )
      }
    }

    val os =
      DeviceOs.entries.find { it.name.toLowerCasePreservingASCIIRules() == os.toLowerCasePreservingASCIIRules() }
        ?: throw IllegalArgumentException("Invalid OS. The OS should be one of ${
          DeviceOs.values().joinToString(", ") { it.name.toLowerCasePreservingASCIIRules() }
        }")
    val device = fetchAvailableDevicesByOs(os).first().connectToDevice()

    runBlocking {
      val arbigentProject = ArbigentProject(
        file = File(scenarioFile),
        aiFactory = { ai },
        deviceFactory = { device }
      )
      arbigentProject.execute()
    }
  }

}

fun main(args: Array<String>) = ArbigentCli().main(args)
