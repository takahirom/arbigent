package io.github.takahirom.arbigent.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class KeychainDelegateTest {
  private class CountingKeyStore(val keys: MutableMap<String, String> = mutableMapOf()) : KeyStore {
    var reads = 0
    override fun getPassword(domain: String, account: String): String {
      reads++
      return keys[account] ?: ""
    }

    override fun setPassword(domain: String, account: String, password: String) {
      keys[account] = password
    }

    override fun deletePassword(domain: String, account: String) {
      keys.remove(account)
    }
  }

  private class Holder(keyStore: KeyStore) {
    var setting: String by KeychainDelegate(accountPrefix = "test", keyStoreFactory = { keyStore }, default = { "default" })
  }

  @Test
  fun `the keychain is read once however often the value is`() {
    val keyStore = CountingKeyStore(mutableMapOf("test-setting" to "saved"))
    val holder = Holder(keyStore)

    repeat(3) { assertEquals("saved", holder.setting) }
    assertEquals(1, keyStore.reads)
  }

  @Test
  fun `a write is what later reads see, without reading the keychain again`() {
    val keyStore = CountingKeyStore()
    val holder = Holder(keyStore)

    holder.setting = "new"
    assertEquals("new", holder.setting)
    assertEquals("new", keyStore.keys["test-setting"])

    holder.setting = ""
    assertEquals("default", holder.setting)
    assertEquals(0, keyStore.reads)
  }
}
