package io.github.takahirom.arbigent.ui

import io.github.takahirom.arbigent.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdditionalActionsTest {

    @Before
    fun setup() {
        globalKeyStoreFactory = TestKeyStoreFactory()
    }

    @After
    fun tearDown() {
        // Clean up any test data
    }

    @Test
    fun `ArbigentAppStateHolder onAdditionalActionsChanged should update flow`() = runBlocking {
        val appStateHolder = ArbigentAppStateHolder(
            aiFactory = { FakeAi() }
        )
        val testActions = listOf("ClickWithText", "ClickWithId")

        appStateHolder.onAdditionalActionsChanged(testActions)

        assertEquals(testActions, appStateHolder.additionalActionsFlow.value)
    }

    @Test
    fun `ArbigentAppStateHolder onAdditionalActionsChanged should handle null`() = runBlocking {
        val appStateHolder = ArbigentAppStateHolder(
            aiFactory = { FakeAi() }
        )

        // First set some actions
        appStateHolder.onAdditionalActionsChanged(listOf("ClickWithText"))
        assertEquals(listOf("ClickWithText"), appStateHolder.additionalActionsFlow.value)

        // Then clear them
        appStateHolder.onAdditionalActionsChanged(null)
        assertNull(appStateHolder.additionalActionsFlow.value)
    }

    @Test
    fun `ArbigentAppStateHolder onAdditionalActionsChanged should handle empty list`() = runBlocking {
        val appStateHolder = ArbigentAppStateHolder(
            aiFactory = { FakeAi() }
        )

        // Set empty list should be treated as null in UI (checkbox deselection logic)
        appStateHolder.onAdditionalActionsChanged(emptyList())
        assertEquals(emptyList<String>(), appStateHolder.additionalActionsFlow.value)
    }

    @Test
    fun `ArbigentScenarioStateHolder onAdditionalActionsChanged should update flow`() {
        val tagManager = ArbigentTagManager()
        val scenarioStateHolder = ArbigentScenarioStateHolder(tagManager = tagManager)
        val testActions = listOf("ClickWithText", "DpadTryAutoFocusById")

        scenarioStateHolder.onAdditionalActionsChanged(testActions)

        assertEquals(testActions, scenarioStateHolder.additionalActionsFlow.value)
    }

    @Test
    fun `ArbigentScenarioStateHolder onAdditionalActionsChanged should handle null`() {
        val tagManager = ArbigentTagManager()
        val scenarioStateHolder = ArbigentScenarioStateHolder(tagManager = tagManager)

        // First set some actions
        scenarioStateHolder.onAdditionalActionsChanged(listOf("ClickWithId"))
        assertEquals(listOf("ClickWithId"), scenarioStateHolder.additionalActionsFlow.value)

        // Then clear them
        scenarioStateHolder.onAdditionalActionsChanged(null)
        assertNull(scenarioStateHolder.additionalActionsFlow.value)
    }

    @Test
    fun `ArbigentScenarioStateHolder createArbigentScenarioContent should include additionalActions`() {
        val tagManager = ArbigentTagManager()
        val scenarioStateHolder = ArbigentScenarioStateHolder(tagManager = tagManager)
        val testActions = listOf("ClickWithText", "ClickWithId")

        scenarioStateHolder.onAdditionalActionsChanged(testActions)
        scenarioStateHolder.onGoalChanged("Test goal")

        val scenarioContent = scenarioStateHolder.createArbigentScenarioContent()

        assertEquals(testActions, scenarioContent.additionalActions)
    }

    @Test
    fun `ArbigentScenarioStateHolder load should restore additionalActions`() {
        val tagManager = ArbigentTagManager()
        val scenarioStateHolder = ArbigentScenarioStateHolder(tagManager = tagManager)
        val testActions = listOf("DpadTryAutoFocusByText")

        val scenarioContent = ArbigentScenarioContent(
            id = "test-id",
            goal = "Test goal",
            type = ArbigentScenarioType.Scenario,
            dependencyId = null,
            initializationMethods = emptyList(),
            noteForHumans = "",
            maxRetry = 3,
            maxStep = 10,
            tags = emptySet(),
            deviceFormFactor = io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor.Mobile,
            cleanupData = ArbigentScenarioContent.CleanupData.Noop,
            imageAssertionHistoryCount = 1,
            imageAssertions = emptyList(),
            userPromptTemplate = "",
            aiOptions = null,
            cacheOptions = null,
            additionalActions = testActions
        )

        scenarioStateHolder.load(scenarioContent)

        assertEquals(testActions, scenarioStateHolder.additionalActionsFlow.value)
    }

    @Test
    fun `AdditionalActionsConstants AVAILABLE_ACTIONS should contain all supported actions`() {
        val expectedActions = listOf(
            "ClickWithText",
            "ClickWithId",
            "DpadTryAutoFocusById",
            "DpadTryAutoFocusByText",
            "DpadTryAutoFocusByIndex"
        )

        assertEquals(expectedActions, AdditionalActionsConstants.AVAILABLE_ACTIONS)
    }
}
