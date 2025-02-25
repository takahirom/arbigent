package io.github.takahirom.arbigent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
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
import kotlin.test.assertEquals

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
              enableCache()
            }
          }
        }
        describe("when add scenario") {
          doIt {
            clickConnectToDeviceButton()
            enableCache()
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
              changeScenarioId("scenario1")
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
          describe("when enter multiline goal") {
            doIt {
              enterGoal("First line of the goal\nSecond line of the goal\nThird line")
              expandOptions()
              changeScenarioId("multiline_scenario")
            }
            itShould("show multiline goal input properly") {
              capture(it)
              assertGoalInputExists()
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
              itShould("not run imageAssertion") {
                capture(it)
                assertDontRunImageAssertion()
              }
            }
          }
          describe("when add multiple methods and run") {
            doIt {
              enterGoal("launch the app")
              expandOptions()
              changeScenarioId("scenario1")
              addCleanupDataInitializationMethod()
              addLaunchAppInitializationMethod()
              clickRunButton()
            }
            itShould("run methods correctly") {
              capture(it)
              assertRunInitializeAndLaunchTwice()
            }
          }
          describeEnterDependencyGoal(
            firstGoal = "g1",
            secondGoal = "g2"
          )
          // Same goal
          describeEnterDependencyGoal(
            firstGoal = "g1",
            secondGoal = "g1"
          )
        }
      }
    }

    private fun BehaviorsTreeBuilder<TestRobot>.describeEnterDependencyGoal(
      firstGoal: String,
      secondGoal: String,
    ) {
      describe("when add scenarios $secondGoal") {
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
            changeScenarioId("scenario1")
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
        describe("when add dependency and change dependency id and run") {
          doIt {
            expandOptions()
            clickDependencyDropDown()
            selectDependencyDropDown(firstGoal)
            openScenario(firstGoal)
            changeScenarioId("newId")
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

  fun assertRunInitializeAndLaunchTwice() {
    val commands = fakeDevice.getCommandHistory()
    val firstLaunch = commands.indexOfFirst {
      it.launchAppCommand != null
    }
    // The first command is failed so it runs twice
    assertEquals(3, commands.count { it.launchAppCommand != null })
    val firstCleanup = commands.indexOfFirst {
      it.clearStateCommand != null
    }
    assertEquals(3, commands.count { it.clearStateCommand != null })

    assert(firstCleanup < firstLaunch)
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

  fun enableCache() {
    composeUiTest.onNode(hasContentDescription("Project Settings")).performClick()
    composeUiTest.waitUntilAtLeastOneExists(hasText("Disabled"), timeoutMillis = 1000)
    composeUiTest.onNode(hasText("Disabled")).performClick()
    composeUiTest.waitUntilAtLeastOneExists(hasText("InMemory"), timeoutMillis = 1000)
    composeUiTest.onNode(hasText("InMemory"), useUnmergedTree = true).performClick()
    composeUiTest.onNode(hasText("Close")).performClick()
  }

  fun selectDependencyDropDown(text: String) {
    composeUiTest.onAllNodes(hasText(text), useUnmergedTree = true)
      .filterToOne(hasTestTag("dependency_scenario"))
      .performClick()
  }

  fun openScenario(goal: String) {
    composeUiTest.onAllNodes(hasText(goal), useUnmergedTree = true)
      .onFirst()
      .performClick()
  }

  fun changeScenarioId(id: String) {
    composeUiTest.onNode(hasTestTag("scenario_id"))
      .performTextClearance()
    composeUiTest.onNode(hasTestTag("scenario_id"))
      .performTextInput(id)
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
      CompositionLocalProvider(
        LocalIsUiTest provides true,
      ) {
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

  fun addCleanupDataInitializationMethod() {
    composeUiTest.onNode(hasContentDescription("Add initialization method")).performClick()
    waitALittle()
    composeUiTest.onAllNodes(hasText(InitializationMethodMenu.Noop.type), useUnmergedTree = true)
      .onFirst()
      .performClick()
    composeUiTest.onAllNodes(
      hasText(InitializationMethodMenu.CleanupData.type),
      useUnmergedTree = true
    )
      .onFirst()
      .performClick()
    composeUiTest.onNode(hasTestTag("cleanup_pacakge"))
      .performTextInput("com.example")
  }

  fun addLaunchAppInitializationMethod() {
    composeUiTest.onNode(hasContentDescription("Add initialization method")).performClick()
    waitALittle()
    composeUiTest.onAllNodes(hasText(InitializationMethodMenu.Noop.type), useUnmergedTree = true)
      .onFirst()
      .performClick()
    composeUiTest.onAllNodes(
      hasText(InitializationMethodMenu.LaunchApp.type),
      useUnmergedTree = true
    )
      .onFirst()
      .performClick()
    composeUiTest.onNode(hasTestTag("launch_app_package"))
      .performTextInput("com.example")
  }

  fun assertDontRunImageAssertion() {
    assertEquals(0, (fakeAi.status as FakeAi.AiStatus.Normal).imageAssertionCount)
  }
}

class FakeDevice : ArbigentDevice {
  private val commandHistory = mutableListOf<MaestroCommand>()
  override fun executeCommands(commands: List<MaestroCommand>) {
    arbigentDebugLog("FakeDevice.executeCommands: $commands")
    commandHistory.addAll(commands)
  }

  fun getCommandHistory(): List<MaestroCommand> {
    return commandHistory
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

  private var isClosed = false
  override fun close() {
    arbigentDebugLog("FakeDevice.close")
  }

  override fun isClosed(): Boolean {
    arbigentDebugLog("FakeDevice.isClosed")
    return isClosed
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
      private var decisionCount = 0
      var imageAssertionCount = 0
        private set

      private fun createDecisionOutput(
        agentCommand: ArbigentAgentCommand = ClickWithTextAgentCommand("text"),
        decisionInput: ArbigentAi.DecisionInput
      ): ArbigentAi.DecisionOutput {
        return ArbigentAi.DecisionOutput(
          listOf(agentCommand),
          ArbigentContextHolder.Step(
            stepId = "stepId1",
            agentCommand = agentCommand,
            memo = "memo",
            screenshotFilePath = "screenshotFileName",
            uiTreeStrings = decisionInput.uiTreeStrings,
            cacheKey = decisionInput.cacheKey
          )
        )
      }

      override fun decideAgentCommands(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
        arbigentDebugLog("FakeAi.decideWhatToDo")
        if (decisionCount < 10) {
          decisionCount++
          return createDecisionOutput(
            decisionInput = decisionInput,
          )
        } else if (decisionCount == 10) {
          decisionCount++
          return createDecisionOutput(
            decisionInput = decisionInput,
            agentCommand = FailedAgentCommand()
          )
        } else {
          return createDecisionOutput(
            agentCommand = GoalAchievedAgentCommand(),
            decisionInput = decisionInput,
          )
        }
      }

      override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
        imageAssertionCount++
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
            stepId = "stepId1",
            agentCommand = GoalAchievedAgentCommand(),
            memo = "memo",
            screenshotFilePath = "screenshotFileName",
            cacheKey = decisionInput.cacheKey
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
