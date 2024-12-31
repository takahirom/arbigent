package com.github.takahirom.arbiter

import maestro.KeyCode
import maestro.MaestroException
import maestro.orchestra.*

interface AgentCommand {
  val actionName: String
  fun runOrchestraCommand(device: ArbiterDevice)
  fun stepLogText(): String

  fun isGoal(): Boolean {
    return actionName == GoalAchievedAgentCommand.actionName
  }
}

interface AgentCommandType {
  val actionName: String
  fun templateForAI(): String
}

private fun getRegexToIndex(text: String): Pair<String, String> {
  val regex = Regex("""(.*)\[(\d+)]""")
  val matchResult = regex.find(text) ?: return Pair(text, "0")
  val (regexText, index) = matchResult.destructured
  return Pair(regexText, index)
}

data class ClickWithTextAgentCommand(val textRegex: String) : AgentCommand {
  override val actionName = Companion.actionName

  override fun stepLogText(): String {
    return "Click on text: $textRegex"
  }

  override fun runOrchestraCommand(device: ArbiterDevice) {
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

  companion object : AgentCommandType {
    override val actionName = "ClickWithText"

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

data class ClickWithIdAgentCommand(val textRegex: String) : AgentCommand {
  override val actionName = Companion.actionName

  override fun stepLogText(): String {
    return "Click on id: $textRegex"
  }

  override fun runOrchestraCommand(device: ArbiterDevice) {
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

  companion object : AgentCommandType {
    override val actionName = "ClickWithId"

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

data class DpadDownArrowAgentCommand(val count: Int) : AgentCommand {
  override val actionName = Companion.actionName

  override fun stepLogText(): String {
    return "Press down arrow key $count times"
  }

  override fun runOrchestraCommand(device: ArbiterDevice) {
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

  companion object : AgentCommandType {
    override val actionName = "DpadDownArrow"

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

data class DpadUpArrowAgentCommand(val count: Int) : AgentCommand {
  override val actionName = Companion.actionName

  override fun stepLogText(): String {
    return "Press up arrow key $count times"
  }

  override fun runOrchestraCommand(device: ArbiterDevice) {
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

  companion object : AgentCommandType {
    override val actionName = "DpadUpArrow"

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

data class DpadRightArrowAgentCommand(val count: Int) : AgentCommand {
  override val actionName = Companion.actionName

  override fun stepLogText(): String {
    return "Press right arrow key $count times"
  }

  override fun runOrchestraCommand(device: ArbiterDevice) {
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

  companion object : AgentCommandType {
    override val actionName = "DpadRightArrow"

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

data class DpadLeftArrowAgentCommand(val count: Int) : AgentCommand {
  override val actionName = Companion.actionName

  override fun stepLogText(): String {
    return "Press left arrow key $count times"
  }

  override fun runOrchestraCommand(device: ArbiterDevice) {
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

  companion object : AgentCommandType {
    override val actionName = "DpadLeftArrow"

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

data class DpadCenterAgentCommand(val count: Int) : AgentCommand {
  override val actionName = Companion.actionName

  override fun stepLogText(): String {
    return "Press center key $count times"
  }

  override fun runOrchestraCommand(device: ArbiterDevice) {
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

  companion object : AgentCommandType {
    override val actionName = "DpadCenter"

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

data class InputTextAgentCommand(val text: String) : AgentCommand {
  override val actionName = Companion.actionName

  override fun stepLogText(): String {
    return "Input text: $text"
  }

  override fun runOrchestraCommand(device: ArbiterDevice) {
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

  companion object : AgentCommandType {
    override val actionName = "InputText"

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

class BackPressAgentCommand() : AgentCommand {
  override val actionName = Companion.actionName

  override fun stepLogText(): String {
    return "Press back button"
  }

  override fun runOrchestraCommand(device: ArbiterDevice) {
    device.executeCommands(
      commands = listOf(
        MaestroCommand(
          backPressCommand = BackPressCommand()
        )
      )
    )
  }

  companion object : AgentCommandType {
    override val actionName = "BackPress"

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName"
        }
        """.trimIndent()
    }

  }
}

class ScrollAgentCommand : AgentCommand {
  override val actionName: String = "Scroll"

  override fun stepLogText(): String {
    return "Scroll"
  }

  override fun runOrchestraCommand(device: ArbiterDevice) {
    device.executeCommands(
      commands = listOf(
        MaestroCommand(
          scrollCommand = ScrollCommand()
        )
      ),
    )
  }

  companion object : AgentCommandType {
    override val actionName = "Scroll"

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName"
        }
        """.trimIndent()
    }

  }
}

data class KeyPressAgentCommand(val keyName: String) : AgentCommand {
  override val actionName = "KeyPress"

  override fun stepLogText(): String {
    return "Press key: $keyName"
  }

  override fun runOrchestraCommand(device: ArbiterDevice) {
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

  companion object : AgentCommandType {
    override val actionName = "KeyPress"

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

class GoalAchievedAgentCommand : AgentCommand {
  override val actionName = "GoalAchieved"

  override fun stepLogText(): String {
    return "Goal achieved"
  }

  override fun runOrchestraCommand(device: ArbiterDevice) {
  }

  companion object : AgentCommandType {
    override val actionName = "GoalAchieved"

    override fun templateForAI(): String {
      return """
        {
            "action": "$actionName"
        }
        """.trimIndent()
    }

  }
}
