import io.github.takahirom.arbigent.AgentActionType
import io.github.takahirom.arbigent.ArbigentAi
import io.github.takahirom.arbigent.ArbigentContextHolder
import io.github.takahirom.arbigent.ArbigentElement
import io.github.takahirom.arbigent.ArbigentElementList
import io.github.takahirom.arbigent.ArbigentPrompt
import io.github.takahirom.arbigent.ClickWithIndex
import io.github.takahirom.arbigent.OpenAIAi
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
import org.junit.Assert.assertTrue
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
class OpenAiDecisionImageTest {

  @Test
  fun `decision request sends the annotated image, not the raw screenshot`() {
    val screenshot = writeRawScreenshot()
    val sentRequests = mutableListOf<String>()
    val openAiAi = OpenAIAi(
      apiKey = "test-api-key",
      loggingEnabled = false,
      httpClient = mockHttpClient(sentRequests)
    )

    val output = openAiAi.decideAgentActions(decisionInput(screenshot))

    // A fixture the provider cannot parse would still leave the byte assertions below passing,
    // so pin the decoded action too.
    assertEquals(listOf(ClickWithIndex(index = 0)), output.agentActions)

    val annotated = screenshot.toAnnotatedFile()
    assertTrue(
      "decideAgentActions should keep writing the annotated file the UI and the report read",
      annotated.exists()
    )
    assertEquals(1, sentRequests.size)
    val sentImage = sentImageBytes(sentRequests.single())
    assertEquals(
      "the image in the request should be the annotated file byte for byte",
      annotated.readBytes().toList(),
      sentImage.toList()
    )
    assertNotEquals(
      "the raw screenshot has no numbered boxes on it",
      screenshot.readBytes().toList(),
      sentImage.toList()
    )
  }

  private fun sentImageBytes(requestBody: String): ByteArray {
    val contents = Json.parseToJsonElement(requestBody)
      .jsonObject["messages"]!!.jsonArray
      .single { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
      .jsonObject["content"]!!.jsonArray
    val image = contents.single { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }
    val url = image.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
    val prefix = "data:image/png;base64,"
    assertTrue("unexpected data url prefix: ${url.take(prefix.length)}", url.startsWith(prefix))
    return Base64.getDecoder().decode(url.removePrefix(prefix))
  }

  private fun mockHttpClient(sentRequests: MutableList<String>): HttpClient {
    return HttpClient(
      MockEngine { request ->
        sentRequests.add((request.body as TextContent).text)
        respond(
          content = MINIMAL_TOOL_CALL_RESPONSE,
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

    val MINIMAL_TOOL_CALL_RESPONSE = """
      {
        "object": "chat.completion",
        "created": 0,
        "model": "gpt-4o",
        "choices": [
          {
            "index": 0,
            "message": {
              "role": "assistant",
              "tool_calls": [
                {
                  "id": "call_1",
                  "type": "function",
                  "function": {"name": "perform_clickwithindex", "arguments": "{\"text\":\"0\"}"}
                }
              ]
            },
            "finish_reason": "tool_calls"
          }
        ]
      }
    """.trimIndent()
  }
}
