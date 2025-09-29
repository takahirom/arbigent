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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    // Use fixed timestamp for deterministic UI tests
    TimeProvider.set(TestTimeProvider(1234567890000L))
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
        }
        
        describe("MCP Environment Variables") {
          doIt {
            expandMcpSettings()
          }

          describe("when adding environment variables") {
            doIt {
              clickAddMcpEnvironmentVariableButton()
            }

            itShould("show new environment variable input fields") {
              assertMcpEnvironmentVariableInputExists(0)
              capture(it)
            }

            describe("when entering valid environment variable") {
              doIt {
                enterMcpEnvironmentVariable(0, "API_KEY", "test-api-key-123")
              }

              itShould("accept the input") {
                assertMcpEnvironmentVariableContains(0, "API_KEY", "test-api-key-123")
                capture(it)
              }
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
          describe("when change prompt template") {
            doIt {
              changePromptTemplate(
                """
                Task: {{USER_INPUT_GOAL}}

                Current step: {{CURRENT_STEP}}
                Total steps: {{MAX_STEP}}

                Progress:
                {{STEPS}}
              """.trimIndent()
              )
            }
            itShould("show updated template with dialog") {
              assertPromptTemplateContains("Task: {{USER_INPUT_GOAL}}")
              capture(it)
              closeProjectSettingsDialog()
            }
          }
          describe("when enter goals and image assertion") {
            doIt {
              enterGoal("launch the app")
              expandOptions()
              changeScenarioId("scenario1")
              enterImageAssertion("The screen should show the app")
            }
            describe("when run and finish the scenario") {
              doIt {
                clickRunButton()
                waitUntilScenarioRunning()
              }
              itShould("show goal achieved") {
                capture(it)
                assertGoalAchieved()
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
            itShould("display goal input with multiline text") {
              capture(it)
              assertGoalInputExists()
            }
          }

          describe("Generate Scenario Dialog") {
            doIt {
              clickGenerateScenarioButton()
            }

            itShould("show dialog with input fields") {
              capture(it)
              assertGenerateDialogExists()
            }

            describe("when entering scenario information") {
              val scenarioToGenerate = "Login to the app and check profile"
              val appUiStructure = """
                Main screen:
                - Login button
                - Register button

                Profile screen:
                - Username field
                - Email field
                - Settings button
              """.trimIndent()

              doIt {
                enterScenariosToGenerate(scenarioToGenerate)
                enterAppUiStructure(appUiStructure)
                toggleUseExistingScenarios()
              }

              itShould("show entered information") {
                capture(it)
                assertScenariosToGenerateContains(scenarioToGenerate)
                assertAppUiStructureContains("Main screen:")
              }

              describe("when generating scenarios") {
                doIt {
                  clickGenerateButton()
                  waitALittle() // Wait for generation to complete
                }

                itShould("create new scenarios") {
                  capture(it)
                  assertScenarioGenerated(scenarioToGenerate)
                }
              }
            }
          }

          describe("Project Settings") {
            describe("Additional System Prompt") {
              val multiLineText = """
                First line
                Second line
                Third line
              """.trimIndent()

              doIt {
                openProjectSettings()
              }

              itShould("handle multi-line input") {
                enterAdditionalSystemPrompt(multiLineText)
                assertAdditionalSystemPromptContains(multiLineText)
                capture(it)
                closeProjectSettingsDialog()
              }
            }

            describe("Image Detail Settings") {
              doIt {
                openProjectSettings()
              }

              describe("when enabling image detail") {
                doIt {
                  clickImageDetailCheckbox()
                }

                itShould("show enabled state with dropdown") {
                  assertImageDetailEnabled()
                  capture(it)
                }

                describe("when selecting detail levels") {
                  doIt {
                    openImageDetailLevelDropdown()
                  }

                  itShould("show both high and low options") {
                    assertImageDetailLevelExists("high")
                    assertImageDetailLevelExists("low")
                    capture(it)
                  }

                  describe("when changing detail levels") {
                    doIt {
                      clickImageDetailLevel("high")
                      clickImageDetailLevel("low")
                    }

                    itShould("apply the changes") {
                      capture(it)
                      closeProjectSettingsDialog()
                    }
                  }
                }
              }
            }

            describe("Scenario Cache Settings") {
              doIt {
                clickAddScenarioButton()
                enterGoal("Test cache settings")
                expandOptions()
              }

              itShould("have cache enabled by default") {
                assertScenarioCacheEnabled()
                capture(it)
              }

              describe("when toggling cache") {
                doIt {
                  toggleScenarioCache()
                }

                itShould("disable cache") {
                  assertScenarioCacheDisabled()
                  capture(it)
                }

                describe("when toggling again") {
                  doIt {
                    toggleScenarioCache()
                  }

                  itShould("enable cache") {
                    assertScenarioCacheEnabled()
                    capture(it)
                  }
                }
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
            itShould("execute cleanup and launch initialization methods three times") {
              capture(it)
              assertRunInitializeAndLaunchTwice()
            }
          }
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

  fun clickGenerateScenarioButton() {
    composeUiTest.onNode(hasContentDescription("Generate")).performClick()
    waitALittle()
  }

  fun enterScenariosToGenerate(scenarios: String) {
    composeUiTest.onNode(hasTestTag("scenarios_to_generate")).performTextInput(scenarios)
    waitALittle()
  }

  fun enterAppUiStructure(structure: String) {
    composeUiTest.onNode(hasTestTag("app_ui_structure")).performTextInput(structure)
    waitALittle()
  }

  fun toggleUseExistingScenarios() {
    composeUiTest.onNode(hasTestTag("use_existing_scenarios_checkbox")).performClick()
    waitALittle()
  }

  fun clickGenerateButton() {
    // Find the Generate button in the dialog - it's the first ActionButton in the dialog
    composeUiTest.onAllNodes(hasText("Generate"))
      .onFirst()
      .performClick()
    waitALittle()
  }

  fun assertScenarioGenerated(scenarioText: String) {
    composeUiTest.onNode(hasText("Goal: $scenarioText", substring = true)).assertExists()
  }

  fun assertGenerateDialogExists() {
    composeUiTest.onNode(hasTestTag("scenarios_to_generate")).assertExists()
    composeUiTest.onNode(hasTestTag("app_ui_structure")).assertExists()
    composeUiTest.onNode(hasTestTag("use_existing_scenarios_checkbox")).assertExists()
  }

  fun assertScenariosToGenerateContains(text: String) {
    composeUiTest.onNode(hasTestTag("scenarios_to_generate")).assertTextContains(text)
  }

  fun assertAppUiStructureContains(text: String) {
    // Get the text from the node and check if it contains the expected text
    val nodeText = composeUiTest.onNode(hasTestTag("app_ui_structure"))
      .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText]
    assert(nodeText.toString().contains(text)) { "Expected text to contain '$text', but was '$nodeText'" }
  }

  fun enterGoal(goal: String) {
    composeUiTest.onNode(hasTestTag("goal")).performTextInput(goal)
    waitALittle()
  }

  fun enterImageAssertion(assertion: String) {
    composeUiTest.onNode(hasTestTag("scenario_options"))
      .performScrollToNode(
        hasContentDescription("Add image assertion")
      )
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

  @OptIn(ExperimentalCoroutinesApi::class)
  fun waitALittle() {
    testScope.advanceUntilIdle()
  }

  fun assertGoalAchieved() {
    composeUiTest.onNode(hasTestTag("scenario_running")).assertDoesNotExist()
    composeUiTest.onNode(hasText("Goal achieved", true), useUnmergedTree = true).assertExists()
  }

  fun assertRunInitializeAndLaunchTwice() {
    val actions = fakeDevice.getActionHistory()
    val firstLaunch = actions.indexOfFirst {
      it.launchAppCommand != null
    }
    // The first action is failed so it runs twice
    assertEquals(3, actions.count { it.launchAppCommand != null })
    val firstCleanup = actions.indexOfFirst {
      it.clearStateCommand != null
    }
    assertEquals(3, actions.count { it.clearStateCommand != null })

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
    waitALittle()
    composeUiTest.waitUntil(timeoutMillis = 5000) {
      try {
        composeUiTest.onNode(hasTestTag("goal")).assertExists()
        true
      } catch (e: AssertionError) {
        false
      }
    }
  }

  fun expandOptions() {
    composeUiTest.onNode(hasContentDescription("Expand Options")).performClick()
    composeUiTest.waitUntilAtLeastOneExists(hasContentDescription("Collapse Options"))
    // To make the test deterministic
    changeScenarioId("default_scenario")
  }

  fun expandMcpSettings() {
    composeUiTest.onNode(hasContentDescription("Expand MCP Settings")).performClick()
    composeUiTest.waitUntilAtLeastOneExists(hasContentDescription("Collapse MCP Settings"))
  }

  fun collapseOptions() {
    // There is a case that we need to close other windows before closing the options
    fun isCollapsed() = try {
      composeUiTest.onNode(hasContentDescription("Collapse Options")).assertExists()
      false
    } catch (e: AssertionError) {
      true
    }
    while (!isCollapsed()) {
      composeUiTest.onNode(hasContentDescription("Collapse Options")).performClick()
    }
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

  fun toggleScenarioCache() {
    composeUiTest.onNode(hasText("Force disable Cache for this scenario")).performClick()
  }

  fun assertScenarioCacheEnabled() {
    composeUiTest.onNode(hasText("Force disable Cache for this scenario")).assertIsToggleable()
      .assertIsOff()
  }

  fun assertScenarioCacheDisabled() {
    composeUiTest.onNode(hasText("Force disable Cache for this scenario")).assertIsToggleable()
      .assertIsOn()
  }

  fun changePromptTemplate(template: String) {
    composeUiTest.onNode(hasContentDescription("Project Settings")).performClick()
    composeUiTest.waitUntilAtLeastOneExists(hasText("User Prompt Template"), timeoutMillis = 1000)
    composeUiTest.onNode(hasTestTag("user_prompt_template"))
      .performTextClearance()
    composeUiTest.onNode(hasTestTag("user_prompt_template"))
      .performTextInput(template)
  }

  fun assertPromptTemplateContains(expectedText: String) {
    composeUiTest.onNode(hasTestTag("user_prompt_template"))
      .assertTextContains(expectedText, substring = true)
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

  fun debugCapture() {
    composeUiTest.onAllNodes(isRoot()).onLast().captureRoboImage("debug.png")
  }

  fun capture(describedBehavior: DescribedBehavior<TestRobot>) {
    composeUiTest.onAllNodes(isRoot()).onLast().captureRoboImage("$describedBehavior.png")
  }

  fun setContent() {
    // Add a default AI provider to make the "Connect to device" button enabled
    addDefaultAiProvider()
    composeUiTest.setContent()
  }

  fun addDefaultAiProvider() {
    // Create a default OpenAI provider
    val openAiProvider = AiProviderSetting.OpenAi(
      id = "testOpenAi",
      apiKey = "test-api-key",
      modelName = OpenAIAi.DEFAULT_OPENAI_MODEL
    )

    // Update the AI settings with the new provider and select it
    val currentSettings = Preference.aiSettingValue
    Preference.aiSettingValue = currentSettings.copy(
      selectedId = openAiProvider.id,
      aiSettings = listOf(openAiProvider),
    )
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
    composeUiTest.onNode(hasTestTag("scenario_options"))
      .performScrollToNode(
        hasContentDescription("Add initialization method")
      )
    composeUiTest.onNode(hasContentDescription("Add initialization method")).performClick()
    waitALittle()
    composeUiTest.onNode(hasTestTag("scenario_options"))
      .performScrollToNode(
        hasTestTag("initialization_method")
      )
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

  fun closeProjectSettingsDialog() {
    composeUiTest.onNode(hasText("Close")).performClick()
    composeUiTest.onNode(hasTestTag("project_settings_dialog")).assertDoesNotExist()
  }

  fun openProjectSettings() {
    composeUiTest.onNode(hasTestTag("settings_button")).performClick()
  }

  fun enterAdditionalSystemPrompt(text: String) {
    composeUiTest.onNode(hasTestTag("additional_system_prompt")).performTextInput(text)
  }

  fun assertAdditionalSystemPromptContains(expectedText: String) {
    composeUiTest.onNode(hasTestTag("additional_system_prompt"))
      .assertTextContains(expectedText)
  }

  fun assertImageDetailCheckboxExists() {
    composeUiTest
      .onNode(hasTestTag("image_detail_checkbox"))
      .assertExists()
      .assertIsToggleable()
  }

  fun assertImageDetailCheckboxIsOff() {
    composeUiTest
      .onNode(hasTestTag("image_detail_checkbox"))
      .assertIsOff()
  }

  fun assertImageDetailCheckboxIsOn() {
    composeUiTest
      .onNode(hasTestTag("image_detail_checkbox"))
      .assertIsOn()
  }

  fun clickImageDetailCheckbox() {
    composeUiTest
      .onNode(hasTestTag("image_detail_checkbox"))
      .performClick()
    waitALittle()
  }

  fun openImageDetailLevelDropdown() {
    composeUiTest
      .onNode(hasTestTag("image_detail_level_combo"))
      .assertExists()
      .performClick()
    waitALittle()
  }

  private fun waitForNode(matcher: SemanticsMatcher) {
    composeUiTest.waitUntil(timeoutMillis = 2000) {
      try {
        composeUiTest.onNode(matcher, useUnmergedTree = true).assertExists()
        true
      } catch (e: AssertionError) {
        false
      }
    }
  }

  private fun verifyDropdownIsOpen() {
    waitForNode(hasTestTag("image_detail_level_item_high"))
  }

  private fun ensureDropdownIsOpen() {
    try {
      verifyDropdownIsOpen()
    } catch (e: AssertionError) {
      openImageDetailLevelDropdown()
      verifyDropdownIsOpen()
    }
  }

  fun assertImageDetailLevelExists(level: String) {
    ensureDropdownIsOpen()
    waitForNode(hasTestTag("image_detail_level_item_${level.lowercase()}"))
  }

  fun clickImageDetailLevel(level: String) {
    val levelLower = level.lowercase()
    ensureDropdownIsOpen()
    waitForNode(hasTestTag("image_detail_level_item_$levelLower"))

    composeUiTest
      .onNode(hasTestTag("image_detail_level_item_$levelLower"), useUnmergedTree = true)
      .performClick()
    waitALittle()

    waitForNode(hasTestTag("image_detail_level_combo"))
    composeUiTest
      .onNode(hasTestTag("image_detail_level_combo"))
      .assertTextContains(levelLower)
  }

  fun assertImageDetailEnabled() {
    assertImageDetailCheckboxIsOn()
    composeUiTest
      .onNode(hasTestTag("image_detail_level_combo"))
      .assertExists()
  }

  fun clickAddMcpEnvironmentVariableButton() {
    composeUiTest
      .onNode(hasTestTag("add_mcp_environment_variable"))
      .performClick()
    waitALittle()
  }

  fun assertMcpEnvironmentVariableInputExists(index: Int) {
    waitForNode(hasTestTag("mcp_environment_variable_key_$index"))
    composeUiTest
      .onNode(hasTestTag("mcp_environment_variable_key_$index"))
      .assertExists()
    composeUiTest
      .onNode(hasTestTag("mcp_environment_variable_value_$index"))
      .assertExists()
  }

  fun enterMcpEnvironmentVariable(index: Int, key: String, value: String) {
    composeUiTest
      .onNode(hasTestTag("mcp_environment_variable_key_$index"))
      .performTextClearance()
    composeUiTest
      .onNode(hasTestTag("mcp_environment_variable_key_$index"))
      .performTextInput(key)
    waitALittle()
    
    composeUiTest
      .onNode(hasTestTag("mcp_environment_variable_value_$index"))
      .performTextClearance()
    composeUiTest
      .onNode(hasTestTag("mcp_environment_variable_value_$index"))
      .performTextInput(value)
    waitALittle()
  }

  fun assertMcpEnvironmentVariableContains(index: Int, key: String, value: String) {
    composeUiTest
      .onNode(hasTestTag("mcp_environment_variable_key_$index"))
      .assertTextContains(key)
    composeUiTest
      .onNode(hasTestTag("mcp_environment_variable_value_$index"))
      .assertTextContains(value)
  }

  fun clickRemoveMcpEnvironmentVariableButton(index: Int) {
    composeUiTest
      .onNode(hasTestTag("remove_mcp_environment_variable_$index"))
      .performClick()
    waitALittle()
  }

  fun assertMcpEnvironmentVariableCount(expectedCount: Int) {
    for (i in 0 until expectedCount) {
      composeUiTest
        .onNode(hasTestTag("mcp_environment_variable_key_$i"))
        .assertExists()
    }
    composeUiTest
      .onNode(hasTestTag("mcp_environment_variable_key_$expectedCount"))
      .assertDoesNotExist()
  }

  fun assertMcpEnvironmentVariableError(index: Int, errorMessage: String) {
    composeUiTest
      .onNode(hasTestTag("mcp_environment_variable_error_$index"))
      .assertTextContains(errorMessage)
  }

  fun assertImageDetailDisabled() {
    assertImageDetailCheckboxIsOff()
    composeUiTest
      .onNode(hasTestTag("image_detail_level_combo"))
      .assertDoesNotExist()
  }

}

class FakeDevice : ArbigentDevice {
  private val actionHistory = mutableListOf<MaestroCommand>()
  override fun executeActions(actions: List<MaestroCommand>) {
    actions.forEach { command ->
      val screenshotCommand = command.takeScreenshotCommand
      if (screenshotCommand != null) {
        // Construct the correct path in the screenshots directory
        val screenshotFile = java.io.File(ArbigentFiles.screenshotsDir, "${screenshotCommand.path}.png")
        screenshotFile.parentFile?.mkdirs()
        // Create a simple image file to simulate screenshot
        val image = java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = java.awt.Color.WHITE
        graphics.fillRect(0, 0, 100, 100)
        graphics.dispose()
        javax.imageio.ImageIO.write(image, "png", screenshotFile)
      }
    }
    actionHistory.addAll(actions)
  }

  fun getActionHistory(): List<MaestroCommand> {
    return actionHistory
  }

  override fun os(): ArbigentDeviceOs {
    return ArbigentDeviceOs.Android
  }

  override fun waitForAppToSettle(appId: String?) {
  }

  override fun focusedTreeString(): String {
    return "focusedTreeString"
  }

  override fun viewTreeString(): ArbigentUiTreeStrings {
    return ArbigentUiTreeStrings(
      "viewTreeString",
      "optimizedTreeString"
    )
  }

  override fun elements(): ArbigentElementList {
    return ArbigentElementList(emptyList(), 1080)
  }

  private var isClosed = false
  override fun close() {
    isClosed = true
  }

  override fun isClosed(): Boolean {
    return isClosed
  }

}

class FakeKeyStore(
  private val keys:MutableMap<String, String>
) : KeyStore {
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
  val keys = mutableMapOf<String, String>()
  override fun invoke(): KeyStore {
    return FakeKeyStore(keys)
  }
}

class FakeAi : ArbigentAi {
  sealed interface AiStatus : ArbigentAi {
    class Normal() : AiStatus {
      private var decisionCount = 0
      var imageAssertionCount = 0
        private set

      private fun createDecisionOutput(
        agentAction: ArbigentAgentAction = ClickWithTextAgentAction("text"),
        decisionInput: ArbigentAi.DecisionInput
      ): ArbigentAi.DecisionOutput {
        return ArbigentAi.DecisionOutput(
          listOf(agentAction),
          ArbigentContextHolder.Step(
            stepId = "stepId1",
            agentAction = agentAction,
            memo = "memo",
            screenshotFilePath = "screenshotFileName",
            uiTreeStrings = decisionInput.uiTreeStrings,
            cacheKey = decisionInput.cacheKey
          )
        )
      }

      override fun decideAgentActions(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
        if (decisionCount < 10) {
          decisionCount++
          return createDecisionOutput(
            decisionInput = decisionInput,
          )
        } else if (decisionCount == 10) {
          decisionCount++
          return createDecisionOutput(
            decisionInput = decisionInput,
            agentAction = FailedAgentAction()
          )
        } else {
          return createDecisionOutput(
            agentAction = GoalAchievedAgentAction(),
            decisionInput = decisionInput,
          )
        }
      }

      override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
        imageAssertionCount++
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

      override fun generateScenarios(
        scenarioGenerationInput: ArbigentAi.ScenarioGenerationInput
      ): GeneratedScenariosContent {
        val scenarioContent = ArbigentScenarioContent(
          goal = scenarioGenerationInput.scenariosToGenerate,
          type = ArbigentScenarioType.Scenario
        )
        return GeneratedScenariosContent(
          scenarios = listOf(scenarioContent)
        )
      }
    }

    class ImageAssertionFailed() : AiStatus {
      override fun decideAgentActions(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
        return ArbigentAi.DecisionOutput(
          listOf(GoalAchievedAgentAction()),
          ArbigentContextHolder.Step(
            stepId = "stepId1",
            agentAction = GoalAchievedAgentAction(),
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

      override fun generateScenarios(
        scenarioGenerationInput: ArbigentAi.ScenarioGenerationInput
      ): GeneratedScenariosContent {
        val scenarioContent = ArbigentScenarioContent(
          goal = scenarioGenerationInput.scenariosToGenerate,
          type = ArbigentScenarioType.Scenario
        )
        return GeneratedScenariosContent(
          scenarios = listOf(scenarioContent)
        )
      }
    }
  }

  var status: AiStatus = AiStatus.Normal()
  override fun decideAgentActions(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
    return status.decideAgentActions(decisionInput)
  }

  override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
    return status.assertImage(imageAssertionInput)
  }

  override fun generateScenarios(
    scenarioGenerationInput: ArbigentAi.ScenarioGenerationInput
  ): GeneratedScenariosContent {
    val scenarioContent = ArbigentScenarioContent(
      goal = scenarioGenerationInput.scenariosToGenerate,
      type = ArbigentScenarioType.Scenario
    )
    return GeneratedScenariosContent(
      scenarios = listOf(scenarioContent)
    )
  }
}
