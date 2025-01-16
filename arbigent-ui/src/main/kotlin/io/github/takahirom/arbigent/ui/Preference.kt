package io.github.takahirom.arbigent.ui

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.github.javakeyring.PasswordAccessException
import kotlin.reflect.KProperty


val aiSettingYaml = Yaml(
  configuration = YamlConfiguration(
    encodeDefaults = false,
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
            aiSettings = defaultAiProviderSettings()
          ),
        )
    }
  )

  var aiSettingValue: AiSetting
    get() = aiSettingYaml.decodeFromString(AiSetting.serializer(), aiSetting)
      .let { savedAiSetting: AiSetting ->
        savedAiSetting.copy(
          aiSettings = savedAiSetting.aiSettings + defaultAiProviderSettings()
            .filter { defaultAiProviderSetting ->
              savedAiSetting.aiSettings.none { it.id == defaultAiProviderSetting.id }
            }
        )
      }
    set(value) {
      aiSetting = aiSettingYaml.encodeToString(AiSetting.serializer(), value)
    }
}

private fun defaultAiProviderSettings() = listOf(
  AiProviderSetting.OpenAi(
    id = "defaultOpenAi",
    apiKey = "",
    modelName = "gpt-4o-mini"
  ),
  AiProviderSetting.CustomOpenAiApiBasedAi(
    id = "defaultCustomOpenAiApiBasedAi",
    apiKey = "",
    modelName = "(e.g. llama3.2-vision)",
    baseUrl = "https://"
  ),
  AiProviderSetting.Gemini(
    id = "defaultGemini",
    apiKey = "",
    modelName = "gemini-1.5-flash"
  ),
  AiProviderSetting.AzureOpenAi(
    id = "defaultAzureOpenAi",
    apiKey = "",
    modelName = "gpt-4o-mini",
    endpoint = "https://YOUR_RESOURCE_NAME.openai.azure.com/openai/deployments/YOUR_DEPLOYMENT_NAME/",
    apiVersion = "2024-10-21"
  )
)


internal var globalKeyStoreFactory: () -> KeyStore = OnMemoryKeyStoreFactory()

private class OnMemoryKeyStoreFactory : () -> KeyStore {
  private val keys = mutableMapOf<String, String>()
  override fun invoke(): KeyStore {
    return object : KeyStore {
      override fun getPassword(domain: String, account: String): String {
        return keys["$domain-$account"] ?: throw PasswordAccessException("Not found")
      }

      override fun setPassword(domain: String, account: String, password: String) {
        keys["$domain-$account"] = password
      }

      override fun deletePassword(domain: String, account: String) {
        keys.remove("$domain-$account")
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
  private val domain: String = "io.github.takahirom.arbigent",
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
