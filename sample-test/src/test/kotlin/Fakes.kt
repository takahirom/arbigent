package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import maestro.TreeNode
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

  override fun elements(meaningfulAttributes: Set<String>): ArbigentElementList {
    return ArbigentElementList(
      (0..10).map {
        ArbigentElement(
          index = it,
          textForAI = "textForAI",
          rawText = "rawText",
          treeNode = TreeNode(
            attributes = mutableMapOf(
              "text" to "text",
              "resource-id" to "resource-id",
              "content-desc" to "content-desc",
              "class" to "class",
              "package" to "package",
              "checkable" to "true",
              "checked" to "true",
              "clickable" to "clickable",
              "enabled" to "enabled",
              "focusable" to "true",
              "focused" to "true",
              "scrollable" to "scrollable",
              "long-clickable" to "long-clickable",
              "password" to "password",
              "selected" to "selected",
              "bounds" to "[0,0][100,100]"
            ),
            children = emptyList(),
            clickable = true,
            enabled = true,
            focused = true,
            checked = true,
            selected = true
          ),
          x = 0,
          y = 0,
          width = 100,
          height = 100,
          isVisible = true
        )
      }
    )
  }

  override fun viewTreeString(): String {
    arbigentDebugLog("FakeDevice.viewTreeString")
    return "viewTreeString"
  }
}

class FakeAi : ArbigentAi {
  var count = 0
  fun createDecisionOutput(
    agentCommand: ArbigentAgentCommand = ClickWithTextAgentCommand("text")
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