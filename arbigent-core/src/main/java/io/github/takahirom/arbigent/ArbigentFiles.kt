package io.github.takahirom.arbigent

import java.io.File

public object ArbigentFiles {
  public var parentDir: String = System.getProperty("java.io.tmpdir") + File.separator + "arbigent"
  public var screenshotsDir: File =
    File(parentDir + File.separator + "screenshots")
  public var jsonlsDir: File =
    File(parentDir + File.separator + "jsonls")
  public var logFile: File? = File(parentDir + File.separator + "arbigent.log")
  public var cacheDir: File = File(parentDir + File.separator + "cache" + File.separator + BuildConfig.VERSION_NAME)
}