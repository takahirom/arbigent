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
