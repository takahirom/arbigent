package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.github.takahirom.arbigent.result.ArbigentStepSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArbigentJevTest {
  private class FakeJevClient(private val answers: List<Pair<String, Double>>) : ArbigentJevClient {
    val requests = mutableListOf<JsonObject>()
    override suspend fun decide(request: JsonObject): JsonObject {
      requests += request
      val (choice, confidence) = answers[minOf(requests.size - 1, answers.lastIndex)]
      return buildJsonObject {
        putJsonObject("answers") {
          putJsonObject("next") {
            put("choice", choice)
            put("confidence", confidence)
          }
        }
      }
    }
  }

  private class CountingAi(private val delegate: FakeAi = FakeAi()) : ArbigentAi by delegate {
    var decisions = 0
    var imageAssertions = 0
    override fun decideAgentActions(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
      decisions++
      return delegate.decideAgentActions(decisionInput)
    }

    override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
      imageAssertions++
      return delegate.assertImage(imageAssertionInput)
    }
  }

  private fun agentConfig(
    ai: ArbigentAi,
    jev: ArbigentJevConfig?,
    imageAssertions: List<ArbigentImageAssertion> = emptyList(),
    cache: ArbigentAiDecisionCache = ArbigentAiDecisionCache.Disabled,
  ): AgentConfig = AgentConfigBuilder(
    prompt = ArbigentPrompt(),
    scenarioType = ArbigentScenarioType.Scenario,
    deviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
    initializationMethods = emptyList(),
    imageAssertions = ArbigentImageAssertions(imageAssertions),
    aiDecisionCache = cache,
    cacheOptions = ArbigentScenarioCacheOptions(),
  ).apply {
    deviceFactory { FakeDevice() }
    aiFactory { ai }
    jev(jev)
  }.build()

  private suspend fun TestScope.run(config: AgentConfig, maxStep: Int = 10): ArbigentAgent {
    val agent = ArbigentAgent(config, coroutineContext[CoroutineDispatcher]!!, replayTrace = null)
    agent.execute(ArbigentAgentTask("id", "Open 'Settings'", config, maxStep = maxStep), MCPClient())
    advanceUntilIdle()
    return agent
  }

  // Decisions only: the agent also records feedback steps, such as "the screen did not change".
  private fun ArbigentAgent.stepSources() = latestArbigentContext()!!.steps()
    .filter { it.agentAction != null }
    .map { it.stepSource }

  @Test
  fun confidentJevDecidesWithoutTheAi() = runTest {
    val ai = CountingAi()
    val client = FakeJevClient(listOf("click 1" to 0.95, "goal_achieved" to 0.97))
    val agent = run(agentConfig(ai, ArbigentJevConfig(ArbigentJevSettings(goalThreshold = 0.9), client)))

    assertTrue(agent.isGoalAchieved())
    assertEquals(0, ai.decisions)
    assertEquals(listOf(ArbigentStepSource.Jev, ArbigentStepSource.Jev), agent.stepSources())
  }

  @Test
  fun aGoalJevDecidesStillGoesThroughImageAssertions() = runTest {
    val ai = CountingAi()
    val client = FakeJevClient(listOf("goal_achieved" to 0.97))
    val agent = run(
      agentConfig(
        ai,
        ArbigentJevConfig(ArbigentJevSettings(goalThreshold = 0.9), client),
        imageAssertions = listOf(ArbigentImageAssertion("Settings is shown")),
      )
    )

    assertTrue(agent.isGoalAchieved())
    assertEquals(0, ai.decisions)
    assertEquals(1, ai.imageAssertions)
  }

  @Test
  fun lowConfidenceAndUnsetGoalThresholdGoToTheAi() = runTest {
    val ai = CountingAi()
    // FakeAi clicks twice and then achieves the goal.
    val client = FakeJevClient(listOf("click 1" to 0.5, "click 2" to 0.5, "goal_achieved" to 0.99))
    val agent = run(agentConfig(ai, ArbigentJevConfig(ArbigentJevSettings(), client)))

    assertTrue(agent.isGoalAchieved())
    assertEquals(3, ai.decisions)
    assertEquals(3, client.requests.size)
    assertEquals(List(3) { ArbigentStepSource.Ai }, agent.stepSources())
  }

  @Test
  fun shadowModeAsksJevButTheAiActs() = runTest {
    val ai = CountingAi()
    val client = FakeJevClient(listOf("click 1" to 0.99))
    val agent = run(agentConfig(ai, ArbigentJevConfig(ArbigentJevSettings(mode = ArbigentJevMode.Shadow), client)))

    assertEquals(3, ai.decisions)
    assertEquals(3, client.requests.size)
    assertFalse(ArbigentStepSource.Jev in agent.stepSources())
  }

  @Test
  fun aCacheHitNeverReachesJev() = runTest {
    val cache = AiDecisionCacheStrategy.InMemory().toCache()
    val firstClient = FakeJevClient(listOf("click 1" to 0.1))
    run(agentConfig(CountingAi(), ArbigentJevConfig(ArbigentJevSettings(), firstClient), cache = cache))
    assertEquals(3, firstClient.requests.size)

    val ai = CountingAi()
    val secondClient = FakeJevClient(listOf("click 1" to 0.99))
    val agent = run(agentConfig(ai, ArbigentJevConfig(ArbigentJevSettings(), secondClient), cache = cache))

    val sources = agent.stepSources()
    assertEquals(ArbigentStepSource.Cache, sources.first())
    // Jev is asked on exactly the steps the cache could not answer.
    assertEquals(sources.count { it != ArbigentStepSource.Cache }, secondClient.requests.size)
  }

  @Test
  fun jevStepsDoNotUseUpMaxStep() = runTest {
    val ai = CountingAi()
    val client = FakeJevClient(listOf("click 1" to 0.95, "click 2" to 0.95, "click 3" to 0.95, "goal_achieved" to 0.97))
    val agent = run(agentConfig(ai, ArbigentJevConfig(ArbigentJevSettings(goalThreshold = 0.9), client)), maxStep = 2)

    assertTrue(agent.isGoalAchieved())
    assertEquals(4, agent.stepSources().size)
  }

  @Test
  fun theSameJevActionOnTheSameScreenGoesToTheAi() = runTest {
    val ai = CountingAi()
    // FakeDevice's screen never changes, so a second "click 1" would be a loop.
    val client = FakeJevClient(listOf("click 1" to 0.95))
    val agent = run(agentConfig(ai, ArbigentJevConfig(ArbigentJevSettings(), client)))

    assertEquals(ArbigentStepSource.Jev, agent.stepSources().first())
    assertEquals(ArbigentStepSource.Ai, agent.stepSources()[1])
  }

  @Test
  fun aFailingJevFallsBackToTheAi() = runTest {
    val ai = CountingAi()
    val client = ArbigentJevClient { error("HTTP 500") }
    val agent = run(agentConfig(ai, ArbigentJevConfig(ArbigentJevSettings(), client)))

    assertTrue(agent.isGoalAchieved())
    assertEquals(3, ai.decisions)
  }

  @Test
  fun settingsResolveFromTheProjectAndOverrides() {
    val client = ArbigentJevClient { error("unused") }
    val project = ArbigentJevSettings(actionThreshold = 0.9, goalThreshold = 0.95)

    assertNull(ArbigentJevConfig.resolve(null, ArbigentJevOverrides(), client))
    assertNull(ArbigentJevConfig.resolve(project, ArbigentJevOverrides(), client = null))
    assertNull(ArbigentJevConfig.resolve(project, ArbigentJevOverrides(mode = ArbigentJevMode.Disabled), client))
    assertEquals(project, ArbigentJevConfig.resolve(project, ArbigentJevOverrides(), client)!!.settings)
    assertEquals(
      ArbigentJevSettings(mode = ArbigentJevMode.Shadow, actionThreshold = 0.7, goalThreshold = 0.95),
      ArbigentJevConfig.effectiveSettings(project, ArbigentJevOverrides(mode = ArbigentJevMode.Shadow, actionThreshold = 0.7)),
    )
    // Overrides alone turn Jev on for a project that doesn't configure it.
    assertEquals(
      ArbigentJevSettings(mode = ArbigentJevMode.Shadow),
      ArbigentJevConfig.effectiveSettings(null, ArbigentJevOverrides(mode = ArbigentJevMode.Shadow)),
    )
  }

  @Test
  fun projectSettingsRoundTripAndOmitJevWhenUnset() {
    val serializer = ArbigentProjectSerializer()
    val withoutJev = ArbigentProjectFileContent(scenarioContents = emptyList())
    assertFalse("jev" in serializer.encodeToString(withoutJev))

    val withJev = ArbigentProjectFileContent(
      scenarioContents = emptyList(),
      settings = ArbigentProjectSettings(jev = ArbigentJevSettings(actionThreshold = 0.9, goalThreshold = 0.95)),
    )
    val yaml = serializer.encodeToString(withJev)
    assertEquals(withJev.settings.jev, serializer.load(yaml).settings.jev)

    val handWritten = serializer.load(
      """
      settings:
        jev:
          mode: Shadow
      scenarios: []
      """.trimIndent()
    )
    assertEquals(ArbigentJevSettings(mode = ArbigentJevMode.Shadow), handWritten.settings.jev)
  }
}
