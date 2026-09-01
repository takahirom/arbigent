package io.github.takahirom.arbigent

import kotlin.test.Test
import kotlin.test.assertEquals

class AssertionScreenshotHistoryTest {
  @Test
  fun `the history starts at the screenshot immediately before the one being judged`() {
    assertEquals(
      listOf("s3", "s2"),
      assertionScreenshotFilePaths(
        currentScreenshotFilePath = "s3",
        previousScreenshotFilePaths = listOf("s2", "s1"),
        historyCount = 2,
      ),
    )
  }

  @Test
  fun `the second step of a task already has a previous screenshot to compare`() {
    assertEquals(
      listOf("s2", "s1"),
      assertionScreenshotFilePaths(
        currentScreenshotFilePath = "s2",
        previousScreenshotFilePaths = listOf("s1"),
        historyCount = 2,
      ),
    )
  }

  @Test
  fun `the first step of a task has nothing to compare against`() {
    assertEquals(
      listOf("s1"),
      assertionScreenshotFilePaths(
        currentScreenshotFilePath = "s1",
        previousScreenshotFilePaths = emptyList(),
        historyCount = 2,
      ),
    )
  }

  @Test
  fun `a step with no history to offer takes the missing frames now`() {
    val captured = mutableListOf<String>()
    assertEquals(
      listOf("s1", "extra1", "extra2"),
      assertionScreenshotFilePaths(
        currentScreenshotFilePath = "s1",
        previousScreenshotFilePaths = emptyList(),
        historyCount = 3,
        captureAdditionalScreenshot = {
          "extra${captured.size + 1}".also { captured += it }
        },
      ),
    )
    assertEquals(2, captured.size)
  }

  @Test
  fun `a history that is already full takes no extra screenshot`() {
    var captureCount = 0
    assertEquals(
      listOf("s3", "s2"),
      assertionScreenshotFilePaths(
        currentScreenshotFilePath = "s3",
        previousScreenshotFilePaths = listOf("s2", "s1"),
        historyCount = 2,
        captureAdditionalScreenshot = {
          captureCount++
          "extra"
        },
      ),
    )
    assertEquals(0, captureCount)
  }

  @Test
  fun `a screenshot that could not be taken leaves the assertion with what it has`() {
    assertEquals(
      listOf("s1"),
      assertionScreenshotFilePaths(
        currentScreenshotFilePath = "s1",
        previousScreenshotFilePaths = emptyList(),
        historyCount = 2,
        captureAdditionalScreenshot = { null },
      ),
    )
  }

  @Test
  fun `a history count of one asks about the current screenshot alone`() {
    assertEquals(
      listOf("s3"),
      assertionScreenshotFilePaths(
        currentScreenshotFilePath = "s3",
        previousScreenshotFilePaths = listOf("s2", "s1"),
        historyCount = 1,
      ),
    )
  }
}
