package io.github.takahirom.arbigent

import java.io.File

public object ArbigentTempDir {
  public val screenshotsDir: File =
    File(System.getProperty("java.io.tmpdir") + File.separator + "arbigent" + File.separator + "screenshots")
}