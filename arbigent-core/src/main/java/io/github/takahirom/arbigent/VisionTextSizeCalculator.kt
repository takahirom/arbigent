package io.github.takahirom.arbigent

import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap // For thread-safe cache
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Provides utility functions to estimate recommended font point sizes
 * for ensuring text legibility in images processed by AI vision models,
 * specifically targeting OpenAI's 'detail: high' processing (768px short side).
 * Includes caching for performance.
 */
internal object FontPointUtils {

    // --- Configuration Constants ---
    private const val HIGH_DETAIL_MAX_RES = 2048.0
    private const val HIGH_DETAIL_SHORT_SIDE_TARGET = 768.0
    private const val TARGET_MIN_TEXT_HEIGHT_ON_PROCESSED_IMAGE = 20.0
    private const val REFERENCE_POINT_SIZE = 72.0
    private const val MIN_POINT_SIZE = 1

    /**
     * Cache key definition. Combines all factors influencing the point size result.
     */
    private data class CacheKey(
        val fontName: String,
        val fontStyle: Int,
        val originalWidth: Int,
        val originalHeight: Int
        // Note: Implicitly depends on the TARGET_MIN_TEXT_HEIGHT constant and font metrics environment.
    )

    // Thread-safe cache for storing calculated point sizes.
    private val pointSizeCache: MutableMap<CacheKey, Int> = ConcurrentHashMap()

    /**
     * Estimates a recommended integer font point size for given font properties and original image dimensions.
     * Results are cached for performance. The goal is legibility after OpenAI's 'detail: high' processing.
     *
     * @param fontName The name of the font family (e.g., "Arial", "Courier New", Font.SANS_SERIF).
     * @param fontStyle The font style (e.g., Font.PLAIN, Font.BOLD, Font.ITALIC).
     * @param originalWidth The width of the original image in pixels (must be > 0).
     * @param originalHeight The height of the original image in pixels (must be > 0).
     * @param g2d An optional, pre-existing Graphics2D context. Providing one ensures consistent font metrics
     * and can improve performance if calling this function repeatedly with the same context.
     * If null, a temporary context is created for metric calculation.
     * @return The recommended integer point size (at least MIN_POINT_SIZE).
     * Returns MIN_POINT_SIZE if inputs are invalid or estimation fails.
     */
    fun estimateRecommendedPointSize(
        fontName: String,
        fontStyle: Int,
        originalWidth: Int,
        originalHeight: Int,
        g2d: Graphics2D? = null // Optional graphics context still useful
    ): Int {
        // 1. Validate inputs
        if (originalWidth <= 0 || originalHeight <= 0 || fontName.isBlank()) {
            System.err.println(
                "Warning: Invalid input provided. " +
                  "width=$originalWidth, height=$originalHeight, fontName='$fontName'. " +
                  "Returning minimum point size $MIN_POINT_SIZE."
            )
            return MIN_POINT_SIZE
        }

        // 2. Create cache key
        val key = CacheKey(fontName, fontStyle, originalWidth, originalHeight)

        // 3. Check cache first (thread-safe read)
        pointSizeCache[key]?.let { cachedSize ->
            return cachedSize
        }

        // --- Cache miss: Proceed with calculation ---

        // 4. Calculate the effective scaling factor for 'detail: high'
        val scaleFactor = calculateHighDetailScaleFactor(originalWidth.toDouble(), originalHeight.toDouble())

        if (scaleFactor <= 0) {
            System.err.println(
                "Warning: Could not calculate a valid scaling factor for dimensions ${originalWidth}x${originalHeight}. " +
                  "Returning minimum point size $MIN_POINT_SIZE."
            )
            // Do not cache failure cases related to invalid dimensions/scaling
            return MIN_POINT_SIZE
        }

        // 5. Determine the required text height in pixels on the *original* image
        val requiredOriginalPixelHeight = ceil(TARGET_MIN_TEXT_HEIGHT_ON_PROCESSED_IMAGE / scaleFactor).toInt()
        val targetPixelHeightOnOriginal = max(1, requiredOriginalPixelHeight) // Ensure at least 1px target

        // 6. Estimate the float point size needed to achieve targetPixelHeightOnOriginal
        // Pass font name and style down to the estimation helper.
        val estimatedFloatPointSize = estimateFloatPointSizeForPixelHeight(
            targetPixelHeight = targetPixelHeightOnOriginal,
            fontName = fontName,
            fontStyle = fontStyle,
            graphicsContext = g2d // Pass optional graphics context
        )

        // 7. Round to the nearest integer and ensure it meets the minimum size
        val finalPointSize = max(MIN_POINT_SIZE, estimatedFloatPointSize.roundToInt())

        // 8. Store the calculated result in the cache (thread-safe write) and return it
        pointSizeCache[key] = finalPointSize
        return finalPointSize
    }

    /**
     * Clears the internal point size cache.
     */
    fun clearCache() {
        pointSizeCache.clear()
        // Consider adding logging if needed: println("Font point size cache cleared.")
    }

    /**
     * Calculates the overall scaling factor applied during OpenAI 'detail: high' processing.
     * (Internal helper, unchanged)
     * @param width Original image width.
     * @param height Original image height.
     * @return The combined scaling factor, or 0.0 if dimensions are invalid.
     */
    internal fun calculateHighDetailScaleFactor(width: Double, height: Double): Double {
        if (width <= 0 || height <= 0) return 0.0
        val scaleToFitMaxRes = min(1.0, min(HIGH_DETAIL_MAX_RES / width, HIGH_DETAIL_MAX_RES / height))
        val intermediateWidth = width * scaleToFitMaxRes
        val intermediateHeight = height * scaleToFitMaxRes
        val shortestIntermediateSide = min(intermediateWidth, intermediateHeight)
        if (shortestIntermediateSide <= 0) return 0.0
        val scaleToTargetShortSide = HIGH_DETAIL_SHORT_SIDE_TARGET / shortestIntermediateSide
        return scaleToFitMaxRes * scaleToTargetShortSide
    }

    /**
     * Internal helper to estimate the float point size for a target pixel height,
     * using FontMetrics derived from the provided font name and style.
     *
     * @param targetPixelHeight Desired pixel height (must be > 0).
     * @param fontName Name of the font family.
     * @param fontStyle Font style (e.g., Font.PLAIN).
     * @param graphicsContext Optional Graphics2D context.
     * @return Estimated float point size (at least 1.0f).
     */
    private fun estimateFloatPointSizeForPixelHeight(
        targetPixelHeight: Int,
        fontName: String, // Changed parameter
        fontStyle: Int,   // Changed parameter
        graphicsContext: Graphics2D? = null
    ): Float {
        if (targetPixelHeight <= 0) return 1.0f

        var graphicsToDispose: Graphics2D? = null
        val graphics: Graphics2D
        var baseFont: Font? = null // Declare here to check font validity

        try {
            // Create base font first to catch potential font issues early
            try {
                baseFont = Font(fontName, fontStyle, 1) // Placeholder size 1pt
            } catch (e: Exception) {
                System.err.println("Error creating base font '$fontName' style $fontStyle: ${e.message}. Check font availability. Returning 1.0f.")
                return 1.0f
            }

            graphics = graphicsContext ?: createTemporaryGraphics().also { graphicsToDispose = it }

            // Derive font at reference size
            val referenceFont = baseFont.deriveFont(REFERENCE_POINT_SIZE.toFloat())

            val fmReference: java.awt.FontMetrics
            try {
                fmReference = graphics.getFontMetrics(referenceFont)
            } catch (e: Exception) {
                System.err.println("Error obtaining FontMetrics for $fontName ($fontStyle): ${e.message}. Returning 1.0f.")
                return 1.0f
            }

            val heightAtReferenceSize = fmReference.height
            if (heightAtReferenceSize <= 0) {
                System.err.println("Warning: FontMetrics gave non-positive height ($heightAtReferenceSize) for $fontName ($fontStyle) at ${REFERENCE_POINT_SIZE}pt. Returning 1.0f.")
                return 1.0f
            }

            // Estimate size using proportion
            val estimatedSize = REFERENCE_POINT_SIZE * (targetPixelHeight.toDouble() / heightAtReferenceSize.toDouble())
            return max(1.0f, estimatedSize.toFloat())

        } catch (e: Exception) {
            System.err.println("Unexpected error during float point size estimation for $fontName ($fontStyle): ${e.message}")
            // e.printStackTrace() // Optional: Log stack trace
            return 1.0f // Fallback
        } finally {
            graphicsToDispose?.dispose() // Dispose only if created temporarily
        }
    }

    /**
     * Creates a minimal temporary BufferedImage and returns its Graphics2D context.
     * (Internal helper, unchanged)
     */
    private fun createTemporaryGraphics(): Graphics2D {
        val tempImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g2d = tempImage.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        return g2d
    }
}