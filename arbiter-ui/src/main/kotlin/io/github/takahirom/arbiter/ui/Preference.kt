package io.github.takahirom.arbiter.ui

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.reflect.KProperty


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
}

val aiSettingYaml = Yaml(
  configuration = YamlConfiguration(
    strictMode = false,
    polymorphismStyle = PolymorphismStyle.Property
  )
)

internal object Preference {
  private var aiSetting: String by KeychainDelegate(
    default = {
      aiSettingYaml
        .encodeToString(
          serializer = AiSetting.serializer(),
          value = AiSetting(
            selectedId = "defaultOpenAi",
            aiSettings = listOf(
              AiProviderSetting.OpenAi(
                id = "defaultOpenAi",
                apiKey = "",
                modelName = "gpt-4o-mini"
              ),
              AiProviderSetting.Gemini(
                id = "defaultGemini",
                apiKey = "",
                modelName = "gemini-1.5-flash"
              )
            )
          ),
        )
    }
  )

  var aiSettingValue: AiSetting
    get() = aiSettingYaml.decodeFromString(AiSetting.serializer(), aiSetting)
    set(value) {
      aiSetting = aiSettingYaml.encodeToString(AiSetting.serializer(), value)
    }
}

internal var globalKeyStoreFactory: () -> KeyStore = {
  object : KeyStore {
    override fun getPassword(domain: String, account: String): String {
      val keying = Keyring.create()
      return keying.use {
        it.getPassword(domain, account)
      }
    }

    override fun setPassword(domain: String, account: String, password: String) {
      val keying = Keyring.create()
      keying.use {
        it.setPassword(domain, account, password)
      }
    }

    override fun deletePassword(domain: String, account: String) {
      val keying = Keyring.create()
      keying.use {
        it.deletePassword(domain, account)
      }
    }
  }
}

internal interface KeyStore {
  fun getPassword(domain: String, account: String): String
  fun setPassword(domain: String, account: String, password: String)
  fun deletePassword(domain: String, account: String)
}

private class KeychainDelegate(
  private val domain: String = "io.github.takahirom.arbiter",
  private val accountPrefix: String = System.getProperty("user.name"),
  private val keyStoreFactory: () -> KeyStore = globalKeyStoreFactory,
  private val default: () -> String = { "" }
) {
  operator fun getValue(thisRef: Any?, property: KProperty<*>): String {
    return try {
      keyStoreFactory().getPassword(domain, getAccount(property)).ifBlank { default() }
    } catch (ex: PasswordAccessException) {
      default()
    }
  }

  private fun getAccount(property: KProperty<*>) = accountPrefix + "-" + property.name

  operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) {
    if (value != null) {
      keyStoreFactory().setPassword(domain, getAccount(property), value)
    } else {
      keyStoreFactory().deletePassword(domain, getAccount(property))
    }
  }
}
