package io.github.takahirom.arbigent.ui

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlin.reflect.KProperty


val yaml = Yaml(
  configuration = YamlConfiguration(
    encodeDefaults = false,
    strictMode = false,
    polymorphismStyle = PolymorphismStyle.Property,
  )
)

internal object Preference {
  private var aiSetting: String by KeychainDelegate(
    default = {
      yaml
        .encodeToString(
          serializer = AiSetting.serializer(),
          value = AiSetting(
            selectedId = null,
            aiSettings = listOf(),
            loggingEnabled = false
          ),
        )
    }
  )

  private var appSetting: String by KeychainDelegate(
    default = {
      yaml
        .encodeToString(
          serializer = AppSettings.serializer(),
          value = AppSettings(
            workingDirectory = "",
            path = "",
            variables = null
          ),
        )
    }
  )

  var aiSettingValue: AiSetting
    get() = yaml.decodeFromString(AiSetting.serializer(), aiSetting)
      .let { savedAiSetting: AiSetting ->
        savedAiSetting.copy(
          aiSettings = savedAiSetting.aiSettings
        )
      }
    set(value) {
      aiSetting = yaml.encodeToString(AiSetting.serializer(), value)
    }

  var appSettingValue: AppSettings
    get() = yaml.decodeFromString(AppSettings.serializer(), appSetting)
    set(value) {
      appSetting = yaml.encodeToString(AppSettings.serializer(), value)
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

// Each keychain read can make the OS ask the user for access, so a value is read at most once per
// process and kept in step with every write.
internal class KeychainDelegate(
  private val domain: String = "io.github.takahirom.arbigent",
  private val accountPrefix: String = System.getProperty("user.name"),
  private val keyStoreFactory: () -> KeyStore = globalKeyStoreFactory,
  private val default: () -> String = { "" }
) {
  operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): Entry =
    Entry(account = accountPrefix + "-" + property.name)

  inner class Entry(private val account: String) {
    @Volatile
    private var value: Lazy<String> = lazy {
      try {
        keyStoreFactory().getPassword(domain, account).ifBlank { default() }
      } catch (ex: PasswordAccessException) {
        default()
      }
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): String = value.value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) {
      if (value != null) {
        keyStoreFactory().setPassword(domain, account, value)
      } else {
        keyStoreFactory().deletePassword(domain, account)
      }
      this.value = lazyOf(value?.ifBlank { null } ?: default())
    }
  }
}
