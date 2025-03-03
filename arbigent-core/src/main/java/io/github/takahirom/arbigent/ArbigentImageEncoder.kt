package io.github.takahirom.arbigent

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream
import com.luciad.imageio.webp.WebPWriteParam

public object ArbigentImageEncoder {
    public fun saveImage(image: BufferedImage, filePath: String, format: ImageFormat) {
        when (format) {
            ImageFormat.PNG -> savePng(image, filePath)
            ImageFormat.WEBP -> saveWebP(image, filePath)
            ImageFormat.LOSSY_WEBP -> saveLossyWebP(image, filePath)
        }
    }

    private fun savePng(image: BufferedImage, filePath: String) {
        ImageIO.write(image, "png", File(filePath))
    }

    private fun saveWebP(image: BufferedImage, filePath: String) {
        try {
            val writer = ImageIO.getImageWritersByMIMEType("image/webp").next()
            try {
                val writeParam = WebPWriteParam(writer.locale)
                writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
                writeParam.compressionType = "Lossless"

                writer.output = FileImageOutputStream(File(filePath))
                writer.write(null, javax.imageio.IIOImage(image, null, null), writeParam)
            } finally {
                writer.dispose()
            }
        } catch (e: NoClassDefFoundError) {
            throw IllegalStateException("Add implementation(\"io.github.darkxanter:webp-imageio:0.3.3\") to use WebP encoding", e)
        }
    }

    private fun saveLossyWebP(image: BufferedImage, filePath: String) {
        try {
            val writer = ImageIO.getImageWritersByMIMEType("image/webp").next()
            try {
                val writeParam = WebPWriteParam(writer.locale)
                writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
                writeParam.compressionType = "Lossy"
                writeParam.compressionQuality = 0.7f // 70% quality

                writer.output = FileImageOutputStream(File(filePath))
                writer.write(null, javax.imageio.IIOImage(image, null, null), writeParam)
            } finally {
                writer.dispose()
            }
        } catch (e: NoClassDefFoundError) {
            throw IllegalStateException("Add implementation(\"io.github.darkxanter:webp-imageio:0.3.3\") to use lossy WebP encoding", e)
        }
    }
}
