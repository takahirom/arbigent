package com.github.takahirom.arbiter

import maestro.KeyCode
import maestro.MaestroException
import maestro.orchestra.*

interface AgentCommand {
  val actionName: String
  fun runOrchestraCommand(device: Device)
  fun templateForAI(): String
}

data class ClickWithTextAgentCommand(val textRegex: String) : AgentCommand {
  override val actionName = "ClickWithText"

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

  override fun runOrchestraCommand(device: Device) {
    device.executeCommands(
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

  override fun runOrchestraCommand(device: Device) {
    device.executeCommands(
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

  override fun runOrchestraCommand(device: Device) {
  }

  override fun templateForAI(): String {
    return """
        {
            "action": "$actionName"
        }
        """.trimIndent()
  }
}
