package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import io.github.takahirom.arbigent.result.ArbigentStepSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import maestro.MaestroException
import maestro.TreeNode
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A replayed task that fails costs only itself: the tasks before it keep what they replayed, and
 * the task is re-run under the AI in place. Restarting the whole scenario instead would re-pay for
 * every task already replayed, which is the saving the mode exists for.
 */
class TaskLevelReplayFallbackTest {
  private val originalTraceDir = ArbigentFiles.traceDir

  @AfterTest
  fun restoreTraceDir() {
    ArbigentFiles.traceDir = originalTraceDir
  }

  @Test
  fun `preceding actions are deduplicated while feedback and history metadata are preserved`() = runTest {
    val action = ArbigentContextHolder.Step(
      stepId = "replayed-action",
      agentAction = ClickWithTextAgentAction("text"),
      memo = "Opened the menu",
      imageDescription = "The menu button is visible",
      cacheKey = "replayed-cache",
      screenshotFilePath = "replayed-screenshot.png",
      timestamp = 123L,
      stepSource = ArbigentStepSource.Replay,
    )
    val assertionFeedback = action.copy(agentAction = null, feedback = "Image assertion failed.")
    val divergence = action.copy(agentAction = null, feedback = "Replay diverged: the target is absent")
    var firstPrompt = ""
    var seeds = emptyList<ArbigentContextHolder.Step>()
    val config = AgentConfig {
      deviceFactory { FakeDevice() }
      aiFactory { FakeAi() }
      addInterceptor(object : ArbigentDecisionInterceptor {
        override suspend fun intercept(
          decisionInput: ArbigentAi.DecisionInput,
          chain: ArbigentDecisionInterceptor.Chain,
        ): ArbigentAi.DecisionOutput {
          if (firstPrompt.isEmpty()) {
            seeds = decisionInput.contextHolder.steps()
            firstPrompt = decisionInput.contextHolder.prompt("", "", ArbigentAiOptions())
          }
          return chain.proceed(decisionInput).withStepIdOf(decisionInput)
        }
      })
    }
    val agent = ArbigentAgent(
      config,
      coroutineContext[CoroutineDispatcher]!!,
      replayTrace = null,
      runInitializers = false,
      precedingSteps = listOf(action, assertionFeedback, action, divergence),
    )
    agent.execute("scenario", "goal", maxStep = 3, mcpClient = MCPClient())
    advanceUntilIdle()

    assertEquals(3, seeds.size)
    assertEquals(action.copy(
      agentAction = null,
      feedback = "This action was already performed before this attempt, replayed from a recording: ${action.agentAction!!.stepLogText()}",
    ), seeds.first())
    assertEquals(listOf(assertionFeedback, divergence), seeds.drop(1))
    assertTrue(firstPrompt.contains(divergence.feedback!!))
    assertTrue(firstPrompt.contains("Current step: 1\n"))
    assertTrue(agent.isGoalAchieved())
    assertEquals(3, agent.latestArbigentContext()!!.countMeaningfulActions())
  }

  @Test
  fun `a task that fails while replaying is re-run alone, leaving the tasks before it replayed`() =
    runTest {
      ArbigentFiles.traceDir =
        Files.createTempDirectory("arbigent-task-level-fallback").toFile()
      val testDispatcher = coroutineContext[CoroutineDispatcher]!!

      var firstTaskExecutions = 0
      var firstTaskDecisions = 0
      val firstTaskConfig = AgentConfig {
        deviceFactory { FakeDevice() }
        aiFactory { FakeAi() }
        addInterceptor(object : ArbigentExecutionInterceptor {
          override suspend fun intercept(
            executeInput: ArbigentAgent.ExecuteInput,
            chain: ArbigentExecutionInterceptor.Chain,
          ): ArbigentAgent.ExecutionResult {
            firstTaskExecutions++
            return chain.proceed(executeInput)
          }
        })
        addInterceptor(object : ArbigentDecisionInterceptor {
          override suspend fun intercept(
            decisionInput: ArbigentAi.DecisionInput,
            chain: ArbigentDecisionInterceptor.Chain,
          ): ArbigentAi.DecisionOutput {
            firstTaskDecisions++
            return chain.proceed(decisionInput).withStepIdOf(decisionInput)
          }
        })
      }

      // Fails the assertion once, which is what a replayed task that ends on the wrong screen
      // looks like. The re-run under the AI then passes.
      var failNextAssertion = false
      var captureReplacement = false
      val replacementActionCounts = mutableListOf<Int>()
      var replacementPrompt = ""
      var replacementSeeds = emptyList<ArbigentContextHolder.Step>()
      val secondTaskConfig = AgentConfig {
        deviceFactory { FakeDevice() }
        aiFactory { FakeAi() }
        addInterceptor(object : ArbigentDecisionInterceptor {
          override suspend fun intercept(
            decisionInput: ArbigentAi.DecisionInput,
            chain: ArbigentDecisionInterceptor.Chain,
          ): ArbigentAi.DecisionOutput {
            if (captureReplacement) {
              val holder = decisionInput.contextHolder
              if (replacementActionCounts.isEmpty()) {
                replacementSeeds = holder.steps()
                replacementPrompt = holder.prompt("", "", ArbigentAiOptions())
              }
              replacementActionCounts += holder.countMeaningfulActions()
            }
            return chain.proceed(decisionInput).withStepIdOf(decisionInput)
          }
        })
        addInterceptor(object : ArbigentImageAssertionInterceptor {
          override fun intercept(
            imageAssertionInput: ArbigentAi.ImageAssertionInput,
            chain: ArbigentImageAssertionInterceptor.Chain,
          ): ArbigentAi.ImageAssertionOutput {
            if (!failNextAssertion) return chain.proceed(imageAssertionInput)
            failNextAssertion = false
            return failedAssertion()
          }
        })
      }

      fun scenario() = ArbigentScenario(
        id = "scenario",
        agentTasks = listOf(
          ArbigentAgentTask("task-1", "goal1", firstTaskConfig),
          // Wide enough for the replayed and the replacement actions together, so the trace written
          // after the fallback stays within what the task allows.
          ArbigentAgentTask("task-2", "goal2", secondTaskConfig, maxStep = 5),
        ),
        maxStepCount = 10,
        tags = setOf(),
        isLeaf = true,
        replayWithFallback = true,
      )

      // Recording run: both tasks pass under the AI, so both traces are written.
      ArbigentScenarioExecutor(testDispatcher).execute(scenario(), MCPClient())
      advanceUntilIdle()
      assertEquals(
        2,
        ArbigentFiles.traceDir.listFiles().orEmpty().size,
        "the recording run should have written one trace per task",
      )

      firstTaskExecutions = 0
      firstTaskDecisions = 0
      failNextAssertion = true
      captureReplacement = true

      val executor = ArbigentScenarioExecutor(testDispatcher)
      executor.execute(scenario(), MCPClient())
      advanceUntilIdle()

      assertEquals(
        1,
        firstTaskExecutions,
        "the first task should run once; re-running it means the whole scenario restarted",
      )
      assertEquals(
        0,
        firstTaskDecisions,
        "the first task should have replayed without asking the AI anything",
      )

      val replayedSteps = executor.taskAssignmentsHistory().first()[1].agent
        .latestArbigentContext()!!.steps()
      val replayedActions = replayedSteps.filter { it.agentAction != null }.distinctBy { it.stepId }
      assertEquals(2, replayedActions.size)
      assertTrue(replacementSeeds.all { it.agentAction == null })
      assertEquals(replayedSteps.map { it.stepId }, replacementSeeds.map { it.stepId })
      replayedActions.forEach { step ->
        assertTrue(replacementPrompt.contains(
          "This action was already performed before this attempt, replayed from a recording: ${step.agentAction!!.stepLogText()}",
        ))
      }
      val replayFeedback = replayedSteps.filter { it.agentAction == null }
      assertTrue(replayFeedback.any { it.feedback!!.startsWith("Failed replay image assertion") })
      replayedSteps.zip(replacementSeeds).forEach { (original, seed) ->
        if (original.agentAction == null) assertEquals(original, seed)
      }
      replayFeedback.forEach { assertTrue(replacementPrompt.contains(it.feedback!!)) }
      assertTrue(replacementPrompt.contains("Current step: 1\n"))
      assertEquals(listOf(0, 1, 2), replacementActionCounts,
        "the replayed history must not count toward the replacement's step number")
      val replacement = executor.taskAssignments()[1].agent
      assertTrue(replacement.isGoalAchieved())
      val replacementActions = replacement.latestArbigentContext()!!.steps()
        .filter { it.agentAction != null }.distinctBy { it.stepId }
      assertEquals(3, replacementActions.size)
      val traceSteps = secondTaskTrace().steps.map { it.decisionOutput.step }
      assertEquals((replayedActions + replacementActions).map { it.stepId }, traceSteps.map { it.stepId })
      assertEquals(traceSteps.size, traceSteps.distinctBy { it.stepId }.size)
      assertEquals(
        (replayedActions + replacementActions).map { it.agentAction!!.stepLogText() },
        traceSteps.map { it.agentAction!!.stepLogText() },
      )

      // The replacement agent starts from the device state the replayed actions left behind, so
      // its own steps alone would not replay from the start of the task. Both Fake AI runs decide
      // two actions and then the goal; the replay executed the two actions before the assertion
      // failed, so the stored trace holds those two plus the three the replacement decided.
      assertEquals(
        5,
        secondTaskTraceStepCount(),
        "the fallback trace should keep what the task replayed before it fell back",
      )
    }

  /**
   * The replacement agent carries on from where the replay left the device, so re-running the
   * task's initializers would undo exactly the state it is meant to continue from.
   */
  @Test
  fun `the agent replacing a task that fell back does not run the initializers again`() = runTest {
    ArbigentFiles.traceDir =
      Files.createTempDirectory("arbigent-task-level-fallback-initializer").toFile()
    val testDispatcher = coroutineContext[CoroutineDispatcher]!!

    var failNextAssertion = false
    var secondTaskInitializations = 0
    val firstTaskConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      aiFactory { FakeAi() }
      addInterceptor(object : ArbigentDecisionInterceptor {
        override suspend fun intercept(
          decisionInput: ArbigentAi.DecisionInput,
          chain: ArbigentDecisionInterceptor.Chain,
        ): ArbigentAi.DecisionOutput = chain.proceed(decisionInput).withStepIdOf(decisionInput)
      })
    }
    val secondTaskConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      aiFactory { FakeAi() }
      addInterceptor(object : ArbigentInitializerInterceptor {
        override fun intercept(device: ArbigentDevice, chain: ArbigentInitializerInterceptor.Chain) {
          secondTaskInitializations++
          chain.proceed(device)
        }
      })
      addInterceptor(object : ArbigentDecisionInterceptor {
        override suspend fun intercept(
          decisionInput: ArbigentAi.DecisionInput,
          chain: ArbigentDecisionInterceptor.Chain,
        ): ArbigentAi.DecisionOutput = chain.proceed(decisionInput).withStepIdOf(decisionInput)
      })
      addInterceptor(object : ArbigentImageAssertionInterceptor {
        override fun intercept(
          imageAssertionInput: ArbigentAi.ImageAssertionInput,
          chain: ArbigentImageAssertionInterceptor.Chain,
        ): ArbigentAi.ImageAssertionOutput {
          if (!failNextAssertion) return chain.proceed(imageAssertionInput)
          failNextAssertion = false
          return failedAssertion()
        }
      })
    }

    fun scenario() = ArbigentScenario(
      id = "scenario",
      agentTasks = listOf(
        ArbigentAgentTask("task-1", "goal1", firstTaskConfig),
        ArbigentAgentTask("task-2", "goal2", secondTaskConfig),
      ),
      maxStepCount = 10,
      tags = setOf(),
      isLeaf = true,
      replayWithFallback = true,
    )

    ArbigentScenarioExecutor(testDispatcher).execute(scenario(), MCPClient())
    advanceUntilIdle()

    secondTaskInitializations = 0
    failNextAssertion = true
    ArbigentScenarioExecutor(testDispatcher).execute(scenario(), MCPClient())
    advanceUntilIdle()

    assertEquals(
      1,
      secondTaskInitializations,
      "the initializers should run for the replay attempt only, not again for the replacement",
    )
    // Two replayed actions plus the three the replacement decided: the count says the task really
    // did fall back, and that a task with an initializer keeps what it replayed all the same.
    assertEquals(
      5,
      secondTaskTraceStepCount(),
      "the fallback trace should keep what the task replayed before it fell back",
    )
  }

  /**
   * An initializer that failed left the device wherever the failure did, so there is no replayed
   * state for a replacement to carry on from. Such a task is not given a task-level fallback: the
   * whole scenario restarts in normal mode, initializers included.
   */
  @Test
  fun `a task whose initializer fails while replaying restarts the whole scenario`() = runTest {
    ArbigentFiles.traceDir =
      Files.createTempDirectory("arbigent-task-level-fallback-init-failure").toFile()
    val testDispatcher = coroutineContext[CoroutineDispatcher]!!

    var failNextInitialization = false
    var firstTaskDecisions = 0
    var secondTaskInitializations = 0
    val firstTaskConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      aiFactory { FakeAi() }
      addInterceptor(object : ArbigentDecisionInterceptor {
        override suspend fun intercept(
          decisionInput: ArbigentAi.DecisionInput,
          chain: ArbigentDecisionInterceptor.Chain,
        ): ArbigentAi.DecisionOutput {
          firstTaskDecisions++
          return chain.proceed(decisionInput).withStepIdOf(decisionInput)
        }
      })
    }
    val secondTaskConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      aiFactory { FakeAi() }
      addInterceptor(object : ArbigentInitializerInterceptor {
        override fun intercept(device: ArbigentDevice, chain: ArbigentInitializerInterceptor.Chain) {
          secondTaskInitializations++
          if (failNextInitialization) {
            failNextInitialization = false
            throw MaestroException.AssertionFailure(
              "initializer failed",
              TreeNode(),
              "initializer failed",
            )
          }
          chain.proceed(device)
        }
      })
      addInterceptor(object : ArbigentDecisionInterceptor {
        override suspend fun intercept(
          decisionInput: ArbigentAi.DecisionInput,
          chain: ArbigentDecisionInterceptor.Chain,
        ): ArbigentAi.DecisionOutput = chain.proceed(decisionInput).withStepIdOf(decisionInput)
      })
    }

    fun scenario() = ArbigentScenario(
      id = "scenario",
      agentTasks = listOf(
        ArbigentAgentTask("task-1", "goal1", firstTaskConfig),
        ArbigentAgentTask("task-2", "goal2", secondTaskConfig),
      ),
      maxStepCount = 10,
      maxRetry = 0,
      tags = setOf(),
      isLeaf = true,
      replayWithFallback = true,
    )

    ArbigentScenarioExecutor(testDispatcher).execute(scenario(), MCPClient())
    advanceUntilIdle()

    firstTaskDecisions = 0
    secondTaskInitializations = 0
    failNextInitialization = true
    ArbigentScenarioExecutor(testDispatcher).execute(scenario(), MCPClient())
    advanceUntilIdle()

    assertTrue(
      firstTaskDecisions > 0,
      "the scenario should restart under the AI; a task-level fallback would have kept the first " +
        "task replayed",
    )
    assertEquals(
      2,
      secondTaskInitializations,
      "the initializers should run for the failed replay attempt and again for the restart",
    )
  }

  /**
   * A replayed step costs an iteration just like one the AI decides, so the concatenated trace a
   * fallback would record can be longer than the task's own step limit. Storing it would make the
   * task fall back, and pay for the AI, on every run from then on.
   */
  @Test
  fun `a fallback does not store a trace longer than the task allows`() = runTest {
    ArbigentFiles.traceDir =
      Files.createTempDirectory("arbigent-task-level-fallback-max-step").toFile()
    val testDispatcher = coroutineContext[CoroutineDispatcher]!!

    var failNextAssertion = false
    val taskConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      aiFactory { FakeAi() }
      addInterceptor(object : ArbigentDecisionInterceptor {
        override suspend fun intercept(
          decisionInput: ArbigentAi.DecisionInput,
          chain: ArbigentDecisionInterceptor.Chain,
        ): ArbigentAi.DecisionOutput = chain.proceed(decisionInput).withStepIdOf(decisionInput)
      })
      addInterceptor(object : ArbigentImageAssertionInterceptor {
        override fun intercept(
          imageAssertionInput: ArbigentAi.ImageAssertionInput,
          chain: ArbigentImageAssertionInterceptor.Chain,
        ): ArbigentAi.ImageAssertionOutput {
          if (!failNextAssertion) return chain.proceed(imageAssertionInput)
          failNextAssertion = false
          return failedAssertion()
        }
      })
    }

    // The Fake AI decides two actions and then the goal, which is exactly the limit: a fallback
    // would concatenate the two it replayed with the three the replacement decided.
    fun scenario() = ArbigentScenario(
      id = "scenario",
      agentTasks = listOf(
        ArbigentAgentTask("task-1", "goal1", taskConfig, maxStep = 3),
      ),
      maxStepCount = 3,
      tags = setOf(),
      isLeaf = true,
      replayWithFallback = true,
    )

    ArbigentScenarioExecutor(testDispatcher).execute(scenario(), MCPClient())
    advanceUntilIdle()
    assertEquals(
      3,
      taskTraceStepCount(),
      "the recording run should have written the three steps the AI decided",
    )

    failNextAssertion = true
    ArbigentScenarioExecutor(testDispatcher).execute(scenario(), MCPClient())
    advanceUntilIdle()

    assertEquals(
      0,
      ArbigentFiles.traceDir.listFiles().orEmpty().size,
      "a trace that could never replay within the step limit should have been discarded",
    )

    // The next run has no trace, so it runs under the AI and records one that fits again.
    ArbigentScenarioExecutor(testDispatcher).execute(scenario(), MCPClient())
    advanceUntilIdle()
    assertEquals(
      3,
      taskTraceStepCount(),
      "the run after the discard should have recorded a replayable trace again",
    )
  }

  private fun failedAssertion(): ArbigentAi.ImageAssertionOutput = ArbigentAi.ImageAssertionOutput(
    listOf(
      ArbigentAi.ImageAssertionResult(
        assertionPrompt = "prompt",
        isPassed = false,
        fulfillmentPercent = 0,
        explanation = "explanation",
      ),
    ),
  )

  private fun taskTraceStepCount(): Int {
    val trace = ArbigentFiles.traceDir.listFiles().orEmpty().single().readText()
    return """"decisionOutput"""".toRegex().findAll(trace).count()
  }

  private fun secondTaskTraceStepCount(): Int {
    return secondTaskTrace().steps.size
  }

  private fun secondTaskTrace(): ArbigentReplayTrace {
    val trace = ArbigentFiles.traceDir.listFiles().orEmpty()
      .map { it.readText() }
      .single { """"taskIndex"\s*:\s*1""".toRegex().containsMatchIn(it) }
    return Json { useArrayPolymorphism = true }.decodeFromString<ArbigentReplayTrace>(trace)
  }

  /**
   * [FakeAi] labels every step it decides with the same id, and a trace keeps one step per id, so
   * without this a recorded run collapses to its first step and is rejected as never reaching the
   * goal. A real AI carries the id it was given.
   */
  private fun ArbigentAi.DecisionOutput.withStepIdOf(
    decisionInput: ArbigentAi.DecisionInput,
  ): ArbigentAi.DecisionOutput = copy(step = step.copy(stepId = decisionInput.stepId))
}
