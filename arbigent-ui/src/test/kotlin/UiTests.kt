package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.takahirom.arbigent.*
import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.takahirom.robospec.BehaviorsTreeBuilder
import io.github.takahirom.robospec.DescribedBehavior
import io.github.takahirom.robospec.DescribedBehaviors
import io.github.takahirom.robospec.describeBehaviors
import io.github.takahirom.robospec.execute
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import maestro.orchestra.MaestroCommand
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class UiTests(private val behavior: DescribedBehavior<TestRobot>) {
  @Test
  fun test() {
    val testDispatcher = StandardTestDispatcher()
    ArbigentCoroutinesDispatcher.dispatcher = testDispatcher
    globalKeyStoreFactory = TestKeyStoreFactory()
    runComposeUiTest {
      runTest(testDispatcher) {
        val robot = TestRobot(this, this@runComposeUiTest)
        robot.setContent()
        behavior.execute(robot)
      }
    }
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun data(): DescribedBehaviors<TestRobot> {
      return describeBehaviors<TestRobot>("Tests") {
        doIt {
          waitALittle()
        }
        describe("when opens the app") {
          itShould("have a Connect to device") {
            capture(it)
            assertConnectToDeviceButtonExists()
          }
          describe("when clicks the Connect to device") {
            doIt {
              clickConnectToDeviceButton()
            }
            itShould("show the Add scenario") {
              capture(it)
              assertAddScenarioButtonExists()
            }
            describe("when clicks the Add scenario") {
              doIt {
                clickAddScenarioButton()
              }
              itShould("show goal input") {
                capture(it)
                assertGoalInputExists()
              }
              describe("when enter goals and image assertion") {
                doIt {
                  enterGoal("launch the app")
                  expandOptions()
                  enterImageAssertion("The screen should show the app")
                }
                describe("when run") {
                  describe("should finish the scenario") {
                    doIt {
                      clickRunButton()
                      waitUntilScenarioRunning()
                    }
                    itShould("show goal achieved") {
                      capture(it)
                      assertGoalAchieved()
                    }
                  }
                }
                describe("when ai fail with image and run") {
                  doIt {
                    setupAiStatus(FakeAi.AiStatus.ImageAssertionFailed())
                    clickRunButton()
                  }
                  describe("should finish the scenario") {
                    doIt {
                      waitUntilScenarioRunning()
                    }
                    itShould("show goal not achieved") {
                      capture(it)
                      assertGoalNotAchievedByImageAssertion()
                    }
                  }
                }
              }
              describe("when enter goals and run") {
                doIt {
                  enterGoal("launch the app")
                  clickRunButton()
                }
                describe("should finish the scenario") {
                  doIt {
                    waitUntilScenarioRunning()
                  }
                  itShould("show goal achieved") {
                    capture(it)
                    assertGoalAchieved()
                  }
                }
              }
              describeEnterDependencyGoal(
                firstGoal = "launch the app",
                secondGoal = "launch the app2"
              )
              // Same goal
              describeEnterDependencyGoal(
                firstGoal = "launch the app",
                secondGoal = "launch the app"
              )
            }
          }
        }
      }
    }

    private fun BehaviorsTreeBuilder<TestRobot>.describeEnterDependencyGoal(
      firstGoal: String,
      secondGoal: String,
    ) {
      describe("when add scenarios") {
        doIt {
          enterGoal(firstGoal)
          clickAddScenarioButton()
          enterGoal(secondGoal)
        }
        describe("when run all") {
          doIt {
            clickRunAllButton()
          }
          describe("when finish the scenario") {
            doIt {
              waitUntilScenarioRunning()
            }
            itShould("show goal achieved") {
              capture(it)
              assertGoalAchieved()
            }
          }
        }
        describe("when add dependency and run") {
          doIt {
            expandOptions()
            clickDependencyDropDown()
            selectDependencyDropDown(firstGoal)
            collapseOptions()
            clickRunAllButton()
          }
          describe("when finish the scenario") {
            doIt {
              waitUntilScenarioRunning()
            }
            itShould("show goal achieved") {
              capture(it)
              assertTwoGoalAchieved()
            }
          }
        }
      }
    }

    private fun ComposeUiTest.capture(it: DescribedBehavior<ComposeUiTest>) {
      onRoot().captureRoboImage("$it.png")
    }
  }
}

@ExperimentalTestApi
class TestRobot(
  private val testScope: TestScope,
  private val composeUiTest: ComposeUiTest,
) {
  private val fakeAi = FakeAi()
  private val fakeDevice = FakeDevice()

  fun setupAiStatus(aiStatus: FakeAi.AiStatus) {
    fakeAi.status = aiStatus
  }

  fun clickConnectToDeviceButton() {
    waitALittle()
    composeUiTest.onNode(hasText("Connect to device")).performClick()
    waitALittle()
  }

  fun clickAddScenarioButton() {
    composeUiTest.onNode(hasContentDescription("Add")).performClick()
    waitALittle()
  }

  fun enterGoal(goal: String) {
    composeUiTest.onNode(hasTestTag("goal")).performTextInput(goal)
    waitALittle()
  }

  fun enterImageAssertion(assertion: String) {
    composeUiTest.onNode(hasContentDescription("Add image assertion")).performClick()
    composeUiTest.onNode(hasTestTag("image_assertion")).performTextInput(assertion)
    waitALittle()
  }

  fun clickRunButton() {
    composeUiTest.onNode(hasContentDescription("Run")).performClick()
    waitALittle()
  }

  fun clickRunAllButton() {
    composeUiTest.onNode(hasContentDescription("Run all")).performClick()
    waitALittle()
  }

  fun waitUntilScenarioRunning() {
    repeat(5) {
      composeUiTest.waitUntil(
        timeoutMillis = 1000
      ) {
        try {
          composeUiTest.onNode(hasTestTag("scenario_running"), useUnmergedTree = true)
            .assertExists()
          false
        } catch (e: AssertionError) {
          true
        }
      }
      waitALittle()
    }
  }

  fun waitALittle() {
    testScope.advanceUntilIdle()
  }

  fun assertGoalAchieved() {
    composeUiTest.onNode(hasTestTag("scenario_running")).assertDoesNotExist()
    composeUiTest.onNode(hasText("Goal achieved", true), useUnmergedTree = true).assertExists()
  }

  fun assertGoalNotAchievedByImageAssertion() {
    composeUiTest.onNode(hasTestTag("scenario_running")).assertDoesNotExist()
    composeUiTest.onAllNodes(hasText("Failed to reach the goal", true), useUnmergedTree = true)
      .onLast().assertIsDisplayed()
  }

  fun assertTwoGoalAchieved() {
    composeUiTest.onNode(hasTestTag("scenario_running")).assertDoesNotExist()
    composeUiTest.onAllNodes(hasText("Goal achieved", true), useUnmergedTree = true)
      .assertCountEquals(2)
  }

  fun assertConnectToDeviceButtonExists() {
    composeUiTest.onNode(hasText("Connect to device")).assertExists()
  }

  fun assertAddScenarioButtonExists() {
    composeUiTest.onNode(hasContentDescription("Add")).assertExists()
  }

  fun assertGoalInputExists() {
    composeUiTest.onNode(hasTestTag("goal")).assertExists()
  }

  fun expandOptions() {
    composeUiTest.onNode(hasContentDescription("Expand Options")).performClick()
  }

  fun collapseOptions() {
    composeUiTest.onNode(hasContentDescription("Collapse Options")).performClick()
  }

  fun clickDependencyDropDown() {
    composeUiTest.onNode(hasTestTag("dependency_dropdown")).performClick()
  }

  fun selectDependencyDropDown(text: String) {
    composeUiTest.onAllNodes(hasText(text), useUnmergedTree = true)
      .filterToOne(hasTestTag("dependency_scenario"))
      .performClick()
  }

  fun capture(describedBehavior: DescribedBehavior<TestRobot>) {
    composeUiTest.onAllNodes(isRoot()).onLast().captureRoboImage("$describedBehavior.png")
  }

  fun setContent() {
    composeUiTest.setContent()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun ComposeUiTest.setContent() {
    val appStateHolder = ArbigentAppStateHolder(
      aiFactory = { fakeAi },
      deviceFactory = { fakeDevice },
      availableDeviceListFactory = {
        listOf(ArbigentAvailableDevice.Fake())
      },
    )
    setContent {
      AppTheme {
        Column {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Box(Modifier.padding(8.dp)) {
              ProjectFileControls(appStateHolder)
            }
            Box(Modifier.padding(8.dp)) {
              ScenarioControls(appStateHolder)
            }
          }
          App(
            appStateHolder = appStateHolder,
          )
        }
      }
    }
  }
}

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

  override fun viewTreeString(): ArbigentUiTreeStrings {
    arbigentDebugLog("FakeDevice.viewTreeString")
    return ArbigentUiTreeStrings(
      "viewTreeString",
      "optimizedTreeString"
    )
  }

  override fun elements(): ArbigentElementList {
    arbigentDebugLog("FakeDevice.elements")
    return ArbigentElementList(emptyList(), 1080)
  }

  override fun close() {
    arbigentDebugLog("FakeDevice.close")
  }
}

class FakeKeyStore : KeyStore {
  private val keys = mutableMapOf<String, String>()
  override fun getPassword(domain: String, account: String): String {
    return keys["$domain:$account"] ?: ""
  }

  override fun setPassword(domain: String, account: String, password: String) {
    keys["$domain:$account"] = password
  }

  override fun deletePassword(domain: String, account: String) {
    keys.remove("$domain:$account")
  }
}

internal class TestKeyStoreFactory : () -> KeyStore {
  override fun invoke(): KeyStore {
    return FakeKeyStore()
  }
}

class FakeAi : ArbigentAi {
  sealed interface AiStatus : ArbigentAi {
    class Normal() : AiStatus {
      private var count = 0
      private fun createDecisionOutput(
        agentCommand: ArbigentAgentCommand = ClickWithTextAgentCommand("text")
      ): ArbigentAi.DecisionOutput {
        return ArbigentAi.DecisionOutput(
          listOf(agentCommand),
          ArbigentContextHolder.Step(
            agentCommand = agentCommand,
            tought = "tought",
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

    class ImageAssertionFailed() : AiStatus {
      override fun decideAgentCommands(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
        return ArbigentAi.DecisionOutput(
          listOf(GoalAchievedAgentCommand()),
          ArbigentContextHolder.Step(
            agentCommand = GoalAchievedAgentCommand(),
            thought = "thought",
            screenshotFilePath = "screenshotFileName"
          )
        )
      }

      override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
        return ArbigentAi.ImageAssertionOutput(
          listOf(
            ArbigentAi.ImageAssertionResult(
              assertionPrompt = "assertionPrompt",
              isPassed = false,
              fulfillmentPercent = 0,
              explanation = "explanation"
            )
          )
        )
      }
    }
  }

  var status: AiStatus = AiStatus.Normal()
  override fun decideAgentCommands(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
    return status.decideAgentCommands(decisionInput)
  }

  override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
    return status.assertImage(imageAssertionInput)
  }
}
