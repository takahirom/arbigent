import io.github.takahirom.arbigent.AgentActionType
import io.github.takahirom.arbigent.AnthropicAi
import io.github.takahirom.arbigent.ArbigentAi
import io.github.takahirom.arbigent.ArbigentContextHolder
import io.github.takahirom.arbigent.ArbigentElement
import io.github.takahirom.arbigent.ArbigentElementList
import io.github.takahirom.arbigent.ArbigentPrompt
import io.github.takahirom.arbigent.ClickWithIndex
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import io.github.takahirom.arbigent.toAnnotatedFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * The decision request has to carry the Set-of-Mark annotated image: the numbered boxes drawn on it
 * are what lets the model map what it sees to the indices in <ELEMENTS>. Sending the un-annotated
 * screenshot instead is a silent failure - the request still succeeds, the model just guesses - so
 * it went unnoticed from commit 9f46f76d until this test.
 */
class AnthropicDecisionImageTest {

  @Test
  fun `decision request sends the annotated image, not the raw screenshot`() {
    val screenshot = writeRawScreenshot()
    val sentRequests = mutableListOf<String>()
    val anthropicAi = AnthropicAi(
      apiKey = "test-api-key",
      loggingEnabled = false,
      httpClient = mockHttpClient(sentRequests)
    )

    val output = anthropicAi.decideAgentActions(decisionInput(screenshot))

    // A fixture the provider cannot parse would still leave the byte assertions below passing,
    // so pin the decoded action too.
    assertEquals(listOf(ClickWithIndex(index = 0)), output.agentActions)

    val annotated = screenshot.toAnnotatedFile()
    assertEquals(
      "decideAgentActions should keep writing the annotated file the UI and the report read",
      true,
      annotated.exists()
    )
    assertEquals(1, sentRequests.size)
    val sentImage = sentImageBytes(sentRequests.single())
    assertArrayEqualsBytes(annotated.readBytes(), sentImage)
    assertNotEquals(
      "the raw screenshot has no numbered boxes on it",
      screenshot.readBytes().toList(),
      sentImage.toList()
    )
  }

  private fun sentImageBytes(requestBody: String): ByteArray {
    val content = Json.parseToJsonElement(requestBody)
      .jsonObject["messages"]!!.jsonArray.single()
      .jsonObject["content"]!!.jsonArray
    val image = content.single { it.jsonObject["type"]?.jsonPrimitive?.content == "image" }
    val source = image.jsonObject["source"]!!.jsonObject
    assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
    val data = source["data"]!!.jsonPrimitive.content
    assertNotNull(data)
    return Base64.getDecoder().decode(data)
  }

  private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
    assertEquals(
      "the image in the request should be the annotated file byte for byte",
      expected.toList(),
      actual.toList()
    )
  }

  private fun mockHttpClient(sentRequests: MutableList<String>): HttpClient {
    return HttpClient(
      MockEngine { request ->
        sentRequests.add((request.body as TextContent).text)
        respond(
          content = MINIMAL_TOOL_USE_RESPONSE,
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )
      }
    ) {
      install(ContentNegotiation) { json() }
    }
  }

  private fun decisionInput(screenshot: File): ArbigentAi.DecisionInput {
    return ArbigentAi.DecisionInput(
      stepId = "step-1",
      contextHolder = ArbigentContextHolder(goal = "goal", maxStep = 10),
      formFactor = ArbigentScenarioDeviceFormFactor.Mobile,
      uiTreeStrings = ArbigentUiTreeStrings(allTreeString = "", optimizedTreeString = ""),
      focusedTreeString = null,
      agentActionTypes = listOf(ClickWithIndex) as List<AgentActionType>,
      screenshotFilePath = screenshot.absolutePath,
      requestUuid = "request-uuid",
      apiCallJsonLFilePath = File(screenshot.parentFile, "api-call.jsonl").absolutePath,
      elements = singleElementList(),
      prompt = ArbigentPrompt(),
      cacheKey = "cache-key",
      aiOptions = null,
    )
  }

  private fun singleElementList(): ArbigentElementList {
    return ArbigentElementList(
      elements = listOf(
        ArbigentElement(
          index = 0,
          textForAI = "textForAI",
          rawText = "rawText",
          treeNode = maestro.TreeNode(),
          identifierData = ArbigentElement.IdentifierData(listOf(), 0),
          // Away from the top edge, so the index label above the box lands inside the image.
          x = 20,
          y = 60,
          width = 60,
          height = 40,
          isVisible = true
        )
      ),
      screenWidth = SCREENSHOT_WIDTH,
    )
  }

  private fun writeRawScreenshot(): File {
    val dir = File.createTempFile("arbigent-decision-image", "").let {
      it.delete()
      it.mkdirs()
      it
    }
    val image = BufferedImage(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT)
    graphics.dispose()
    val file = File(dir, "screenshot.png")
    ImageIO.write(image, "png", file)
    return file
  }

  private companion object {
    // Same width as ArbigentElementList.screenWidth, so the annotated image differs from the raw
    // screenshot only by the drawn boxes and not by a resize.
    const val SCREENSHOT_WIDTH = 200
    const val SCREENSHOT_HEIGHT = 400

    val MINIMAL_TOOL_USE_RESPONSE = """
      {
        "id": "msg_1",
        "type": "message",
        "role": "assistant",
        "model": "claude-sonnet-4-5",
        "content": [
          {
            "type": "tool_use",
            "id": "toolu_1",
            "name": "perform_clickwithindex",
            "input": {"text": "0"}
          }
        ],
        "stop_reason": "tool_use",
        "usage": {"input_tokens": 10, "output_tokens": 10}
      }
    """.trimIndent()
  }
}
