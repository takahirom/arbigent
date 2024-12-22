package com.github.takahirom.arbiter

import dadb.Dadb
import io.grpc.StatusRuntimeException
import io.ktor.client.plugins.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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

val maestroInstance: Maestro by lazy {
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
  (maestroInstance as Maestro).close()
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


class Arbiter(
  private val interceptors: List<ArbiterInterceptor>,
  private val apiKey: String,
  private val maestroInstance: Maestro,
) {
  private val decisionInterceptors: List<ArbiterDecisionInterceptor> = interceptors
    .filterIsInstance<ArbiterDecisionInterceptor>()
  private val executeCommandInterceptors: List<ArbiterExecuteCommandInterceptor> = interceptors
    .filterIsInstance<ArbiterExecuteCommandInterceptor>()
  private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var job: Job? = null
  private val ai = Ai(apiKey)
  val arbiterContextStateFlow: MutableStateFlow<ArbiterContext?> = MutableStateFlow(null)
  val isRunningStateFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
  private val currentGoalStateFlow = MutableStateFlow<String?>(null)
  val isArchivedStateFlow = arbiterContextStateFlow
    .flatMapConcat { it?.turns ?: flowOf() }
    .map { it.any { it.agentCommand is GoalAchievedAgentCommand } }
    .stateIn(coroutineScope, SharingStarted.Lazily, false)

  suspend fun waitUntilFinished() {
    isRunningStateFlow.first { !it }
  }

  fun execute(goal: String) {
    isRunningStateFlow.value = true
    this.currentGoalStateFlow.value = goal
    val arbiterContext = ArbiterContext(goal)
    arbiterContextStateFlow.value = arbiterContext
    job?.cancel()
    job = coroutineScope.launch {
      try {
        val agentCommands: List<AgentCommand> = defaultAgentCommands()

        val orchestra = Orchestra(
          maestro = maestroInstance,
          screenshotsDir = File("screenshots")
        )
        val agentCommandMap = agentCommands.associateBy { it.actionName }
        repeat(10) {
          try {
            yield()
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
          if (step(orchestra, arbiterContext, maestroInstance, agentCommandMap)) {
            isRunningStateFlow.value = false
            return@launch
          }
        }
        println("終わり")
        isRunningStateFlow.value = false
      } catch (e: Exception) {
        println("Failed to run agent: $e")
        e.printStackTrace()
        isRunningStateFlow.value = false
      }
    }
  }

  private fun step(
    orchestra: Orchestra,
    arbiterContext: ArbiterContext,
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
    println("Inputs: ${arbiterContext.prompt()}")
    val agentCommand = decisionInterceptors.fold(
      ArbiterDecisionInterceptor.Chain({
        ai.decideWhatToDo(
          arbiterContext = arbiterContext,
          dumpHierarchy = maestro.viewHierarchy(false).toOptimizedString(),
          screenshotFileName = screenshotFileName,
          screenshot = null, //"screenshots/test.png",
          agentCommandMap = agentCommandMap
        )
      }),
      createChain = { chain, interceptor ->
        ArbiterDecisionInterceptor.Chain { arbiterContext ->
          interceptor.intercept(arbiterContext, chain)
        }
      },
      context = arbiterContext
    )
      .proceed(arbiterContext)
    println("What to do: ${agentCommand}")
    if (agentCommand is GoalAchievedAgentCommand) {
      println("Goal achieved")
      return true
    } else {
      try {
        agentCommand.runOrchestraCommand(orchestra)
      } catch (e: MaestroException) {
        arbiterContext.add(
          ArbiterContext.Turn(
            memo = "Failed to perform action: ${e.message}",
            screenshotFileName = screenshotFileName
          )
        )
      } catch (e: StatusRuntimeException) {
        arbiterContext.add(
          ArbiterContext.Turn(
            memo = "Failed to perform action: ${e.message}",
            screenshotFileName = screenshotFileName
          )
        )
      } catch (e: IllegalStateException) {
        arbiterContext.add(
          ArbiterContext.Turn(
            memo = "Failed to perform action: ${e.message}",
            screenshotFileName = screenshotFileName
          )
        )
      }
    }
    return false
  }

  private fun <I, C> List<I>.fold(chain: C, createChain: (C, I) -> C, context: ArbiterContext): C {
    return fold(chain) { acc, interceptor ->
      createChain(acc, interceptor)
    }
  }

  fun cancel() {
    job?.cancel()
    isRunningStateFlow.value = false
  }

  class Builder {
    private val interceptors = mutableListOf<ArbiterInterceptor>()
    private var apiKey: String = System.getenv("API_KEY")!!
    private lateinit var maestroInstance: Maestro

    fun addIntercepter(interceptor: ArbiterInterceptor) {
      interceptors.add(interceptor)
    }

    fun apiKey(apiKey: String) {
      this.apiKey = apiKey
    }

    fun maestroInstance(maestroInstance: Maestro) {
      this.maestroInstance = maestroInstance as Maestro
    }

    fun build(): Arbiter {
      return Arbiter(interceptors, apiKey, maestroInstance)
    }
  }
}

// DSL
fun arbiter(block: Arbiter.Builder.() -> Unit = {}): Arbiter {
  val builder = Arbiter.Builder()
  builder.block()
  return builder.build()
}

interface ArbiterInterceptor

interface ArbiterDecisionInterceptor : ArbiterInterceptor {
  fun intercept(arbiterContext: ArbiterContext, chain: Chain): AgentCommand

  fun interface Chain {
    fun proceed(arbiterContext: ArbiterContext): AgentCommand
  }
}

interface ArbiterExecuteCommandInterceptor : ArbiterInterceptor {
  fun intercept(arbiterContext: ArbiterContext, chain: Chain)
  interface Chain {
    fun proceed(arbiterContext: ArbiterContext)
  }
}

fun main() {
  val goal = "Find best dinner for tonight in tokyo"
  val ai =
    Ai(apiKey = System.getenv("API_KEY"))
//  startTestAgent(ai, goal)
}


data class ArbiterContext(
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
    arbiterContext: ArbiterContext,
    dumpHierarchy: String,
    screenshot: String?,
    agentCommandMap: Map<String, AgentCommand>,
    screenshotFileName: String,
  ): AgentCommand {
    val prompt = buildPrompt(arbiterContext, dumpHierarchy, agentCommandMap)
//    val imageFile = File(screenshot)
//    val imageBase64 = imageFile.getResizedIamgeByteArray(0.3F).encodeBase64()
    val messages: List<ChatMessage> = listOf(
      ChatMessage(
        role = "system",
        content = listOf(
          Content(
            type = "text",
            text = "You are an agent that achieve the user's goal automatically. Please be careful not to repeat the same action."
          )
        )
      ),
      ChatMessage(
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
    arbiterContext.add(turn)
    return turn.agentCommand!!
  }

  private fun buildPrompt(
    arbiterContext: ArbiterContext,
    dumpHierarchy: String,
    agentCommandMap: Map<String, AgentCommand>
  ): String {
    val contextPrompt = arbiterContext.prompt()
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
    message: List<ChatMessage>,
    screenshotFileName: String,
    agentCommandMap: Map<String, AgentCommand>
  ): ArbiterContext.Turn {
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
      ArbiterContext.Turn(
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

  private fun chatCompletion(messages: List<ChatMessage>): String {
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
}


