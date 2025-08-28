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
    // Pre-connect within runTest coroutine and reuse
    val connectedDevice = ArbigentAvailableDevice.Android(
      dadb = Dadb.discover() ?: error("No Android device discovered for test")
    ).connectToDevice()
    
    val arbigentProject = ArbigentProject(
      file = scenarioFile,
      aiFactory = {
        OpenAIAi(
          apiKey = System.getenv("OPENAI_API_KEY"),
          loggingEnabled = false,
        )
      },
      deviceFactory = {
        connectedDevice
      },
      appSettings = DefaultArbigentAppSettings
    )
    arbigentProject.execute()
  }
}
