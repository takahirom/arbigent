package io.github.takahirom.arbigent

import maestro.KeyCode
import maestro.MaestroException
import maestro.orchestra.*

public interface ArbigentAgentCommand {
  public val actionName: String
  public fun runOrchestraCommand(device: ArbigentDevice)
  public fun stepLogText(): String

  public fun isGoal(): Boolean {
    return actionName == GoalAchievedAgentCommand.actionName
  }
}

public interface AgentCommandType {
  public val actionName: String
  public fun templateForAI(): String
}

private fun getRegexToIndex(text: String): Pair<String, String> {
  val regex = Regex("""(.*)\[(\d+)]""")
  val matchResult = regex.find(text) ?: return Pair(text, "0")
  val (regexText, index) = matchResult.destructured
  return Pair(regexText, index)
}

public data class ClickWithTextAgentCommand(val textRegex: String) : ArbigentAgentCommand {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Click on text: $textRegex"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    val (textRegex, index) = getRegexToIndex(textRegex)
    val maestroCommand = MaestroCommand(
      tapOnElement = TapOnElementCommand(
        selector = ElementSelector(
          textRegex = "$textRegex", index = index
        ), waitToSettleTimeoutMs = 500, retryIfNoChange = false, waitUntilVisible = false
      )
    )
    try {
      device.executeCommands(
        commands = listOf(
          maestroCommand
        ),
      )
    } catch (e: MaestroException) {
      device.executeCommands(
        commands = listOf(
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

  public companion object : AgentCommandType {
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

public data class ClickWithIdAgentCommand(val textRegex: String) : ArbigentAgentCommand {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Click on id: $textRegex"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    val (textRegex, index) = getRegexToIndex(textRegex)
    device.executeCommands(
      commands = listOf(
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

  public companion object : AgentCommandType {
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

public data class DpadDownArrowAgentCommand(val count: Int) : ArbigentAgentCommand {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press down arrow key $count times"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    device.executeCommands(
      commands = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_DOWN
          )
        )
      },
    )
  }

  public companion object : AgentCommandType {
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

public data class DpadUpArrowAgentCommand(val count: Int) : ArbigentAgentCommand {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press up arrow key $count times"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    device.executeCommands(
      commands = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_UP
          )
        )
      },
    )
  }

  public companion object : AgentCommandType {
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

public data class DpadRightArrowAgentCommand(val count: Int) : ArbigentAgentCommand {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press right arrow key $count times"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    device.executeCommands(
      commands = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_RIGHT
          )
        )
      },
    )
  }

  public companion object : AgentCommandType {
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

public data class DpadLeftArrowAgentCommand(val count: Int) : ArbigentAgentCommand {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press left arrow key $count times"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    device.executeCommands(
      commands = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_LEFT
          )
        )
      },
    )
  }

  public companion object : AgentCommandType {
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

public data class DpadCenterAgentCommand(val count: Int) : ArbigentAgentCommand {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press center key $count times"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    device.executeCommands(
      commands = List(count) {
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code = KeyCode.REMOTE_CENTER
          )
        )
      },
    )
  }

  public companion object : AgentCommandType {
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

public data class DpadAutoFocusWithIdAgentCommand(val id: String) : ArbigentAgentCommand {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Focus on id: $id"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    val device = (device as? ArbigentTvCompatDevice)?: throw NotImplementedError(message = "This command is only available for TV device")
    device.moveFocusToElement(ArbigentTvCompatDevice.Selector.ById.fromId(id))
  }

  public companion object : AgentCommandType {
    override val actionName: String = "DpadAutoFocusWithId"

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

public data class DpadAutoFocusWithTextAgentCommand(val text: String) : ArbigentAgentCommand {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Focus on text: $text"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    val device = (device as? ArbigentTvCompatDevice)?: throw NotImplementedError(message = "This command is only available for TV device")
    device.moveFocusToElement(ArbigentTvCompatDevice.Selector.ByText.fromText(text))
  }

  public companion object : AgentCommandType {
    override val actionName: String = "DpadAutoFocusWithText"

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

public data class InputTextAgentCommand(val text: String) : ArbigentAgentCommand {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Input text: $text"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    device.executeCommands(
      commands = listOf(
        MaestroCommand(
          inputTextCommand = InputTextCommand(
            text
          )
        )
      ),
    )
  }

  public companion object : AgentCommandType {
    override val actionName: String = "InputText"

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
}

public class BackPressAgentCommand() : ArbigentAgentCommand {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Press back button"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    device.executeCommands(
      commands = listOf(
        MaestroCommand(
          backPressCommand = BackPressCommand()
        )
      )
    )
  }

  public companion object : AgentCommandType {
    override val actionName: String = "BackPress"

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName"
        }
        """.trimIndent()
    }

  }
}

public class ScrollAgentCommand : ArbigentAgentCommand {
  override val actionName: String = "Scroll"

  override fun stepLogText(): String {
    return "Scroll"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    device.executeCommands(
      commands = listOf(
        MaestroCommand(
          scrollCommand = ScrollCommand()
        )
      ),
    )
  }

  public companion object : AgentCommandType {
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

public data class KeyPressAgentCommand(val keyName: String) : ArbigentAgentCommand {
  override val actionName: String = "KeyPress"

  override fun stepLogText(): String {
    return "Press key: $keyName"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    val code = KeyCode.getByName(keyName)
      ?: throw MaestroException.InvalidCommand(message = "Unknown key: $keyName")
    device.executeCommands(
      commands = listOf(
        MaestroCommand(
          pressKeyCommand = PressKeyCommand(
            code
          )
        )
      ),
    )
  }

  public companion object : AgentCommandType {
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

public class WaitAgentCommand(public val timeMs: Int) : ArbigentAgentCommand {
  override val actionName: String = "Wait"

  override fun stepLogText(): String {
    return "Wait for $timeMs ms"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
    Thread.sleep(timeMs.toLong())
  }

  public companion object : AgentCommandType {
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

public class GoalAchievedAgentCommand : ArbigentAgentCommand {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Goal achieved"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
  }

  public companion object : AgentCommandType {
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

public class FailedAgentCommand : ArbigentAgentCommand {
  override val actionName: String = Companion.actionName

  override fun stepLogText(): String {
    return "Failed"
  }

  override fun runOrchestraCommand(device: ArbigentDevice) {
  }

  public companion object : AgentCommandType {
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
