package io.github.takahirom.arbigent

import java.io.File

public object ArbigentFiles {
  public var parentDir: String = System.getProperty("java.io.tmpdir") + File.separator + "arbigent"
  public var screenshotsDir: File =
    File(parentDir + File.separator + "screenshots")
  public var jsonlsDir: File =
    File(parentDir + File.separator + "jsonls")

  /**
   * Token usage of every recorded API response, one file per call. Unlike [jsonlsDir] this also
   * covers calls that do not go through the decision path, such as image assertions.
   */
  public var usagesDir: File =
    File(parentDir + File.separator + "usages")
  public var logFile: File? = File(parentDir + File.separator + "arbigent.log")
  public var cacheDir: File = File(parentDir + File.separator + "cache" + File.separator + BuildConfig.VERSION_NAME)
  public var traceDir: File = File(parentDir + File.separator + "traces")
}
