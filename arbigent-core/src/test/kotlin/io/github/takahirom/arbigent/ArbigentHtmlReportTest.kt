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
                    describe("with XML content in aiRequest") {
                        doIt {
                            createScreenshotFile()
                            generateReportWithXmlContent()
                        }
                        itShould("contain XML tags in YAML") {
                            capture(it)
                            assertReportContainsXml()
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

    fun generateReportWithXmlContent(): ArbigentHtmlReportRobot {
        val xmlContent = "<PROMPT><CONTEXT>This is a test XML content</CONTEXT></PROMPT>"
        val step = createTestStep(aiRequest = xmlContent)
        result = createTestProjectExecutionResult(step)
        outputDir = tempFolder.newFolder("output")
        ArbigentHtmlReport().saveReportHtml(outputDir.absolutePath, result)
        return this
    }

    fun assertReportContainsXml(): ArbigentHtmlReportRobot {
        val reportContent = File(outputDir, "report.html").readText()
        println("[DEBUG_LOG] Report content: $reportContent")
        println("[DEBUG_LOG] Looking for XML tags in YAML content")
        assertTrue(reportContent.contains("<PROMPT>"), "Report should contain XML opening tag")
        assertTrue(reportContent.contains("</PROMPT>"), "Report should contain XML closing tag")
        return this
    }

    private fun createTestStep(aiRequest: String? = null) = ArbigentAgentTaskStepResult(
        stepId = "test_step",
        summary = "Test step",
        screenshotFilePath = screenshotFile.absolutePath,
        apiCallJsonPath = null,
        agentCommand = null,
        aiRequest = aiRequest,
        aiResponse = null,
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
