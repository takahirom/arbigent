@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import io.github.takahirom.arbigent.ArbigentInternalApi

sealed class AiConfig(name: String) : OptionGroup(name)

class OpenAIAiConfig : AiConfig("Options for OpenAI API AI") {
  private val defaultEndpoint = "https://api.openai.com/v1/"
  val openAiEndpoint by defaultOption("--openai-endpoint", help = "Endpoint URL (default: $defaultEndpoint)")
    .default(defaultEndpoint, defaultForHelp = defaultEndpoint)
  val openAiModelName by defaultOption("--openai-model-name", help = "Model name (default: gpt-4o-mini)")
    .default("gpt-4o-mini", "gpt-4o-mini")
  val openAiApiKey by defaultOption("--openai-api-key", "--openai-key", envvar = "OPENAI_API_KEY", help = "API key")
}

class GeminiAiConfig : AiConfig("Options for Gemini API AI") {
  private val defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta/openai/"
  val geminiEndpoint by defaultOption("--gemini-endpoint", help = "Endpoint URL (default: $defaultEndpoint)")
    .default(defaultEndpoint, defaultForHelp = defaultEndpoint)
  val geminiModelName by defaultOption("--gemini-model-name", help = "Model name (default: gemini-1.5-flash)")
    .default("gemini-1.5-flash", "gemini-1.5-flash")
  val geminiApiKey by defaultOption("--gemini-api-key", envvar = "GEMINI_API_KEY", help = "API key")
}

class AzureOpenAiConfig : AiConfig("Options for Azure OpenAI") {
  val azureOpenAIEndpoint by defaultOption("--azure-openai-endpoint", help = "Endpoint URL")
  val azureOpenAIApiVersion by defaultOption("--azure-openai-api-version", help = "API version")
    .default("2024-10-21")
  val azureOpenAIModelName by defaultOption("--azure-openai-model-name", help = "Model name (default: gpt-4o-mini)")
    .default("gpt-4o-mini")
  val azureOpenAIKey by defaultOption("--azure-openai-api-key", "--azure-openai-key", envvar = "AZURE_OPENAI_API_KEY", help = "API key")
}