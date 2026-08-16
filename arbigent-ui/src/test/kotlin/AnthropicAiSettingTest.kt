package io.github.takahirom.arbigent.ui

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnthropicAiSettingTest {

  @Before
  fun setup() {
    // Set up test keystore to avoid BackendNotSupportedException
    globalKeyStoreFactory = TestKeyStoreFactory()
  }

  @After
  fun tearDown() {
    Preference.aiSettingValue = AiSetting(selectedId = null, aiSettings = listOf(), loggingEnabled = false)
  }

  private fun anthropicProvider() = AiProviderSetting.Anthropic(
    id = "test-anthropic",
    apiKey = "sk-ant-test-key",
    modelName = "claude-sonnet-4-5",
  )

  @Test
  fun `Anthropic provider uses the documented default base URL`() {
    assertEquals("https://api.anthropic.com/v1/", anthropicProvider().baseUrl)
  }

  @Test
  fun `Anthropic provider name and required fields`() {
    val provider = anthropicProvider()

    assertEquals("Anthropic", provider.name)
    assertTrue(provider.isApiKeyRequired)
  }

  @Test
  fun `updatedApiKey and updatedModelName and updatedBaseUrl return copies with the new value`() {
    val provider = anthropicProvider()

    val withNewKey = provider.updatedApiKey("new-key")
    val withNewModel = provider.updatedModelName("claude-opus-4-1")
    val withNewBaseUrl = provider.updatedBaseUrl("https://my-anthropic-proxy.example.com/v1/")

    assertEquals("new-key", (withNewKey as AiProviderSetting.Anthropic).apiKey)
    assertEquals("claude-opus-4-1", (withNewModel as AiProviderSetting.Anthropic).modelName)
    assertEquals("https://my-anthropic-proxy.example.com/v1/", withNewBaseUrl.baseUrl)
    // Original instance is unchanged (data class copy, not mutation)
    assertEquals("sk-ant-test-key", provider.apiKey)
  }

  @Test
  fun `Anthropic provider round-trips through yaml serialization`() {
    val provider = anthropicProvider()

    val encoded = yaml.encodeToString(AiProviderSetting.serializer(), provider)
    val decoded = yaml.decodeFromString(AiProviderSetting.serializer(), encoded)

    assertEquals(provider, decoded)
    assertTrue(decoded is AiProviderSetting.Anthropic)
  }

  @Test
  fun `Anthropic provider round-trips through Preference aiSettingValue`() {
    val provider = anthropicProvider()

    Preference.aiSettingValue = AiSetting(
      selectedId = provider.id,
      aiSettings = listOf(provider),
      loggingEnabled = true,
    )

    val reloaded = Preference.aiSettingValue
    val reloadedProvider = reloaded.aiSettings.single()

    assertEquals(provider.id, reloaded.selectedId)
    assertEquals(provider, reloadedProvider)
  }

  @Test
  fun `AiSetting with a mix of provider types round-trips correctly`() {
    val openAi = AiProviderSetting.OpenAi(id = "openai-1", apiKey = "openai-key", modelName = "gpt-4.1")
    val anthropic = anthropicProvider()

    Preference.aiSettingValue = AiSetting(
      selectedId = anthropic.id,
      aiSettings = listOf(openAi, anthropic),
    )

    val reloaded = Preference.aiSettingValue

    assertEquals(listOf(openAi, anthropic), reloaded.aiSettings)
  }
}
