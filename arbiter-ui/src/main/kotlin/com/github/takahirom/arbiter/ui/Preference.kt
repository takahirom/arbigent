package com.github.takahirom.arbiter.ui

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlin.reflect.KProperty

enum class AiProvider(val displayName: String) {
  OpenAi("OpenAI"),
  Gemini("Gemini");
  companion object {
    fun fromDisplayName(displayName: String): AiProvider {
      return values().firstOrNull() { it.displayName == displayName } ?: OpenAi
    }
  }
}

internal object Preference {
  private var aiProvider: String by KeychainDelegate()
  var aiProviderEnum: AiProvider
    get() = AiProvider.fromDisplayName(aiProvider)
    set(value) {
      aiProvider = value.displayName
    }
  var openAiApiKey: String by KeychainDelegate()
  var openAiModelName: String by KeychainDelegate(default = "gpt-4o-mini")
  var geminiApiKey: String by KeychainDelegate()
  var geminiModelName: String by KeychainDelegate(default = "gemini-1.5-flash")
}

private class KeychainDelegate(
  private val domain: String = "io.github.takahirom.arbiter",
  private val accountPrefix: String = System.getProperty("user.name"),
  private val default: String = ""
) {
  operator fun getValue(thisRef: Any?, property: KProperty<*>): String {
    return try {
      Keyring.create().use { keyring ->
        keyring.getPassword(domain, getAccount(property)).ifBlank { default }
      }
    } catch (ex: PasswordAccessException) {
      default
    }
  }

  private fun getAccount(property: KProperty<*>) = accountPrefix + "-" + property.name

  operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) {
    Keyring.create().use { keyring ->
      if (value != null) {
        keyring.setPassword(domain, getAccount(property), value.toString())
      } else {
        keyring.deletePassword(domain, getAccount(property))
      }
    }
  }
}
