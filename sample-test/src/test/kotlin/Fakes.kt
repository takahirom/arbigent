package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.AgentCommand
import io.github.takahirom.arbigent.ArbigentAi
import io.github.takahirom.arbigent.ArbigentContextHolder
import io.github.takahirom.arbigent.ArbigentDevice
import io.github.takahirom.arbigent.ClickWithTextAgentCommand
import io.github.takahirom.arbigent.GoalAchievedAgentCommand
import io.github.takahirom.arbigent.arbigentDebugLog
import maestro.orchestra.MaestroCommand

class FakeDevice : ArbigentDevice {
  override fun executeCommands(commands: List<MaestroCommand>) {
    arbigentDebugLog("FakeDevice.executeCommands: $commands")
  }

  override fun focusedTreeString(): String {
    arbigentDebugLog("FakeDevice.focusedTreeString")
    return "focusedTreeString"
  }

  override fun close() {
    arbigentDebugLog("FakeDevice.close")
  }

  override fun viewTreeString(): String {
    arbigentDebugLog("FakeDevice.viewTreeString")
    return "viewTreeString"
  }
}

class FakeAi : ArbigentAi {
  var count = 0
  fun createDecisionOutput(
    agentCommand: AgentCommand = ClickWithTextAgentCommand("text")
  ): ArbigentAi.DecisionOutput {
    return ArbigentAi.DecisionOutput(
      listOf(agentCommand),
      ArbigentContextHolder.Step(
        agentCommand = agentCommand,
        memo = "memo",
        screenshotFilePath = "screenshotFileName"
      )
    )
  }

  override fun decideAgentCommands(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
    arbigentDebugLog("FakeAi.decideWhatToDo")
    if (count == 0) {
      count++
      return createDecisionOutput()
    } else if (count == 1) {
      count++
      return createDecisionOutput()
    } else {
      return createDecisionOutput(
        agentCommand = GoalAchievedAgentCommand()
      )
    }
  }

  override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
    arbigentDebugLog("FakeAi.assertImage")
    return ArbigentAi.ImageAssertionOutput(
      listOf(
        ArbigentAi.ImageAssertionResult(
          assertionPrompt = "assertionPrompt",
          isPassed = true,
          fulfillmentPercent = 100,
          explanation = "explanation"
        )
      )
    )
  }
}