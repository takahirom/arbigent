package com.github.takahirom.arbiter.ui

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
import com.github.takahirom.arbiter.AgentCommand
import com.github.takahirom.arbiter.Ai
import com.github.takahirom.arbiter.ArbiterContextHolder
import com.github.takahirom.arbiter.ArbiterCorotuinesDispatcher
import com.github.takahirom.arbiter.ClickWithTextAgentCommand
import com.github.takahirom.arbiter.Device
import com.github.takahirom.arbiter.GoalAchievedAgentCommand
import io.github.takahirom.roborazzi.captureRoboImage
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
    ArbiterCorotuinesDispatcher.dispatcher = testDispatcher
    runComposeUiTest {
      setContent()
      runTest(testDispatcher) {
        behavior.execute(TestRobot(this, this@runComposeUiTest))
      }
    }
  }

  @OptIn(ExperimentalTestApi::class)
  private fun ComposeUiTest.setContent() {
    val appStateHolder = AppStateHolder(
      aiFactory = { FakeAi() },
      deviceFactory = { FakeDevice() },
    )
    setContent {
      AppTheme {
        Column {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Box(Modifier.padding(8.dp)) {
              ScenarioFileControls(appStateHolder)
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
            assertConnectToDeviceButtonExists()
            capture(it)
          }
          describe("when clicks the Connect to device") {
            doIt {
              clickConnectToDeviceButton()
            }
            itShould("show the Add scenario") {
              assertAddScenarioButtonExists()
              capture(it)
            }
            describe("when clicks the Add scenario") {
              doIt {
                clickAddScenarioButton()
              }
              itShould("show goal input") {
                capture(it)
                assertGoalInputExists()
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
              describe("when add scenarios") {
                doIt {
                  enterGoal("launch the app")
                  clickAddScenarioButton()
                  enterGoal("launch the app2")
                }
                describe("when run all") {
                  doIt {
                    clickRunAllButton()
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
                describe("when add dependency and run") {
                  doIt {
                    clickDependencyTextField()
                    typeDependencyTextField("launch the app")
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
  val testScope: TestScope,
  val composeUiTest: ComposeUiTest,
) {

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
        timeoutMillis = 5000
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
    composeUiTest.onNode(hasText("GoalAchieved", true), useUnmergedTree = true).assertExists()
  }

  fun assertTwoGoalAchieved() {
    composeUiTest.onNode(hasTestTag("scenario_running")).assertDoesNotExist()
    composeUiTest.onAllNodes(hasText("GoalAchieved", true), useUnmergedTree = true)
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


  fun clickDependencyTextField() {
    composeUiTest.onNode(hasTestTag("dependency_dropdown")).performClick()
  }

  fun typeDependencyTextField(text: String) {
    composeUiTest.onNode(hasText(text)).performClick()
  }

  fun capture(describedBehavior: DescribedBehavior<TestRobot>) {
    composeUiTest.onAllNodes(isRoot()).onLast().captureRoboImage("$describedBehavior.png")
  }
}

class FakeDevice : Device {
  override fun executeCommands(commands: List<MaestroCommand>) {
    println("FakeDevice.executeCommands: $commands")
  }

  override fun viewTreeString(): String {
    println("FakeDevice.viewTreeString")
    return "viewTreeString"
  }
}

class FakeAi : Ai {
  var count = 0
  fun createDecisionOutput(
    agentCommand: AgentCommand = ClickWithTextAgentCommand("text")
  ): Ai.DecisionOutput {
    return Ai.DecisionOutput(
      listOf(agentCommand),
      ArbiterContextHolder.Turn(
        agentCommand = agentCommand,
        memo = "memo",
        screenshotFileName = "screenshotFileName"
      )
    )
  }

  override fun decideWhatToDo(decisionInput: Ai.DecisionInput): Ai.DecisionOutput {
    println("FakeAi.decideWhatToDo")
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
