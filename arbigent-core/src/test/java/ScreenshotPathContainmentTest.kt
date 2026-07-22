package io.github.takahirom.arbigent.test

import io.github.takahirom.arbigent.resolveScreenshotFile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScreenshotPathContainmentTest {

  private fun newBaseDir(): File = Files.createTempDirectory("screenshots").toFile()

  @Test
  fun resolvesSimpleNameInsideBase() {
    val base = newBaseDir()
    val file = resolveScreenshotFile(base, "shot")
    assertEquals(File(base.canonicalFile, "shot.png"), file)
    assertTrue(file.toPath().startsWith(base.canonicalFile.toPath()))
  }

  @Test
  fun resolvesNestedRelativePath() {
    val base = newBaseDir()
    val file = resolveScreenshotFile(base, "sub/dir/shot")
    assertTrue(file.parentFile.isDirectory, "nested parent should be created")
    assertTrue(file.toPath().startsWith(base.canonicalFile.toPath()))
  }

  @Test
  fun rejectsParentTraversal() {
    val base = newBaseDir()
    assertFailsWith<IllegalArgumentException> {
      resolveScreenshotFile(base, "../../etc/passwd")
    }
  }

  @Test
  fun rejectsAbsolutePath() {
    val base = newBaseDir()
    assertFailsWith<IllegalArgumentException> {
      resolveScreenshotFile(base, "/tmp/escape")
    }
  }

  @Test
  fun rejectsSymlinkEscape() {
    val base = newBaseDir()
    val outside = Files.createTempDirectory("outside").toFile()
    // A symlinked subdirectory of base that points outside must not let writes escape.
    val link = File(base, "link").toPath()
    try {
      Files.createSymbolicLink(link, outside.toPath())
    } catch (_: UnsupportedOperationException) {
      return // platform without symlink support (e.g. some Windows configs)
    }
    assertFailsWith<IllegalArgumentException> {
      resolveScreenshotFile(base, "link/shot")
    }
  }
}
