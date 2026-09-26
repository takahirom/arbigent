package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.github.takahirom.arbigent.result.ArbigentStepSource
import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

  private class CountingAi(
    private val delegate: FakeAi = FakeAi(),
    // One per image assertion call; passes once these run out.
    private val imageAssertionPasses: List<Boolean> = emptyList(),
  ) : ArbigentAi by delegate {
    var decisions = 0
    var imageAssertions = 0
    override fun decideAgentActions(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput {
      decisions++
      return delegate.decideAgentActions(decisionInput)
    }

    override fun assertImage(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput {
      val passes = imageAssertionPasses.getOrElse(imageAssertions++) { true }
      val output = delegate.assertImage(imageAssertionInput)
      return output.copy(results = output.results.map { ArbigentAi.ImageAssertionResult(it.assertionPrompt, passes, it.fulfillmentPercent, it.explanation) })
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
  fun aGoalTheImageAssertionRejectsDoesNotUseUpMaxStep() = runTest {
    val ai = CountingAi(imageAssertionPasses = listOf(false))
    val client = FakeJevClient(listOf("goal_achieved" to 0.97, "click 1" to 0.95, "goal_achieved" to 0.97))
    val agent = run(
      agentConfig(
        ai,
        ArbigentJevConfig(ArbigentJevSettings(goalThreshold = 0.9), client),
        imageAssertions = listOf(ArbigentImageAssertion("Settings is shown")),
      ),
      maxStep = 1,
    )

    assertTrue(agent.isGoalAchieved())
    assertEquals(0, ai.decisions)
  }

  @Test
  fun aJevDecisionReplayedFromTheCacheDoesNotUseUpMaxStep() = runTest {
    val cache = AiDecisionCacheStrategy.InMemory().toCache()
    val jevOnly = FakeJevClient(listOf("click 1" to 0.95, "click 2" to 0.95, "goal_achieved" to 0.97))
    val settings = ArbigentJevSettings(goalThreshold = 0.9)
    // The step limit is part of the cache key, so both runs use the same maxStep.
    assertTrue(run(agentConfig(CountingAi(), ArbigentJevConfig(settings, jevOnly), cache = cache), maxStep = 1).isGoalAchieved())

    val ai = CountingAi()
    val unsure = FakeJevClient(listOf("click 1" to 0.1))
    val agent = run(agentConfig(ai, ArbigentJevConfig(settings, unsure), cache = cache), maxStep = 1)

    assertTrue(agent.isGoalAchieved())
    assertEquals(List(3) { ArbigentStepSource.Cache }, agent.stepSources())
    assertEquals(0, ai.decisions)
  }

  @Test
  fun anAnswerThatIsNotAnOfferedOptionGoesToTheAi() = runTest {
    val ai = CountingAi()
    val client = FakeJevClient(listOf("bogus" to 0.99, "click 999" to 0.99, "focus 1" to 0.99))
    val agent = run(agentConfig(ai, ArbigentJevConfig(ArbigentJevSettings(), client)))

    assertTrue(agent.isGoalAchieved())
    assertEquals(3, ai.decisions)
    assertEquals(List(3) { ArbigentStepSource.Ai }, agent.stepSources())
  }

  @Test
  fun aDisabledConfigNeverAsksJev() = runTest {
    val client = FakeJevClient(listOf("click 1" to 0.99))
    run(agentConfig(CountingAi(), ArbigentJevConfig(ArbigentJevSettings(mode = ArbigentJevMode.Disabled), client)))

    assertEquals(0, client.requests.size)
  }

  @Test
  fun shadowModeDoesNotHoldTheAiActionForJev() = runTest {
    val ai = CountingAi()
    val neverAnswers = ArbigentJevClient { awaitCancellation() }
    val agent = run(agentConfig(ai, ArbigentJevConfig(ArbigentJevSettings(mode = ArbigentJevMode.Shadow), neverAnswers)))

    assertTrue(agent.isGoalAchieved())
    assertEquals(3, ai.decisions)
  }

  @Test
  fun optionsFollowTheActionsTheStepAllows() = runTest {
    val client = FakeJevClient(listOf("goal_achieved" to 0.1))
    val interceptor = ArbigentJevDecisionInterceptor(ArbigentJevSettings(), client)

    interceptor.intercept(decisionInput(agentActionTypes = listOf(ClickWithIndex, GoalAchievedAgentAction))) { aiOutput(it) }
    assertTrue("goal_achieved" in client.criteria().keys)
    assertTrue(client.criteria().keys.all { it.startsWith("click ") || it == "goal_achieved" }, client.criteria().keys.toString())

    interceptor.intercept(
      decisionInput(ArbigentScenarioDeviceFormFactor.Tv, defaultAgentActionTypesForTvForVisualMode())
    ) { aiOutput(it) }
    // The goal quotes 'Settings', and a TV step can type text too.
    assertTrue("input Settings" in client.criteria().keys, client.criteria().keys.toString())
  }

  @Test
  fun aToolCallIsOfferedOnlyWithToolsAndLeftToTheAi() = runTest {
    val client = FakeJevClient(listOf("call_tool" to 0.99))
    val interceptor = ArbigentJevDecisionInterceptor(ArbigentJevSettings(), client)

    interceptor.intercept(decisionInput()) { aiOutput(it) }
    assertFalse("call_tool" in client.criteria().keys)

    var aiCalls = 0
    val tools = listOf(MCPTool(Tool(name = "launch_app", description = "Launch an app"), serverName = "device"))
    val output = interceptor.intercept(decisionInput(mcpTools = tools)) { aiCalls++; aiOutput(it) }
    assertTrue("launch_app" in client.criteria().getValue("call_tool"))
    assertEquals(1, aiCalls)
    assertEquals(ArbigentStepSource.Ai, output.step.stepSource)
  }

  @Test
  fun aCancelledRunDoesNotGoOnToTheAi() = runTest {
    var aiCalls = 0
    lateinit var run: Job
    val interceptor = ArbigentJevDecisionInterceptor(ArbigentJevSettings(), ArbigentJevClient { run.cancel(); awaitCancellation() })
    run = launch { interceptor.intercept(decisionInput()) { aiCalls++; aiOutput(it) } }
    run.join()

    assertTrue(run.isCancelled)
    assertEquals(0, aiCalls)
  }

  @Test
  fun aJevRequestCancelledOnItsOwnGoesToTheAi() = runTest {
    val timesOut = ArbigentJevClient { throw CancellationException("client-side timeout") }
    for (mode in listOf(ArbigentJevMode.Active, ArbigentJevMode.Shadow)) {
      var aiCalls = 0
      ArbigentJevDecisionInterceptor(ArbigentJevSettings(mode = mode), timesOut)
        .intercept(decisionInput()) { aiCalls++; aiOutput(it) }
      assertEquals(1, aiCalls, mode.name)
    }
  }

  private fun FakeJevClient.criteria(): Map<String, String> =
    requests.last()["questions"]!!.jsonObject["next"]!!.jsonObject["criteria"]!!.jsonObject
      .mapValues { it.value.jsonPrimitive.content }

  private fun decisionInput(
    formFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
    agentActionTypes: List<AgentActionType> = defaultAgentActionTypesForVisualMode(),
    mcpTools: List<MCPTool>? = null,
  ) = ArbigentAi.DecisionInput(
    stepId = "step",
    contextHolder = ArbigentContextHolder(goal = "Open 'Settings'", maxStep = 10),
    formFactor = formFactor,
    uiTreeStrings = ArbigentUiTreeStrings("", ""),
    focusedTreeString = null,
    agentActionTypes = agentActionTypes,
    screenshotFilePath = "screenshot.png",
    requestUuid = "uuid",
    apiCallJsonLFilePath = "api.jsonl",
    elements = FakeDevice().elements(),
    prompt = ArbigentPrompt(),
    cacheKey = "cacheKey",
    aiOptions = null,
    mcpTools = mcpTools,
  )

  private fun aiOutput(input: ArbigentAi.DecisionInput) = ArbigentAi.DecisionOutput(
    agentActions = listOf(ClickWithIndex(0)),
    step = ArbigentContextHolder.Step(stepId = input.stepId, agentAction = ClickWithIndex(0), cacheKey = input.cacheKey, screenshotFilePath = input.screenshotFilePath),
  )

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
    // An override mode turns Jev on for a project that doesn't configure it; a threshold alone doesn't.
    assertEquals(
      ArbigentJevSettings(mode = ArbigentJevMode.Shadow),
      ArbigentJevConfig.effectiveSettings(null, ArbigentJevOverrides(mode = ArbigentJevMode.Shadow)),
    )
    assertNull(ArbigentJevConfig.effectiveSettings(null, ArbigentJevOverrides(actionThreshold = 0.7)))
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
