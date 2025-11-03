package io.github.takahirom.arbigent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import maestro.KeyCode
import maestro.MaestroException
import maestro.orchestra.*

@Serializable
public sealed interface ArbigentAgentAction {
  public val actionName: String
  public fun runDeviceAction(runInput: RunInput)
  public fun stepLogText(): String

  public class RunInput(
    public val device: ArbigentDevice,
    public val elements: ArbigentElementList,
  )

  public fun isGoal(): Boolean {
    return actionName == GoalAchievedAgentAction.actionName
  }
}

public interface AgentActionType {
  public val actionName: String

  /**
   * Returns a description of the action.
   */
  public fun actionDescription(): String

  public data class Argument(
    val name: String,
    val type: String,
    val description: String
  ) {
    public fun toJson(): String {
      val description = JsonPrimitive(description)
      return """
        "$name": { "type": "$type", "description": $description }
      """.trimIndent()
    }
  }

  /**
   * Returns a list of argument descriptions for the action.
   */
  public fun arguments(): List<Argument>

  public fun isSupported(deviceOs: ArbigentDeviceOs): Boolean = true
}

private fun getRegexToIndex(text: String): Pair<String, String> {
  val regex = Regex("""(.*)\[(\d+)]""")
  val matchResult = regex.find(text) ?: return Pair(text, "0")
  val (regexText, index) = matchResult.destructured
  return Pair(regexText, index)
}

@Serializable
public data class ClickWithIndex(val index: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Click on index: $index"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val elements = runInput.elements
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          tapOnPointV2Command = TapOnPointV2Command(
            point = "${elements.elements[index].rect.centerX()},${elements.elements[index].rect.centerY()}"
          )
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "ClickWithIndex"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The index of the ELEMENTS to click on. Should be a number like 1 or 2, NOT text or ID."
        )
      )

    override fun actionDescription(): String = "Click on an element by its index in the ELEMENTS"
  }
}

@Serializable
public data class ClickWithTextAgentAction(val textRegex: String) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Click on text: $textRegex"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val (textRegex, index) = getRegexToIndex(textRegex)
    val maestroCommand = MaestroCommand(
      tapOnElement = TapOnElementCommand(
        selector = ElementSelector(
          textRegex = textRegex, index = index
        ), waitToSettleTimeoutMs = 500, retryIfNoChange = false, waitUntilVisible = false
      )
    )
    try {
      runInput.device.executeActions(
        actions = listOf(
          maestroCommand
        ),
      )
    } catch (e: MaestroException) {
      runInput.device.executeActions(
        actions = listOf(
          maestroCommand.copy(
            tapOnElement = maestroCommand.tapOnElement!!.copy(
              selector = maestroCommand.tapOnElement!!.selector.copy(
                textRegex = ".*$textRegex.*"
              )
            )
          )
        ),
      )
    }
  }

  public companion object : AgentActionType {
    override val actionName: String = "ClickWithText"

    override fun actionDescription(): String = "Click on an element by its text content"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The text with index should be clickable text, or content description. Should be in UI hierarchy, not a resource id. You can use Regex. If you want to click second button, you can use text[index] e.g.: \"text[0]\". Try different index if the first one doesn't work."
        )
      )
  }
}

@Serializable
public data class ClickWithIdAgentAction(val textRegex: String) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Click on id: $textRegex"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val (textRegex, index) = getRegexToIndex(textRegex)
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          tapOnElement = TapOnElementCommand(
            selector = ElementSelector(
              idRegex = textRegex, index = index
            ), waitToSettleTimeoutMs = 500, waitUntilVisible = false
          )
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "ClickWithId"

    override fun actionDescription(): String = "Click on an element by its ID"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The text should be an ID that exists in the UI hierarchy. You can use Regex. If you want to click the second button, you can use \"button[1]\"."
        )
      )
  }
}

@Serializable
public data class DpadDownArrowAgentAction(val count: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press down arrow key $count times"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_DOWN
          )
        )
      },
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadDownArrow"

    override fun actionDescription(): String = "Press the down arrow key on a D-pad"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The number of times to press the down arrow key"
        )
      )
  }
}

@Serializable
public data class DpadUpArrowAgentAction(val count: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press up arrow key $count times"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_UP
          )
        )
      },
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadUpArrow"

    override fun actionDescription(): String = "Press the up arrow key on a D-pad"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The number of times to press the up arrow key"
        )
      )
  }
}

@Serializable
public data class DpadRightArrowAgentAction(val count: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press right arrow key $count times"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_RIGHT
          )
        )
      },
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadRightArrow"

    override fun actionDescription(): String = "Press the right arrow key on a D-pad"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The number of times to press the right arrow key"
        )
      )
  }
}

@Serializable
public data class DpadLeftArrowAgentAction(val count: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press left arrow key $count times"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_LEFT
          )
        )
      },
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadLeftArrow"

    override fun actionDescription(): String = "Press the left arrow key on a D-pad"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The number of times to press the left arrow key"
        )
      )
  }
}

@Serializable
public data class DpadCenterAgentAction(val count: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press center key $count times"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_CENTER
          )
        )
      },
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadCenter"

    override fun actionDescription(): String = "Press the center key on a D-pad. Please refer to FOCUSED_TREE to know what will be clicked."

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The number of times to press the center key"
        )
      )
  }
}

@Serializable
public data class DpadAutoFocusWithIdAgentAction(val id: String) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Try to focus by id: $id"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val tvCompatibleDevice = (runInput.device as? ArbigentTvCompatDevice)
      ?: throw NotImplementedError(message = "This action is only available for TV device")
    tvCompatibleDevice.moveFocusToElement(ArbigentTvCompatDevice.Selector.ById.fromId(id))
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadTryAutoFocusById"

    override fun actionDescription(): String = "Try to focus on an element by its ID using D-pad navigation"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The ID of the element to focus on. Should be in UI hierarchy. You can use Regex. If you want to focus on the second button, you can use text[index] e.g.: \"text[0]\". Try different index if the first one doesn't work."
        )
      )
  }
}

@Serializable
public data class DpadAutoFocusWithTextAgentAction(val text: String) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Try to focus by text: $text"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val tvCompatibleDevice = (runInput.device as? ArbigentTvCompatDevice)
      ?: throw NotImplementedError(message = "This action is only available for TV device")
    tvCompatibleDevice.moveFocusToElement(ArbigentTvCompatDevice.Selector.ByText.fromText(text))
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadTryAutoFocusByText"

    override fun actionDescription(): String = "Try to focus on an element by its text content using D-pad navigation"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The text content or content description of the element to focus on. Should be in UI hierarchy, not a resource ID. You can use Regex. If you want to focus on the second button, you can use text[index] e.g.: \"text[0]\". Try different index if the first one doesn't work."
        )
      )
  }
}

@Serializable
public data class DpadAutoFocusWithIndexAgentAction(val index: Int) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  public override fun stepLogText(): String {
    return "Try to focus by index: $index"
  }

  public override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val elements = runInput.elements
    val tvCompatibleDevice = (runInput.device as? ArbigentTvCompatDevice)
      ?: throw NotImplementedError(message = "This action is only available for TV device")
    tvCompatibleDevice.moveFocusToElement(elements.elements[index])
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadTryAutoFocusByIndex"

    override fun actionDescription(): String =
      "Try to focus on an element by its index in the ELEMENTS using D-pad navigation"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The index of the ELEMENTS to focus on. Should be a number like 1 or 2, NOT text or ID."
        )
      )
  }
}

@Serializable
public data class InputTextAgentAction(val text: String) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Input text: $text"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          inputTextCommand = InputTextCommand(
            text
          )
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "InputText"

    override fun actionDescription(): String = "Input text into a text field"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The text to input. You must click on a text field before sending this action."
        )
      )
  }
}

@Serializable
public class BackPressAgentAction : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press back button"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          backPressCommand = BackPressCommand()
        )
      )
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "BackPress"

    override fun actionDescription(): String = "Press the back button on the device"

    override fun arguments(): List<AgentActionType.Argument> = emptyList()

    override fun isSupported(deviceOs: ArbigentDeviceOs): Boolean {
      return !deviceOs.isIos()
    }
  }
}

@Serializable
public class ScrollAgentAction : ArbigentAgentAction {
  override val actionName: String = "Scroll"

  override fun stepLogText(): String {
    return "Scroll"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          scrollCommand = ScrollCommand()
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "Scroll"

    override fun actionDescription(): String = "Scroll down on the current screen"

    override fun arguments(): List<AgentActionType.Argument> = emptyList()
  }
}

@Serializable
public data class KeyPressAgentAction(val keyName: String) : ArbigentAgentAction {
  override val actionName: String = "KeyPress"

  override fun stepLogText(): String {
    return "Press key: $keyName"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val code = KeyCode.getByName(keyName)
      ?: throw MaestroException.InvalidCommand(message = "Unknown key: $keyName")
    runInput.device.executeActions(
      actions = listOf(
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code
          )
        )
      ),
    )
  }

  public companion object : AgentActionType {
    override val actionName: String = "KeyPress"

    override fun actionDescription(): String = "Press a specific key on the device"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The name of the key to press (e.g., ENTER, TAB, etc.)"
        )
      )
  }
}

@Serializable
public class WaitAgentAction(private val timeMs: Int) : ArbigentAgentAction {
  override val actionName: String = "Wait"

  override fun stepLogText(): String {
    return "Wait for $timeMs ms"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    Thread.sleep(timeMs.toLong())
  }

  public companion object : AgentActionType {
    override val actionName: String = "Wait"

    override fun actionDescription(): String = "Wait for a specified amount of time"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "Time to wait in milliseconds (e.g., \"1000\" for 1 second)"
        )
      )
  }
}

@Serializable
public class GoalAchievedAgentAction : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Goal achieved"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
  }

  public companion object : AgentActionType {
    override val actionName: String = "GoalAchieved"

    override fun actionDescription(): String =
      "Indicate that the goal has been achieved and the test scenario is complete"

    override fun arguments(): List<AgentActionType.Argument> = emptyList()
  }
}

@Serializable
public class FailedAgentAction : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Failed"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
  }

  public companion object : AgentActionType {
    override val actionName: String = "Failed"

    override fun actionDescription(): String = "Indicate that the test scenario has failed"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "text",
          type = "string",
          description = "The reason why the test scenario failed"
        )
      )
  }
}

@Serializable
public data class ExecuteMcpToolAgentAction(
  val tool: MCPTool,
  val executeToolArgs: ExecuteToolArgs
) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Execute MCP tool: ${tool.name} with args: ${executeToolArgs.arguments}"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    // This is a no-op for device actions, as tool execution is handled separately
  }

  public companion object : AgentActionType {
    override val actionName: String = "ExecuteTool"

    override fun actionDescription(): String = "Execute a tool via MCP"

    override fun arguments(): List<AgentActionType.Argument> =
      listOf(
        AgentActionType.Argument(
          name = "tool",
          type = "object",
          description = "The tool to execute"
        ),
        AgentActionType.Argument(
          name = "args",
          type = "object",
          description = "The arguments for the tool"
        )
      )
  }
}
