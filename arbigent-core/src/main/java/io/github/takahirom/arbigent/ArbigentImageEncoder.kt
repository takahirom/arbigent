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

                // writer.dispose() does not close the output, and the decision request reads this
                // file back as soon as it is written, so close it here.
                FileImageOutputStream(File(filePath)).use { output ->
                    writer.output = output
                    writer.write(null, javax.imageio.IIOImage(image, null, null), writeParam)
                }
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

                // writer.dispose() does not close the output, and the decision request reads this
                // file back as soon as it is written, so close it here.
                FileImageOutputStream(File(filePath)).use { output ->
                    writer.output = output
                    writer.write(null, javax.imageio.IIOImage(image, null, null), writeParam)
                }
            } finally {
                writer.dispose()
            }
        } catch (e: NoClassDefFoundError) {
            throw IllegalStateException("Add implementation(\"io.github.darkxanter:webp-imageio:0.3.3\") to use lossy WebP encoding", e)
        }
    }
}
