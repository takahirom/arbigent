package com.github.takahirom.arbiter.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.github.takahirom.arbiter.AgentCommand
import com.github.takahirom.arbiter.Ai
import com.github.takahirom.arbiter.ArbiterContextHolder
import com.github.takahirom.arbiter.ClickWithTextAgentCommand
import com.github.takahirom.arbiter.Device
import com.github.takahirom.arbiter.GoalAchievedAgentCommand
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.takahirom.robospec.DescribedBehavior
import io.github.takahirom.robospec.DescribedBehaviors
import io.github.takahirom.robospec.describeBehaviors
import io.github.takahirom.robospec.execute
import kotlinx.coroutines.test.runTest
import maestro.orchestra.MaestroCommand
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

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

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class Tests(val behavior: DescribedBehavior<ComposeUiTest>) {
  @Test
  fun test() {
    runComposeUiTest {
      setContent()
      runTest {
        behavior.execute(this@runComposeUiTest)
      }
    }
  }

  @OptIn(ExperimentalTestApi::class)
  private fun ComposeUiTest.setContent() {
    setContent {
      App(
        appStateHolder = AppStateHolder(
          aiFacotry = { FakeAi() },
          deviceFactory = { FakeDevice() },
        ),
      )
    }
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun data(): DescribedBehaviors<ComposeUiTest> {
      return describeBehaviors<ComposeUiTest>("Tests") {
        describe("when the user opens the app") {
          itShould("have a Connect to device button") {
            onNode(hasText("Connect to device")).assertExists()
            capture(it)
          }
          describe("when the user clicks the Connect to device button") {
            doIt {
              onNode(hasText("Connect to device")).performClick()
            }
            itShould("show the Add scenario button") {
              onNode(hasText("Add scenario")).assertExists()
              capture(it)
            }
            describe("when the user clicks the Add scenario button") {
              doIt {
                onNode(hasText("Add scenario")).performClick()
              }
              itShould("show the Add scenario button") {
                capture(it)
                onNode(hasTestTag("goal")).assertExists()
              }
              describe("when enter goals and run") {
                doIt {
                  onNode(hasTestTag("goal")).performTextInput("launch the app")
                  onNode(hasText("Run")).performClick()
                }
                itShould("show the Add scenario button") {
                  capture(it)
                  onNode(hasTestTag("scenario_running")).assertExists()
                }
                describe("should finish the scenario") {
                  doIt {
                    waitUntil(
                      timeoutMillis = 5000
                    ) {
                      try {
                        onNode(hasTestTag("scenario_running")).assertExists()
                        false
                      } catch (e: AssertionError) {
                        true
                      }
                    }
                  }
                  itShould("show goal achieved") {
                    capture(it)
                    onNode(hasTestTag("scenario_running")).assertDoesNotExist()
                    onNode(hasText("GoalAchieved", true)).assertExists()
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
