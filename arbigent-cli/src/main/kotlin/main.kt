package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
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
import io.ktor.util.toLowerCasePreservingASCIIRules
import kotlinx.coroutines.runBlocking
import java.io.File

class ArbigentCli : CliktCommand() {

  val aiType by option(help = "Type of AI to use")
    .choice("openai", "gemini")
    .default("openai")

  val os by option(help = "Target operating system")
    .choice("android", "ios", "web")
    .default("android")

  val scenarioFile by option(help = "Path to the scenario YAML file")
    .prompt("Scenario file path")

  @OptIn(ArbigentInternalApi::class)
  override fun run() {
    val ai: ArbigentAi = when (aiType) {
      "openai" -> OpenAIAi(
        apiKey = System.getenv("OPENAI_API_KEY") ?: throw IllegalArgumentException("Environment variable OPENAI_API_KEY is not set"),
        baseUrl = "https://api.openai.com/v1/"
      )

      "gemini" -> OpenAIAi(
        apiKey = System.getenv("GEMINI_API_KEY") ?: throw IllegalArgumentException("Environment variable GEMINI_API_KEY is not set"),
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/"
      )

      else -> throw IllegalArgumentException("Invalid AI type")
    }

    val os = DeviceOs.entries.find { it.name.toLowerCasePreservingASCIIRules() == os.toLowerCasePreservingASCIIRules() }
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
