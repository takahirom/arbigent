package com.github.takahirom.arbiter.sample.test

import com.github.takahirom.arbiter.*
import dadb.Dadb
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes


@Ignore("Currently this test is not working on CI")
class RealArbiterTest {
  private val scenarioFile = File(this::class.java.getResource("/projects/nowinandroidsample.yaml").toURI())

  @Test
  fun tests() = runTest(
    timeout = 10.minutes
  ) {
    val arbiterProject = ArbiterProject(
      file = scenarioFile,
      aiFactory = {
        OpenAIAi(
          apiKey = System.getenv("OPENAI_API_KEY")
        )
      },
      deviceFactory = {
        AvailableDevice.Android(
          dadb = Dadb.discover()!!
        ).connectToDevice()
      }
    )
    arbiterProject.execute()
  }
}
