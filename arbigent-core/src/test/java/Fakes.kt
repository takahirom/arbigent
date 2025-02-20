package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import maestro.TreeNode
import maestro.orchestra.MaestroCommand

class FakeDevice : ArbigentDevice {
  override fun executeCommands(commands: List<MaestroCommand>) {
    arbigentDebugLog("FakeDevice.executeCommands: $commands")
  }

  override fun os(): ArbigentDeviceOs {
    arbigentDebugLog("FakeDevice.os")
    return ArbigentDeviceOs.Android
  }

  override fun waitForAppToSettle(appId: String?) {
    arbigentDebugLog("FakeDevice.waitForAppToSettle")
  }

  override fun focusedTreeString(): String {
    arbigentDebugLog("FakeDevice.focusedTreeString")
    return "focusedTreeString"
  }

  private var isClosed = false
  override fun close() {
    arbigentDebugLog("FakeDevice.close")
    isClosed = true
  }

  override fun isClosed(): Boolean {
    arbigentDebugLog("FakeDevice.isClosed")
    return isClosed
  }

  override fun elements(): ArbigentElementList {
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
              "checkable" to "true",
              "checked" to "true",
              "clickable" to "true",
              "enabled" to "true",
              "focusable" to "true",
              "focused" to "true",
            ),
            children = emptyList(),
            clickable = true,
            enabled = true,
            focused = true,
            checked = true,
            selected = true
          ),
          identifierData = ArbigentElement.IdentifierData(listOf(), 0),
          x = 0,
          y = 0,
          width = 100,
          height = 100,
          isVisible = true
        )
      },
      screenWidth = 1000
    )
  }

  override fun viewTreeString(): ArbigentUiTreeStrings {
    arbigentDebugLog("FakeDevice.viewTreeString")
    return ArbigentUiTreeStrings(
      "viewTreeString",
      "optimizedTreeString"
    )
  }
}

class FakeAi : ArbigentAi {
  private var count = 0
  private fun createDecisionOutput(
    agentCommand: ArbigentAgentCommand = ClickWithTextAgentCommand("text")
  ): ArbigentAi.DecisionOutput {
    return ArbigentAi.DecisionOutput(
      listOf(agentCommand),
      ArbigentContextHolder.Step(
        stepId = "stepId",
        agentCommand = agentCommand,
        memo = "memo",
        screenshotFilePath = "screenshotFileName",
        cacheKey = "cacheKey",
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