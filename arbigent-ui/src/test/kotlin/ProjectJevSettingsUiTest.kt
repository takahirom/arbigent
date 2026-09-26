@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.ui

import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.ArbigentJevMode
import io.github.takahirom.arbigent.ArbigentJevSettings
import io.github.takahirom.arbigent.ArbigentProjectSerializer
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/** The UI has no Jev editor yet, so a load/save cycle must keep `settings.jev` as written. */
class ProjectJevSettingsUiTest {
  @Before
  fun setup() {
    globalKeyStoreFactory = TestKeyStoreFactory()
  }

  @Test
  fun `saving keeps the project's jev settings`() {
    val app = ArbigentAppStateHolder(aiFactory = { FakeAi() }, dispatcher = Dispatchers.Unconfined, onRecoverableError = { throw it })
    val file = File.createTempFile("arbigent-jev", ".yml")
    file.writeText(
      """
      scenarios: []
      settings:
        jev:
          mode: Shadow
          actionThreshold: 0.9
          goalThreshold: 0.95
      """.trimIndent()
    )

    app.loadProjectContents(file)
    app.saveProjectContents(file)

    assertEquals(
      ArbigentJevSettings(mode = ArbigentJevMode.Shadow, actionThreshold = 0.9, goalThreshold = 0.95),
      ArbigentProjectSerializer().load(file).settings.jev,
    )
    file.delete()
  }
}
