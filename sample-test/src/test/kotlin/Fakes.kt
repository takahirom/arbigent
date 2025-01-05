package io.github.takahirom.arbiter.sample.test

import io.github.takahirom.arbiter.AgentCommand
import io.github.takahirom.arbiter.ArbiterAi
import io.github.takahirom.arbiter.ArbiterContextHolder
import io.github.takahirom.arbiter.ArbiterDevice
import io.github.takahirom.arbiter.ClickWithTextAgentCommand
import io.github.takahirom.arbiter.GoalAchievedAgentCommand
import io.github.takahirom.arbiter.arbiterDebugLog
import maestro.orchestra.MaestroCommand

class FakeDevice : ArbiterDevice {
  override fun executeCommands(commands: List<MaestroCommand>) {
    arbiterDebugLog("FakeDevice.executeCommands: $commands")
  }

  override fun focusedTreeString(): String {
    arbiterDebugLog("FakeDevice.focusedTreeString")
    return "focusedTreeString"
  }

  override fun close() {
    arbiterDebugLog("FakeDevice.close")
  }

  override fun viewTreeString(): String {
    arbiterDebugLog("FakeDevice.viewTreeString")
    return "viewTreeString"
  }
}

class FakeAi : ArbiterAi {
  var count = 0
  fun createDecisionOutput(
    agentCommand: AgentCommand = ClickWithTextAgentCommand("text")
  ): ArbiterAi.DecisionOutput {
    return ArbiterAi.DecisionOutput(
      listOf(agentCommand),
      ArbiterContextHolder.Step(
        agentCommand = agentCommand,
        memo = "memo",
        screenshotFileName = "screenshotFileName"
      )
    )
  }

  override fun decideAgentCommands(decisionInput: ArbiterAi.DecisionInput): ArbiterAi.DecisionOutput {
    arbiterDebugLog("FakeAi.decideWhatToDo")
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
}