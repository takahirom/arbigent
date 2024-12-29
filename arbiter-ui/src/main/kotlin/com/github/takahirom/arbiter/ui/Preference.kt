package com.github.takahirom.arbiter.ui

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import kotlin.reflect.KProperty

internal object Preference {
  var openAiApiKey by KeychainDelegate()
}

private class KeychainDelegate(private val domain: String = "io.github.takahirom.arbiter", private val accountPrefix: String = System.getProperty("user.name")) {
  operator fun getValue(thisRef: Any?, property: KProperty<*>): String {
    return try {
      Keyring.create().use { keyring ->
        keyring.getPassword(domain, getAccount(property))
      }
    } catch (ex: PasswordAccessException) {
      ""
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
