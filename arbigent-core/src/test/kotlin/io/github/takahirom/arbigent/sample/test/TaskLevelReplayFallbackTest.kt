package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
      val secondTaskConfig = AgentConfig {
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
            return ArbigentAi.ImageAssertionOutput(
              listOf(
                ArbigentAi.ImageAssertionResult(
                  assertionPrompt = "prompt",
                  isPassed = false,
                  fulfillmentPercent = 0,
                  explanation = "explanation",
                ),
              ),
            )
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

      ArbigentScenarioExecutor(testDispatcher).execute(scenario(), MCPClient())
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

  private fun secondTaskTraceStepCount(): Int {
    val trace = ArbigentFiles.traceDir.listFiles().orEmpty()
      .map { it.readText() }
      .single { """"taskIndex"\s*:\s*1""".toRegex().containsMatchIn(it) }
    return """"decisionOutput"""".toRegex().findAll(trace).count()
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
