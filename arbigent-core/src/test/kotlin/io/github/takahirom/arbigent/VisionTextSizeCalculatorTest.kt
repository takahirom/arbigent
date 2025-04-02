package io.github.takahirom.arbigent

import java.awt.Font
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import kotlin.system.measureTimeMillis // For basic cache performance check
import kotlin.test.*

class FontPointUtilsTest {

    private val MIN_POINT_SIZE = 1

    @BeforeTest // Clear cache before each test method
    fun setup() {
        FontPointUtils.clearCache()
    }

    @AfterTest // Optional: Clear cache after each test
    fun teardown() {
        FontPointUtils.clearCache()
    }

    @Test
    fun estimateRecommendedPointSize_HD_SansSerif_Plain() {
        val fontName = Font.SANS_SERIF // Use logical font name
        val fontStyle = Font.PLAIN
        val width = 1920
        val height = 1080
        val expectedPointSize = 25 // EXAMPLE - ADJUST
        val actualSize = FontPointUtils.estimateRecommendedPointSize(fontName, fontStyle, width, height)

        assertTrue(
            actualSize in (expectedPointSize - 2)..(expectedPointSize + 2),
            "Point size for HD SansSerif Plain ($actualSize) not close to expected ($expectedPointSize). Font metrics may differ."
        )
        println("Test HD_SansSerif_Plain: Expected around $expectedPointSize, Got $actualSize (Env-dependent)")
    }

    @Test
    fun estimateRecommendedPointSize_HD_Monospaced_Bold() {
        val fontName = Font.MONOSPACED // Logical font name
        val fontStyle = Font.BOLD
        val width = 1920
        val height = 1080
        val expectedPointSize = 23 // EXAMPLE - ADJUST
        val actualSize = FontPointUtils.estimateRecommendedPointSize(fontName, fontStyle, width, height)

        assertTrue(
            actualSize in (expectedPointSize - 2)..(expectedPointSize + 2),
            "Point size for HD Monospaced Bold ($actualSize) not close to expected ($expectedPointSize). Font metrics may differ."
        )
        println("Test HD_Monospaced_Bold: Expected around $expectedPointSize, Got $actualSize (Env-dependent)")
    }

    @Test
    fun estimateRecommendedPointSize_LargeImage_Serif_Italic() {
        val fontName = Font.SERIF // Logical font name
        val fontStyle = Font.ITALIC
        val width = 4096
        val height = 8192
        // Scale=0.1875, ReqHeight=107
        // Assume Serif Italic 72pt height is ~88px (EXAMPLE)
        // FloatSize = 72 * (107 / 88) = 87.5... -> 88
        val expectedPointSize = 88 // EXAMPLE - ADJUST
        val actualSize = FontPointUtils.estimateRecommendedPointSize(fontName, fontStyle, width, height)

        assertTrue(
            actualSize in (expectedPointSize - 5)..(expectedPointSize + 5), // Wider range
            "Point size for Large Serif Italic ($actualSize) not close to expected ($expectedPointSize). Font metrics may differ."
        )
        println("Test LargeImage_Serif_Italic: Expected around $expectedPointSize, Got $actualSize (Env-dependent)")
    }


    @Test
    fun estimateRecommendedPointSize_InvalidDimensions_ReturnsMinSize() {
        assertEquals(MIN_POINT_SIZE, FontPointUtils.estimateRecommendedPointSize(Font.SANS_SERIF, Font.PLAIN, 0, 1080))
        assertEquals(MIN_POINT_SIZE, FontPointUtils.estimateRecommendedPointSize(Font.SANS_SERIF, Font.PLAIN, 1920, 0))
        assertEquals(MIN_POINT_SIZE, FontPointUtils.estimateRecommendedPointSize(Font.SANS_SERIF, Font.PLAIN, -1, 1080))
    }

    @Test
    fun estimateRecommendedPointSize_InvalidFontName_ReturnsMinSize() {
        // Assuming "InvalidFontNameXYZ123" does not exist
        // The internal logic should catch the font creation error and return fallback
        assertEquals(
            MIN_POINT_SIZE,
            FontPointUtils.estimateRecommendedPointSize("InvalidFontNameXYZ123", Font.PLAIN, 1920, 1080),
            "Should return min size for invalid font name"
        )
        assertEquals(
            MIN_POINT_SIZE,
            FontPointUtils.estimateRecommendedPointSize("", Font.PLAIN, 1920, 1080), // Blank font name
            "Should return min size for blank font name"
        )
    }

    @Test
    fun caching_ReturnsSameResultAndIsFasterOnSecondCall() {
        val fontName = Font.SANS_SERIF
        val fontStyle = Font.PLAIN
        val width = 1920
        val height = 1080

        // Ensure cache is clear before starting this specific test sequence
        FontPointUtils.clearCache()

        var result1: Int = -1
        var result2: Int = -1

        val time1 = measureTimeMillis {
            result1 = FontPointUtils.estimateRecommendedPointSize(fontName, fontStyle, width, height)
        }

        val time2 = measureTimeMillis {
            result2 = FontPointUtils.estimateRecommendedPointSize(fontName, fontStyle, width, height)
        }

        println("Caching Test: First call time = ${time1}ms, Second call time = ${time2}ms")

        assertEquals(result1, result2, "Result should be the same for consecutive calls with same input")
        assertTrue(result1 >= MIN_POINT_SIZE, "First result should be valid")
        // Check if the second call was significantly faster (allowing for some overhead)
        // This is a heuristic check and might be flaky depending on the system load and calculation time.
        // Only assert if the first time is reasonably long (e.g., > 5ms)
        if (time1 > 5) {
            assertTrue(time2 < time1, "Second call should be faster due to cache (time1=$time1, time2=$time2)")
            // A stricter check could be time2 < time1 / 2 or similar
        } else {
            println("Skipping strict timing assertion as first call was very fast (< 5ms)")
        }

        // Verify a different input causes recalculation (implicitly tested by other tests, but can be explicit)
        val result3 = FontPointUtils.estimateRecommendedPointSize(fontName, Font.BOLD, width, height) // Change style
        assertTrue(result3 >= MIN_POINT_SIZE, "Different input should also yield a result")
        // We cannot easily assert *which* result without knowing metrics, just that it runs.
    }

}
