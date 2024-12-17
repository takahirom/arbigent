package com.github.takahirom.ai_agent

import dadb.Dadb
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import maestro.*
import maestro.drivers.AndroidDriver
import maestro.orchestra.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.awt.image.BufferedImage
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.imageio.ImageIO

val maestroInstance by lazy {
  var dadb = Dadb.discover()!!
  val maestro = retry {
    val driver = AndroidDriver(
      dadb
    )
    try {
      dadb.close()
      dadb = Dadb.discover()!!
      Maestro.android(
        driver
      )
    } catch (e: Exception) {
      driver.close()
      dadb.close()
      throw e
    }
  }
  maestro
}

fun closeMaestro() {
  maestroInstance.close()
}


private fun <T> retry(times: Int = 5, block: () -> T): T {
  repeat(times) {
    try {
      return block()
    } catch (e: Exception) {
      println("Failed to run agent: $e")
      e.printStackTrace()
    }
  }

  throw TimeoutException("Failed to run agent")
}


class AgentStateHolder(
  val apiKey: String = System.getenv("API_KEY")!!,
  val initialGoal: String = "Launch MyApp"
) {
  val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  var job: Job? = null
  val contextStateFlow: MutableStateFlow<Context?> = MutableStateFlow(null)
  val isRunning: MutableStateFlow<Boolean> = MutableStateFlow(false)
  val goal = MutableStateFlow(initialGoal)
  val isArchived = contextStateFlow
    .flatMapConcat { it?.turns ?: flowOf() }
    .map { it.any { it.agentCommand is GoalAchievedAgentCommand } }
    .stateIn(coroutineScope, SharingStarted.Lazily, false)

  fun run(goal: String) {
    isRunning.value = true
    this.goal.value = goal
    val context = Context(goal)
    contextStateFlow.value = context
    job?.cancel()
    job = coroutineScope.launch {
      try {
        val ai = Ai(apiKey)
        val agentCommands: List<AgentCommand> = defaultAgentCommands()

        val orchestra = Orchestra(
          maestro = maestroInstance,
          screenshotsDir = File("screenshots")
        )
        val agentCommandMap = agentCommands.associateBy { it.actionName }
        repeat(10) {
          try {
            orchestra.executeCommands(
              commands = listOf(
                MaestroCommand(
                  backPressCommand = BackPressCommand()
                )
              ),
            )
          } catch (e: Exception) {
            println("Failed to back press: $e")
          }
        }
        for (i in 0..10) {
          yield()
          if (step(orchestra, ai, context, maestroInstance, agentCommandMap)) {
            isRunning.value = false
            return@launch
          }
        }
        println("終わり")
        isRunning.value = false
      } catch (e: Exception) {
        println("Failed to run agent: $e")
        e.printStackTrace()
        isRunning.value = false
      }
    }
  }

  fun cancel() {
    job?.cancel()
    isRunning.value = false
  }
}

fun main() {
  val goal = "Find best dinner for tonight in tokyo"
  val ai =
    Ai(apiKey = System.getenv("API_KEY"))
//  startTestAgent(ai, goal)
}

interface AgentCommand {
  val actionName: String
  fun runOrchestraCommand(orchestra: Orchestra)
  fun templateForAI(): String
}

data class ClickWithTextAgentCommand(val textRegex: String) : AgentCommand {
  override val actionName = "ClickWithText"

  override fun runOrchestraCommand(orchestra: Orchestra) {
    orchestra.executeCommands(
      commands = listOf(
        MaestroCommand(
          tapOnElement = TapOnElementCommand(
            ElementSelector(textRegex = textRegex)
          )
        )
      ),
    )
  }

  override fun templateForAI(): String {
    return """
        {
            "action": "$actionName",
            // the text should be clickable text, or content description. should be in UI hierarchy. should not resource id
            // You can use Regex
            "text": "..." 
        }
        """.trimIndent()
  }
}

data class ClickWithIdAgentCommand(val textRegex: String) : AgentCommand {
  override val actionName = "ClickWithId"

  override fun runOrchestraCommand(orchestra: Orchestra) {
    orchestra.executeCommands(
      commands = listOf(
        MaestroCommand(
          tapOnElement = TapOnElementCommand(
            ElementSelector(idRegex = textRegex)
          )
        )
      ),
    )
  }

  override fun templateForAI(): String {
    return """
        {
            "action": "$actionName",
            // the text should be id, should be in UI hierarchy
            // You can use Regex
            "text": "..." 
        }
        """.trimIndent()
  }
}

data class InputTextAgentCommand(val text: String) : AgentCommand {
  override val actionName = "InputText"

  override fun runOrchestraCommand(orchestra: Orchestra) {
    orchestra.executeCommands(
      commands = listOf(
        MaestroCommand(
          inputTextCommand = InputTextCommand(
            text
          )
        )
      ),
    )
  }

  override fun templateForAI(): String {
    return """
        {
            // You have to **Click** on a text field before sending this command
            "action": "$actionName",
            "text": "..."
        }
        """.trimIndent()
  }
}

data object BackPressAgentCommand : AgentCommand {
  override val actionName = "BackPress"

  override fun runOrchestraCommand(orchestra: Orchestra) {
    orchestra.runFlow(
      commands = listOf(
        MaestroCommand(
          backPressCommand = BackPressCommand()
        )
      )
    )
  }

  override fun templateForAI(): String {
    return """
        {
            "action": "$actionName"
        }
        """.trimIndent()
  }
}

data object ScrollAgentCommand : AgentCommand {
  override val actionName: String = "Scroll"

  override fun runOrchestraCommand(orchestra: Orchestra) {
    orchestra.executeCommands(
      commands = listOf(
        MaestroCommand(
          scrollCommand = ScrollCommand()
        )
      ),
    )
  }

  override fun templateForAI(): String {
    return """
        {
            "action": "$actionName"
        }
        """.trimIndent()
  }
}

data class KeyPressAgentCommand(val keyName: String) : AgentCommand {
  override val actionName = "KeyPress"

  override fun runOrchestraCommand(orchestra: Orchestra) {
    val code = KeyCode.getByName(keyName) ?: throw MaestroException.InvalidCommand(message = "Unknown key: $keyName")
    orchestra.executeCommands(
      commands = listOf(
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code
          )
        )
      ),
    )
  }

  override fun templateForAI(): String {
    return """
        {
            "action": "$actionName",
            "text": "..."
        }
        """.trimIndent()
  }
}

data object GoalAchievedAgentCommand : AgentCommand {
  override val actionName = "GoalAchieved"

  override fun runOrchestraCommand(orchestra: Orchestra) {
  }

  override fun templateForAI(): String {
    return """
        {
            "action": "$actionName"
        }
        """.trimIndent()
  }
}

data class Context(
  val goal: String,
) {
  class Turn(
    val agentCommand: AgentCommand? = null,
    val action: String? = null,
    val memo: String,
    val whatYouSaw: String? = null,
    val message: String? = null,
    val screenshotFileName: String
  ) {
    fun text(): String {
      return """
        action: $agentCommand
        memo: $memo
        whatYouSaw: $whatYouSaw
      """.trimIndent()
    }
  }

  val turns = MutableStateFlow<List<Turn>>(listOf())
  fun add(turn: Turn) {
    turns.value = turns.value.toMutableList() + turn
  }

  fun prompt(): String {
    return """
Goal: "$goal"
Turns so far:
${
      turns.value.mapIndexed { index, turn ->
        "${index + 1}. \n" +
          turn.text()
      }.joinToString("\n")
    }
    """.trimIndent()
  }
}

fun defaultAgentCommands(): List<AgentCommand> {
  return listOf(
    ClickWithTextAgentCommand(""),
    ClickWithIdAgentCommand(""),
    InputTextAgentCommand(""),
    BackPressAgentCommand,
    KeyPressAgentCommand(""),
    ScrollAgentCommand,
    GoalAchievedAgentCommand
  )
}

private fun step(
  orchestra: Orchestra,
  ai: Ai,
  context: Context,
  maestro: Maestro,
  agentCommandMap: Map<String, AgentCommand>
): Boolean {
  val screenshotFileName = System.currentTimeMillis().toString()
  try {
    orchestra.executeCommands(
      commands = listOf(
        MaestroCommand(
          takeScreenshotCommand = TakeScreenshotCommand(
            screenshotFileName
          )
        ),
      ),
    )
  } catch (e: Exception) {
    println("Failed to take screenshot: $e")
  }
  println("Inputs: ${context.prompt()}")
  val agentCommand = ai.decideWhatToDo(
    context = context,
    dumpHierarchy = maestro.viewHierarchy(false).toOptimizedString(),
    screenshotFileName = screenshotFileName,
    screenshot = null, //"screenshots/test.png",
    agentCommandMap = agentCommandMap
  )
  println("What to do: ${agentCommand}")
  if (agentCommand is GoalAchievedAgentCommand) {
    println("Goal achieved")
    return true
  } else {
    try {
      agentCommand.runOrchestraCommand(orchestra)
    } catch (e: MaestroException) {
      context.add(
        Context.Turn(
          memo = "Failed to perform action: ${e.message}",
          screenshotFileName = screenshotFileName
        )
      )
    } catch (e: StatusRuntimeException) {
      context.add(
        Context.Turn(
          memo = "Failed to perform action: ${e.message}",
          screenshotFileName = screenshotFileName
        )
      )
    } catch (e: IllegalStateException) {
      context.add(
        Context.Turn(
          memo = "Failed to perform action: ${e.message}",
          screenshotFileName = screenshotFileName
        )
      )
    }
  }
  return false
}

data class OptimizationResult(
  val node: TreeNode?,
  val promotedChildren: List<TreeNode>
)

fun TreeNode.optimizeTree(
  isRoot: Boolean = false,
  meaningfulAttributes: Set<String> = setOf("text", "content description", "hintText"),
  viewHierarchy: ViewHierarchy
): OptimizationResult {
  // Optimize children
  val childResults = children
    .filter {
      it.attributes["resource-id"] != "status_bar_container"
    }
    .map { it.optimizeTree(false, meaningfulAttributes, viewHierarchy) }
  val optimizedChildren = childResults.flatMap {
    it.node?.let { node -> listOf(node) } ?: it.promotedChildren
  }
  val hasContent = attributes.keys.any { it in meaningfulAttributes }
    && (children.isNotEmpty() || viewHierarchy.isVisible(this))
  val singleChild = optimizedChildren.singleOrNull()

  return when {
    (hasContent) || isRoot -> {
      // Keep the node with optimized children
      OptimizationResult(
        node = this.copy(children = optimizedChildren),
        promotedChildren = emptyList()
      )
    }

    optimizedChildren.isEmpty() -> {
      // Remove the node
      OptimizationResult(
        node = null,
        promotedChildren = emptyList()
      )
    }
    // If the node has only one child, promote it
    singleChild != null -> {
      OptimizationResult(
        node = singleChild,
        promotedChildren = emptyList()
      )
    }

    else -> {
      // Promote children
      OptimizationResult(
        node = null,
        promotedChildren = optimizedChildren
      )
    }
  }
}

fun TreeNode.optimizedToString(depth: Int, enableDepth: Boolean = false): String {
  /**
   * data class TreeNode(
   *     val attributes: MutableMap<String, String> = mutableMapOf(),
   *     val children: List<TreeNode> = emptyList(),
   *     val clickable: Boolean? = null,
   *     val enabled: Boolean? = null,
   *     val focused: Boolean? = null,
   *     val checked: Boolean? = null,
   *     val selected: Boolean? = null,
   * )
   * if it is not true, it shouldn't be included in the output
   */
  val blank = " ".repeat(depth)
  fun StringBuilder.appendString(str: String) {
    if (enableDepth) {
      append(blank)
      appendLine(str)
    } else {
      append(str)
    }
  }
  return buildString {
    appendString("Node(")
//    appendString("attributes=$attributes, ")
    if (attributes.isNotEmpty()) {
//      appendString("attr={")
      attributes.forEach { (key, value) ->
        if (key == "class") {
          appendString("class=${value.substringAfterLast('.')}, ")
        } else if (key == "resource-id" && value.isNotBlank()) {
          appendString("id=${value.substringAfterLast('/')}, ")
        } else if (key == "clickable") {
          appendString("clickable=$value, ")
        } else if (key == "enabled") {
          if (value == "false") {
            appendString("enabled=$value, ")
          }
        } else if (value.isNotBlank() && value != "null" && value != "false") {
          appendString("$key=$value, ")
        }
      }
//      appendString("}, ")
    }
    if (clickable != null && clickable!!) {
      appendString("clickable=$clickable, ")
    }
    if (enabled != null && !enabled!!) {
      appendString("enabled=$enabled, ")
    }
    if (focused != null && focused!!) {
      appendString("focused=$focused, ")
    }
    if (checked != null && checked!!) {
      appendString("checked=$checked, ")
    }
    if (selected != null && selected!!) {
      appendString("selected=$selected, ")
    }
    if (children.isNotEmpty()) {
      appendString("children(${children.size})=[")
      children.forEachIndexed { index, child ->
        append(child.optimizedToString(depth + 1))
        if (index < children.size - 1) {
          appendString(", ")
        }
      }
      appendString("], ")
    }
    appendString(")")
  }
}

fun ViewHierarchy.toOptimizedString(
  meaningfulAttributes: Set<String> = setOf("text", "content description", "hintText")
): String {
  val root = root
  val result = root.optimizeTree(
    isRoot = true,
    viewHierarchy = this,
    meaningfulAttributes = meaningfulAttributes
  )
  val optimizedTree = result.node ?: result.promotedChildren.firstOrNull()
  println("Before optimization (length): ${this.toString().length}")
  println("After optimization (length): ${optimizedTree?.optimizedToString(depth = 0)?.length}")
  return optimizedTree?.optimizedToString(depth = 0) ?: ""
}

//fun main() {
//  // サンプルツリーの定義
//  val tree = TreeNode(
//    attributes = mutableMapOf(),
//    children = listOf(
//      TreeNode(
//        attributes = mutableMapOf(),
//        children = listOf(
//          TreeNode(
//            attributes = mutableMapOf("text" to "Hello"),
//            children = emptyList()
//          ),
//          TreeNode(
//            attributes = mutableMapOf("hintText" to "Enter name"),
//            children = emptyList()
//          ),
//          TreeNode(
//            attributes = mutableMapOf(),
//          children = listOf(
//            TreeNode(
//              attributes = mutableMapOf("content description" to "Button"),
//              children = emptyList()
//            )
//          )
//        )
//      )
//    ),
//    TreeNode(
//      attributes = mutableMapOf(),
//      children = emptyList()
//    )
//  )
//  )
//  println("=== Before Optimization ===")
//  println(tree)
//  println("=== After Optimization ===")
//  val optimizedTreeString = tree.toOptimizedString()
//  println(optimizedTreeString)
//}


fun File.getResizedIamgeByteArray(scale: Float): ByteArray {
  val image = ImageIO.read(this)
  val scaledImage = image.getScaledInstance(
    (image.width * scale).toInt(),
    (image.height * scale).toInt(),
    BufferedImage.SCALE_SMOOTH
  )
  val bufferedImage = BufferedImage(
    scaledImage.getWidth(null),
    scaledImage.getHeight(null),
    BufferedImage.TYPE_INT_RGB
  )
  bufferedImage.graphics.drawImage(scaledImage, 0, 0, null)
  val output = File.createTempFile("scaled", ".png")
  ImageIO.write(bufferedImage, "png", output)
  return output.readBytes()
}

class Ai(private val apiKey: String) {
  fun decideWhatToDo(
    context: Context,
    dumpHierarchy: String,
    screenshot: String?,
    agentCommandMap: Map<String, AgentCommand>,
    screenshotFileName: String,
  ): AgentCommand {
    val prompt = buildPrompt(context, dumpHierarchy, agentCommandMap)
//    val imageFile = File(screenshot)
//    val imageBase64 = imageFile.getResizedIamgeByteArray(0.3F).encodeBase64()
    val messages: List<Message> = listOf(
      Message(
        role = "system",
        content = listOf(
          Content(
            type = "text",
            text = "You are an agent that achieve the user's goal automatically. Please be careful not to repeat the same action."
          )
        )
      ),
      Message(
        role = "user",
        content = listOf(
//          Content(
//            type = "image_url",
//            imageUrl = ImageUrl(
//              url = "data:image/png;base64,$imageBase64"
//            )
//          ),
          Content(
            type = "text",
            text = prompt
          ),
        )
      )
    )
    val responseText = chatCompletion(messages)
    val turn = parseResponse(responseText, messages, screenshotFileName, agentCommandMap)
    context.add(turn)
    return turn.agentCommand!!
  }

  private fun buildPrompt(
    context: Context,
    dumpHierarchy: String,
    agentCommandMap: Map<String, AgentCommand>
  ): String {
    val contextPrompt = context.prompt()
    val templates = agentCommandMap.values.joinToString("\nor\n") { it.templateForAI() }
    val prompt = """

UI Hierarchy:
$dumpHierarchy

Based on the above, decide on the next action to achieve the goal. Please ensure not to repeat the same action. The action must be one of the following:
$templates"""
    return contextPrompt + (prompt.trimIndent())
  }

  private fun parseResponse(
    response: String,
    message: List<Message>,
    screenshotFileName: String,
    agentCommandMap: Map<String, AgentCommand>
  ): Context.Turn {
    val json = Json { ignoreUnknownKeys = true }
    val responseObj = json.decodeFromString<ChatCompletionResponse>(response)
    val content = responseObj.choices.firstOrNull()?.message?.content ?: ""
    println("OpenAI content: $content")
    return try {
      val jsonElement = json.parseToJsonElement(content)
      val jsonObject = jsonElement.jsonObject
      val action =
        jsonObject["action"]?.jsonPrimitive?.content ?: throw Exception("Action not found")
      val commandPrototype = agentCommandMap[action] ?: throw Exception("Unknown action: $action")
      val agentCommand = when (commandPrototype) {
        is ClickWithTextAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          ClickWithTextAgentCommand(text)
        }

        is ClickWithIdAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          ClickWithIdAgentCommand(text)
        }

        is InputTextAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          InputTextAgentCommand(text)
        }

        is BackPressAgentCommand -> BackPressAgentCommand
        is KeyPressAgentCommand -> {
          val text = jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Text not found")
          KeyPressAgentCommand(text)
        }

        is ScrollAgentCommand -> ScrollAgentCommand

        is GoalAchievedAgentCommand -> GoalAchievedAgentCommand
        else -> throw Exception("Unsupported action: $action")
      }
      Context.Turn(
        agentCommand = agentCommand,
        action = action,
        memo = jsonObject["memo"]?.jsonPrimitive?.content ?: "",
        whatYouSaw = jsonObject["summary-of-what-you-saw"]?.jsonPrimitive?.content ?: "",
        message = message.toString(),
        screenshotFileName = screenshotFileName
      )
    } catch (e: Exception) {
      throw Exception("Failed to parse OpenAI response: $e")
    }
  }

  private fun chatCompletion(messages: List<Message>): String {
    val client = OkHttpClient.Builder()
      .readTimeout(60, TimeUnit.SECONDS)
      .build()
    val url = "https://api.openai.com/v1/chat/completions"
    val json = Json { ignoreUnknownKeys = true }
    val requestBodyJson = json.encodeToString(
      ChatCompletionRequest(
        model = "gpt-4o-mini",
        messages = messages,
        ResponseFormat(
          type = "json_schema",
          jsonSchema = buildActionSchema(),
        ),
      )
    )
    val requestBody = RequestBody.create(
      "application/json".toMediaType(), requestBodyJson
    )
    val request = Request.Builder()
      .url(url)
      .header("Authorization", "Bearer $apiKey")
      .post(requestBody)
      .build()
    println(
      "OpenAI request: ${
        messages.flatMap { it.content }.filter { it.type == "text" }.joinToString("\n")
      }"
    )
    val response = client.newCall(request).execute()
    val responseBody = response.body?.string() ?: ""
    println("OpenAI response: $responseBody")
    return responseBody
  }

  private fun buildActionSchema(): JsonObject {
    val actions = defaultAgentCommands().map { it.actionName }.joinToString { "\"$it\"" }
    val schemaJson = """
    {
      "name": "ActionSchema",
      "description": "Schema for user actions",
      "strict": true,
      "schema": {
        "type": "object",
        "required": ["summary-of-what-you-saw", "memo",  "action", "text"],
        "additionalProperties": false,
        "properties": {
          "summary-of-what-you-saw": {
            "type": "string"
          },
          "memo": {
            "type": "string"
          },
          "action": {
            "type": "string",
            "enum": [$actions]
          },
          "text": {
            "type": "string",
            "nullable": true
          }
        }
      }
    }
    """.trimIndent()
    return Json.parseToJsonElement(schemaJson).jsonObject
  }

  private fun readFileAsBase64(filename: String): String {
    val file = File(filename)
    val bytes = file.readBytes()
    return Base64.getEncoder().encodeToString(bytes)
  }

  @Serializable
  private data class Message(
    val role: String,
    val content: List<Content>
  )

  @Serializable
  private data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    @SerialName("response_format") val responseFormat: ResponseFormat?,
  )

  @Serializable
  data class ResponseFormat(
    val type: String,
    @SerialName("json_schema") val jsonSchema: JsonObject
  )

  @Serializable
  data class ChatCompletionResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null
  )

  @Serializable
  data class Choice(
    val index: Int,
    val message: MessageContent,
    @SerialName("finish_reason") val finishReason: String? = null,
  )

  @Serializable
  data class MessageContent(
    val role: String,
    val content: String
  )

  @Serializable
  private data class Content(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrl? = null
  )

  @Serializable
  private data class ImageUrl(
    val url: String
  )

  @Serializable
  data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int,
  )
}
