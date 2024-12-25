package com.github.takahirom.arbiter

import maestro.KeyCode
import maestro.MaestroException
import maestro.orchestra.*

interface AgentCommand {
  val actionName: String
  fun runOrchestraCommand(device: Device)
}

interface AgentCommandType {
  val actionName: String
  fun templateForAI(): String
}

data class ClickWithTextAgentCommand(val textRegex: String) : AgentCommand {
  override val actionName = Companion.actionName

  override fun runOrchestraCommand(device: Device) {
    device.executeCommands(
      commands = listOf(
        MaestroCommand(
          tapOnElement = TapOnElementCommand(
            ElementSelector(textRegex = textRegex)
          )
        )
      ),
    )
  }

  companion object : AgentCommandType {
    override val actionName = "ClickWithText"

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
}

data class ClickWithIdAgentCommand(val textRegex: String) : AgentCommand {
  override val actionName = Companion.actionName

  override fun runOrchestraCommand(device: Device) {
    device.executeCommands(
      commands = listOf(
        MaestroCommand(
          tapOnElement = TapOnElementCommand(
            ElementSelector(idRegex = textRegex)
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
            "text": "..." 
        }
        """.trimIndent()
    }

  }
}

data class InputTextAgentCommand(val text: String) : AgentCommand {
  override val actionName = Companion.actionName

  override fun runOrchestraCommand(device: Device) {
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

  override fun runOrchestraCommand(device: Device) {
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

  override fun runOrchestraCommand(device: Device) {
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

  override fun runOrchestraCommand(device: Device) {
    val code = KeyCode.getByName(keyName) ?: throw MaestroException.InvalidCommand(message = "Unknown key: $keyName")
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

  override fun runOrchestraCommand(device: Device) {
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
