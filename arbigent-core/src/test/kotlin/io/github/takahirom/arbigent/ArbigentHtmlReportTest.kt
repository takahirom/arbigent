package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentAgentResult
import io.github.takahirom.arbigent.result.ArbigentAgentResults
import io.github.takahirom.arbigent.result.ArbigentAgentTaskStepResult
import io.github.takahirom.arbigent.result.ArbigentProjectExecutionResult
import io.github.takahirom.arbigent.result.ArbigentScenarioResult
import io.github.takahirom.robospec.*
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class ArbigentHtmlReportTest(private val behavior: DescribedBehavior<ArbigentHtmlReportRobot>) {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun test() = runTest {
        val robot = ArbigentHtmlReportRobot(tempFolder)
        behavior.execute(robot)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): DescribedBehaviors<ArbigentHtmlReportRobot> {
            return describeBehaviors<ArbigentHtmlReportRobot>("HTML Report Tests") {
                describe("when generating report") {
                    describe("with missing annotated files") {
                        doIt {
                            createScreenshotFile()
                            generateReport()
                        }
                        itShould("handle gracefully") {
                            capture(it)
                            assertScreenshotExists()
                            assertAnnotatedFileNotExists()
                            assertReportFileExists()
                        }
                    }
                    describe("with existing annotated files") {
                        doIt {
                            createScreenshotFile()
                            createAnnotatedFile()
                            generateReport()
                        }
                        itShould("copy all files") {
                            capture(it)
                            assertScreenshotExists()
                            assertAnnotatedFileExists()
                            assertReportFileExists()
                        }
                    }
                    describe("with JSONL file path") {
                        doIt {
                            createScreenshotFile()
                            generateReportWithJsonl()
                        }
                        itShould("contain JSONL link") {
                            capture(it)
                            assertReportContainsJsonlLink()
                        }
                    }
                    describe("with JSONL file path and cache hit") {
                        doIt {
                            createScreenshotFile()
                            generateReportWithJsonlCacheHit()
                        }
                        itShould("contain cache hit text") {
                            capture(it)
                            assertReportContainsJsonlCacheHit()
                        }
                    }
                }
            }
        }
    }
}

class ArbigentHtmlReportRobot(private val tempFolder: TemporaryFolder) {
    private lateinit var screenshotFile: File
    private lateinit var outputDir: File
    private lateinit var result: ArbigentProjectExecutionResult

    fun capture(behavior: DescribedBehavior<*>) {
        // Record the test state for verification
        println("[DEBUG_LOG] Capturing state for: $behavior")
    }

    fun createScreenshotFile(): ArbigentHtmlReportRobot {
        screenshotFile = tempFolder.newFile("screenshot.png")
        screenshotFile.writeBytes(ByteArray(1))
        return this
    }

    fun createAnnotatedFile(): ArbigentHtmlReportRobot {
        val annotatedFile = File(screenshotFile.absolutePath.substringBeforeLast(".") + "_annotated.png")
        annotatedFile.writeBytes(ByteArray(1))
        return this
    }

    fun generateReport(): ArbigentHtmlReportRobot {
        val step = createTestStep()
        result = createTestProjectExecutionResult(step)
        outputDir = tempFolder.newFolder("output")
        ArbigentHtmlReport().saveReportHtml(outputDir.absolutePath, result)
        return this
    }

    fun assertScreenshotExists(): ArbigentHtmlReportRobot {
        assertTrue(File(outputDir, "screenshots/${screenshotFile.name}").exists())
        return this
    }

    fun assertAnnotatedFileExists(): ArbigentHtmlReportRobot {
        assertTrue(File(outputDir, "screenshots/${screenshotFile.name.replace(".png", "_annotated.png")}").exists())
        return this
    }

    fun assertAnnotatedFileNotExists(): ArbigentHtmlReportRobot {
        assertFalse(File(outputDir, "screenshots/${screenshotFile.name.replace(".png", "_annotated.png")}").exists())
        return this
    }

    fun assertReportFileExists(): ArbigentHtmlReportRobot {
        assertTrue(File(outputDir, "report.html").exists())
        return this
    }

    fun generateReportWithJsonl(): ArbigentHtmlReportRobot {
        // Create a temporary JSONL file
        val jsonlFile = tempFolder.newFile("test.jsonl")
        jsonlFile.writeText("""{"request": "test request", "response": "test response"}""")
        
        val step = createTestStep().copy(apiCallJsonPath = jsonlFile.absolutePath, cacheHit = false)
        result = createTestProjectExecutionResult(step)
        outputDir = tempFolder.newFolder("output-jsonl")
        ArbigentHtmlReport().saveReportHtml(outputDir.absolutePath, result)
        return this
    }

    fun generateReportWithJsonlCacheHit(): ArbigentHtmlReportRobot {
        // Create a temporary JSONL file
        val jsonlFile = tempFolder.newFile("cache.jsonl")
        jsonlFile.writeText("""{"request": "cache request", "response": "cache response"}""")
        
        val step = createTestStep().copy(apiCallJsonPath = jsonlFile.absolutePath, cacheHit = true)
        result = createTestProjectExecutionResult(step)
        outputDir = tempFolder.newFolder("output-cache")
        ArbigentHtmlReport().saveReportHtml(outputDir.absolutePath, result)
        return this
    }

    fun assertReportContainsJsonlLink(): ArbigentHtmlReportRobot {
        val reportContent = File(outputDir, "report.html").readText()
        assertTrue(
            reportContent.contains("apiCallJsonPath: \"jsonls/test.jsonl\"") && 
            reportContent.contains("cacheHit: false"),
            "Report should contain JSONL path in YAML and cacheHit false"
        )
        // Also verify the JSONL file was copied to the output directory
        assertTrue(
            File(outputDir, "jsonls/test.jsonl").exists(),
            "JSONL file should be copied to output directory"
        )
        return this
    }

    fun assertReportContainsJsonlCacheHit(): ArbigentHtmlReportRobot {
        val reportContent = File(outputDir, "report.html").readText()
        assertTrue(
            reportContent.contains("apiCallJsonPath: \"jsonls/cache.jsonl\"") && 
            reportContent.contains("cacheHit: true"),
            "Report should contain JSONL path in YAML and cacheHit true"
        )
        // Also verify the JSONL file was copied to the output directory
        assertTrue(
            File(outputDir, "jsonls/cache.jsonl").exists(),
            "JSONL file should be copied to output directory"
        )
        return this
    }


    private fun createTestStep() = ArbigentAgentTaskStepResult(
        stepId = "test_step",
        summary = "Test step",
        screenshotFilePath = screenshotFile.absolutePath,
        apiCallJsonPath = null,
        agentAction = null,
        timestamp = System.currentTimeMillis(),
        cacheHit = false
    )

    private fun createTestProjectExecutionResult(step: ArbigentAgentTaskStepResult) = ArbigentProjectExecutionResult(
        scenarios = listOf(
            ArbigentScenarioResult(
                id = "test_scenario",
                isSuccess = true,
                histories = listOf(
                    ArbigentAgentResults(
                        status = "completed",
                        agentResults = listOf(
                            ArbigentAgentResult(
                                goal = "test goal",
                                isGoalAchieved = true,
                                steps = listOf(step),
                                deviceName = "test_device",
                                endTimestamp = System.currentTimeMillis()
                            )
                        )
                    )
                )
            )
        )
    )
}
