package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import dadb.Dadb
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes


@Ignore("Currently this test is not working on CI")
class RealArbigentTest {
  private val scenarioFile = File(this::class.java.getResource("/projects/nowinandroidsample.yaml").toURI())

  @Test
  fun tests() = runTest(
    timeout = 10.minutes
  ) {
    val arbigentProject = ArbigentProject(
      file = scenarioFile,
      aiFactory = {
        OpenAIAi(
          apiKey = System.getenv("OPENAI_API_KEY"),
          loggingEnabled = false,
        )
      },
      deviceFactory = {
        ArbigentAvailableDevice.Android(
          dadb = Dadb.discover()!!
        ).let { kotlinx.coroutines.runBlocking { it.connectToDevice() } }
      },
      appSettings = DefaultArbigentAppSettings
    )
    arbigentProject.execute()
  }
}
