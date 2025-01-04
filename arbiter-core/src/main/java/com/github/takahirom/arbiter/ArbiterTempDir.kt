package com.github.takahirom.arbiter

import java.io.File

public object ArbiterTempDir {
  public val screenshotsDir: File =
    File(System.getProperty("java.io.tmpdir") + File.separator + "arbiter" + File.separator + "screenshots")
}