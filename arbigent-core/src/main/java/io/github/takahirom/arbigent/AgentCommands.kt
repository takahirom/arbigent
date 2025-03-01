package io.github.takahirom.arbigent

import kotlinx.serialization.Serializable
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
  public fun templateForAI(): String
  public fun isSupported(deviceOs: ArbigentDeviceOs): Boolean = true
}

private fun getRegexToIndex(text: String): Pair<String, String> {
  val regex = Regex("""(.*)\[(\d+)]""")
  val matchResult = regex.find(text) ?: return Pair(text, "0")
  val (regexText, index) = matchResult.destructured
  return Pair(regexText, index)
}

@Serializable
public data class ClickWithIndex(val index: Int): ArbigentAgentAction {
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

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName",
            // Should be index like 1 or 2 should NOT be text or id
            "text": "1"
        }
        """.trimIndent()
    }

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

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName",
            // the text with index should be clickable text, or content description. should be in UI hierarchy. should not resource id
            // You can use Regex.
            // If you want to click second button, you can use text[index] e.g.: "text[0]". Try different index if the first one doesn't work.
            "text": "...[index]" 
        }
        """.trimIndent()
    }

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

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName",
            // the text should be id, should be in UI hierarchy
            // You can use Regex
            // If you want to click second button, you can use "button[1]"
            "text": "..." 
        }
        """.trimIndent()
    }

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

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName",
            // Count of down arrow key press
            "text": "1"
        }
        """.trimIndent()
    }

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

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName",
            // Count of up arrow key press
            "text": "1"
        }
        """.trimIndent()
    }

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

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName",
            // Count of right arrow key press
            "text": "1"
        }
        """.trimIndent()
    }

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

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName",
            // Count of left arrow key press
            "text": "1"
        }
        """.trimIndent()
    }

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

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName",
            // Count of center key press
            "text": "1"
        }
        """.trimIndent()
    }

  }
}

@Serializable
public data class DpadAutoFocusWithIdAgentAction(val id: String) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Try to focus by id: $id"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val tvCompatibleDevice = (runInput.device as? ArbigentTvCompatDevice)?: throw NotImplementedError(message = "This action is only available for TV device")
    tvCompatibleDevice.moveFocusToElement(ArbigentTvCompatDevice.Selector.ById.fromId(id))
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadTryAutoFocusById"

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName",
            // the text should be id, should be in UI hierarchy
            // You can use Regex
            // If you want to click second button, you can use text[index] e.g.: "text[0]". Try different index if the first one doesn't work.
            "text": "...[index]"
        }
        """.trimIndent()
    }
  }
}

@Serializable
public data class DpadAutoFocusWithTextAgentAction(val text: String) : ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Try to focus by text: $text"
  }

  override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val tvCompatibleDevice = (runInput.device as? ArbigentTvCompatDevice)?: throw NotImplementedError(message = "This action is only available for TV device")
    tvCompatibleDevice.moveFocusToElement(ArbigentTvCompatDevice.Selector.ByText.fromText(text))
  }

  public companion object : AgentActionType {
    override val actionName: String = "DpadTryAutoFocusByText"

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName",
            // the text should be clickable text, or content description. should be in UI hierarchy. should not resource id
            // You can use Regex
            // If you want to click second button, you can use text[index] e.g.: "text[0]". Try different index if the first one doesn't work.
            "text": "...[index]"
        }
        """.trimIndent()
    }
  }
}

@Serializable
public data class DpadAutoFocusWithIndexAgentAction(val index: Int): ArbigentAgentAction {
  override val actionName: String = Companion.actionName

  public override fun stepLogText(): String {
    return "Try to focus by index: $index"
  }

  public override fun runDeviceAction(runInput: ArbigentAgentAction.RunInput) {
    val elements = runInput.elements
    val tvCompatibleDevice = (runInput.device as? ArbigentTvCompatDevice)?: throw NotImplementedError(message = "This action is only available for TV device")
    tvCompatibleDevice.moveFocusToElement(elements.elements[index])
  }

  public companion object : AgentActionType {
    override val actionName: String = "DPadTryAutoFocusByIndex"

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName",
            // Should be index like 1 or 2 should NOT be text or id
            "text": "1"
        }
        """.trimIndent()
    }
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

    override fun templateForAI(): String {
      return """
        {
            // You have to **Click** on a text field before sending this action
            "action": "$actionName",
            "text": "..."
        }
        """.trimIndent()
    }

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

    override fun isSupported(deviceOs: ArbigentDeviceOs): Boolean {
      return !deviceOs.isIos()
    }

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName"
        }
        """.trimIndent()
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

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName"
        }
        """.trimIndent()
    }

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

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName",
            "text": "..."
        }
        """.trimIndent()
    }
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

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName",
            // Time in milliseconds "text": "1000"
            "text": "..."
        }
        """.trimIndent()
    }
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

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName"
        }
        """.trimIndent()
    }

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

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName"
            // Please write the reason why it failed
            "text": "..."
        }
        """.trimIndent()
    }

  }
}
