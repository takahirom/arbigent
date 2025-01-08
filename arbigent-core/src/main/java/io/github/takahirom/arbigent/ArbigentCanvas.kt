package io.github.takahirom.arbigent

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

public enum class CompositeMode {
  SrcOver,
  Src,
}

internal val colors = listOf(
  0x3F9101,
  0x0E4A8E,
  0xBCBF01,
  0xBC0BA2,
  0x61AA0D,
  0x3D017A,
  0xD6A60A,
  0x7710A3,
  0xA502CE,
  0xeb5a00
)

public class ArbigentCanvas(width: Int, height: Int, bufferedImageType: Int) {
  private val bufferedImage: BufferedImage = BufferedImage(width, height, bufferedImageType)

  public fun drawImage(image: BufferedImage, compositeMode: CompositeMode = CompositeMode.SrcOver) {
    bufferedImage.graphics { graphics2D ->
      graphics2D.setComposite(
        when (compositeMode) {
          CompositeMode.SrcOver -> AlphaComposite.SrcOver
          CompositeMode.Src -> AlphaComposite.Src
        }
      )
      graphics2D.drawImage(
        image,
        0,
        0,
        null
      )
    }
  }


  public fun draw(elements: ArbigentElementList) {
    bufferedImage.graphics { graphics2D ->
      elements.elements.forEach { element ->
        val text = element.index.toString()
        val color = Color(colors[element.index % colors.size])
        drawRectOutline(element.rect, color)

        val (rawBoxWidth, rawBoxHeight) = textCalc(listOf(text))
        val textPadding = 5
        val boxWidth = rawBoxWidth + textPadding * 2
        val boxHeight = rawBoxHeight + textPadding * 2
        val topTextRect = ArbigentElement.Rect(
          element.rect.left,
          element.rect.top - boxHeight,
          element.rect.left + boxWidth,
          element.rect.top
        )
        drawRect(topTextRect, color)
        drawText(
          (topTextRect.left + textPadding).toFloat(),
          (topTextRect.top + textPadding + rawBoxHeight).toFloat(),
          listOf(text),
          Color.WHITE
        )
      }
    }
  }

  public fun textCalc(texts: List<String>): Pair<Int, Int> {
    return bufferedImage.graphics { graphics2D ->
      val frc: FontRenderContext = graphics2D.getFontRenderContext()
      val longestLineWidth = texts.map {
        calcTextLayout(
          it,
          graphics2D,
          frc
        ).getPixelBounds(frc, 0F, 0F).width
      }.maxBy {
        it
      }
      longestLineWidth to (texts.sumOf {
        calcTextLayout(it, graphics2D, frc).bounds.height + 1
      }).toInt()
    }
  }

  public fun drawRect(r: ArbigentElement.Rect, color: Color) {
    bufferedImage.graphics { graphics2D ->
      graphics2D.color = color
      graphics2D.fillRect(
        r.left, r.top,
        (r.right - r.left), (r.bottom - r.top)
      )
    }
  }

  public fun drawText(textPointX: Float, textPointY: Float, texts: List<String>, color: Color) {
    bufferedImage.graphics {
      val graphics2D = bufferedImage.createGraphics()
      graphics2D.color = color

      val frc: FontRenderContext = graphics2D.getFontRenderContext()

      var nextY = textPointY
      for (text in texts) {
        val layout = calcTextLayout(text, graphics2D, frc)
        val height = layout.bounds.height
        layout.draw(
          graphics2D,
          textPointX,
          nextY
        )
        nextY += (height + 1).toInt()
      }
    }
  }

  private fun drawRectOutline(r: ArbigentElement.Rect, color: Color) {
    bufferedImage.graphics { graphics2D ->
      graphics2D.color = color
      val stroke = BasicStroke(minOf(r.width().toFloat(), r.height().toFloat()) / 20)
      graphics2D.setStroke(stroke)
      graphics2D.drawRect(
        r.left, r.top,
        (r.right - r.left), (r.bottom - r.top)
      )
    }
  }

  private val textCache = hashMapOf<String, TextLayout>()

  private fun calcTextLayout(
    text: String,
    graphics2D: Graphics2D,
    frc: FontRenderContext
  ) = textCache.getOrPut(text) {
    TextLayout(text, graphics2D.font, frc)
  }

  public fun save(screenshotFilePath: String) {
    ImageIO.write(bufferedImage, "png", File(screenshotFilePath))
  }

  public companion object {
    public fun load(file: File, bufferedImageType: Int): ArbigentCanvas {
      val bufferedImage = ImageIO.read(file)
      val canvas = ArbigentCanvas(bufferedImage.width, bufferedImage.height,bufferedImageType)
      canvas.drawImage(bufferedImage, CompositeMode.Src)
      return canvas
    }
  }
}

private fun <T> BufferedImage.graphics(block: (Graphics2D) -> T): T {
  val graphics = createGraphics()
  graphics.font = Font("Courier New", Font.BOLD, 12)
  val result = block(graphics)
  graphics.dispose()
  return result
}
