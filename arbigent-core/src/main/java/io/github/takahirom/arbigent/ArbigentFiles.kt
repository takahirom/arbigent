package io.github.takahirom.arbigent

import java.io.File

public object ArbigentFiles {
  public var screenshotsDir: File =
    File(System.getProperty("java.io.tmpdir") + File.separator + "arbigent" + File.separator + "screenshots")
  public var jsonlsDir: File =
    File(System.getProperty("java.io.tmpdir") + File.separator + "arbigent" + File.separator + "jsonls")
  public var logFile: File? = File(System.getProperty("java.io.tmpdir") + File.separator + "arbigent" + File.separator + "arbigent.log")
  public var cacheDir: File = File(System.getProperty("java.io.tmpdir") + File.separator + "arbigent" + File.separator + "cache" + File.separator + BuildConfig.VERSION_NAME)
}