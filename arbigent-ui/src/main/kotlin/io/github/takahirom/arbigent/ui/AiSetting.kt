package io.github.takahirom.arbigent.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AiSetting(
  val selectedId: String,
  val aiSettings: List<AiProviderSetting>
)

@Serializable
sealed interface AiProviderSetting {
  val id: String
  val name: String

  interface NormalAiProviderSetting : AiProviderSetting {
    val apiKey: String
    val modelName: String
    fun updatedApiKey(apiKey: String): NormalAiProviderSetting
    fun updatedModelName(modelName: String): NormalAiProviderSetting
  }

  interface OpenAiBasedApiProviderSetting : NormalAiProviderSetting {
    val baseUrl: String
  }

  @Serializable
  @SerialName("Gemini")
  data class Gemini(
    override val id: String,
    override val apiKey: String,
    override val modelName: String
  ) : AiProviderSetting, OpenAiBasedApiProviderSetting {
    override val name: String
      get() = "Gemini"

    override fun updatedApiKey(apiKey: String): NormalAiProviderSetting {
      return copy(apiKey = apiKey)
    }

    override fun updatedModelName(modelName: String): NormalAiProviderSetting {
      return copy(modelName = modelName)
    }

    override val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/openai/"
  }

  @Serializable
  @SerialName("OpenAi")
  data class OpenAi(
    override val id: String,
    override val apiKey: String,
    override val modelName: String
  ) : AiProviderSetting, OpenAiBasedApiProviderSetting {
    override val name: String
      get() = "OpenAi"

    override fun updatedApiKey(apiKey: String): NormalAiProviderSetting {
      return copy(apiKey = apiKey)
    }

    override fun updatedModelName(modelName: String): NormalAiProviderSetting {
      return copy(modelName = modelName)
    }

    override val baseUrl: String = "https://api.openai.com/v1/"
  }

  // https://learn.microsoft.com/en-us/azure/ai-services/openai/reference#chat-completions
  @Serializable
  @SerialName("AzureOpenAi")
  data class AzureOpenAi(
    override val id: String,
    val apiKey: String,
    val modelName: String,
    val endpoint: String,
    val apiVersion: String,
  ) : AiProviderSetting {
    override val name: String
      get() = "Azure OpenAi"

    fun updatedApiKey(apiKey: String): AzureOpenAi {
      return copy(apiKey = apiKey)
    }

    fun updatedModelName(modelName: String): AzureOpenAi {
      return copy(modelName = modelName)
    }

    fun updatedEndpoint(endpoint: String): AzureOpenAi {
      return copy(endpoint = endpoint)
    }

    fun updatedApiVersion(apiVersion: String): AzureOpenAi {
      return copy(apiVersion = apiVersion)
    }
  }
}
