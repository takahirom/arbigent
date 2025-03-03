package io.github.takahirom.arbigent

import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class ArbigentImageEncoderTest {
    @Test
    fun testPngEncoding() {
        val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val tempFile = File.createTempFile("test", ".png")
        tempFile.deleteOnExit()

        ArbigentImageEncoder.saveImage(image, tempFile.absolutePath, ImageFormat.PNG)

        assertTrue(tempFile.exists())
        assertTrue(tempFile.length() > 0)
    }

    @Test
    fun testWebPEncoding() {
        val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val tempFile = File.createTempFile("test", ".webp")
        tempFile.deleteOnExit()

        ArbigentImageEncoder.saveImage(image, tempFile.absolutePath, ImageFormat.WEBP)

        assertTrue(tempFile.exists())
        assertTrue(tempFile.length() > 0)

        // Verify it's a valid WebP file by checking its header
        val bytes = tempFile.readBytes()
        assertTrue(bytes.size >= 12, "WebP file too small")
        val header = String(bytes.sliceArray(8..11))
        assertTrue(header == "WEBP", "Invalid WebP header: $header")
    }

    @Test
    fun testLossyWebPEncoding() {
        // Create a test image with gradient and noise for better compression test
        val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
        val g = image.graphics
        // Create a gradient
        for (x in 0 until 200) {
            for (y in 0 until 200) {
                val red = (x * 255 / 200)
                val blue = (y * 255 / 200)
                val green = ((x + y) * 255 / 400)
                g.color = java.awt.Color(red, green, blue)
                g.fillRect(x, y, 1, 1)
            }
        }
        g.dispose()

        // Save as lossy WebP
        val lossyFile = File.createTempFile("test_lossy", ".webp")
        lossyFile.deleteOnExit()

        ArbigentImageEncoder.saveImage(image, lossyFile.absolutePath, ImageFormat.LOSSY_WEBP)

        // Verify file exists and is valid
        assertTrue(lossyFile.exists(), "Lossy WebP file should exist")
        assertTrue(lossyFile.length() > 0, "Lossy WebP file should not be empty")

        // Verify it's a valid WebP file
        val bytes = lossyFile.readBytes()
        assertTrue(bytes.size >= 12, "WebP file too small")
        val header = String(bytes.sliceArray(8..11))
        assertTrue(header == "WEBP", "Invalid WebP header: $header")

        // Load the image back to verify it's readable
        val loadedImage = ImageIO.read(lossyFile)
        assertNotNull(loadedImage, "Should be able to read the WebP image back")
        assertEquals(200, loadedImage.width, "Image width should be preserved")
        assertEquals(200, loadedImage.height, "Image height should be preserved")
    }
}
