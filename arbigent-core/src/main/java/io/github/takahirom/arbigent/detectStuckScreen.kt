package io.github.takahirom.arbigent

import java.io.File
import javax.imageio.ImageIO

internal fun detectStuckScreen(lastScreenshot: String?, newScreenshot: File): Boolean {
  if (lastScreenshot == null) {
    return false
  }
  val lastScreenshotFile = File(lastScreenshot)
  if (lastScreenshotFile.exists().not()) {
    return false
  }
  val oldBufferedImage = ImageIO.read(lastScreenshotFile)
  val newBufferedImage = ImageIO.read(newScreenshot)
  try {
    if (oldBufferedImage.width != newBufferedImage.width || oldBufferedImage.height != newBufferedImage.height) {
      return false
    }
    for (x in 0 until oldBufferedImage.width) {
      for (y in 0 until oldBufferedImage.height) {
        if (oldBufferedImage.getRGB(x, y) != newBufferedImage.getRGB(x, y)) {
          return false
        }
      }
    }
    return true
  } finally {
    oldBufferedImage.flush()
    newBufferedImage.flush()
  }
}