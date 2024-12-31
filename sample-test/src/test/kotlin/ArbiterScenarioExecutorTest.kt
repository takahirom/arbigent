package com.github.takahirom.arbiter.sample.test

import com.github.takahirom.arbiter.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import maestro.orchestra.MaestroCommand
import kotlin.test.Test

class ArbiterScenarioExecutorTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbiterCorotuinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    val agentConfig = AgentConfig {
      device(FakeDevice())
      ai(FakeAi())
      addInterceptor(
        object : ArbiterInitializerInterceptor {
          override fun intercept(
            device: ArbiterDevice,
            chain: ArbiterInitializerInterceptor.Chain
          ) {
            println("intercept device: $device")
            chain.proceed(device)
          }
        }
      )
      addInterceptor(
        object : ArbiterStepInterceptor {
          override fun intercept(
            stepInput: ArbiterAgent.StepInput,
            chain: ArbiterStepInterceptor.Chain
          ): ArbiterAgent.StepResult {
            println("Arbiter ArbiterStepInterceptor test intercept stepInput start")
            val result = chain.proceed(stepInput)
            println("Arbiter ArbiterStepInterceptor test intercept stepInput end")
            return result
          }
        }
      )
      addInterceptor(
        object : ArbiterDecisionInterceptor {
          override fun intercept(
            decisionInput: ArbiterAi.DecisionInput,
            chain: ArbiterDecisionInterceptor.Chain
          ): ArbiterAi.DecisionOutput {
            println("  intercept decisionInput: $decisionInput")
            val result = chain.proceed(decisionInput)
            println("  intercept decisionInput result: $result")
            return result
          }
        }
      )
      addInterceptor(
        object : ArbiterExecuteCommandsInterceptor {
          override fun intercept(
            executeCommandsInput: ArbiterAgent.ExecuteCommandsInput,
            chain: ArbiterExecuteCommandsInterceptor.Chain
          ): ArbiterAgent.ExecuteCommandsOutput {
            println("    intercept executeCommandsInput: $executeCommandsInput")
            val result = chain.proceed(executeCommandsInput)
            println("    intercept executeCommandsInput result: $result")
            return result
          }
        }
      )
    }
    val arbiterScenarioExecutor = ArbiterScenarioExecutor {
    }
    val arbiterExecutorScenario = ArbiterScenarioExecutor.ArbiterExecutorScenario(
      listOf(
        ArbiterScenarioExecutor.ArbiterAgentTask("goal1", agentConfig),
        ArbiterScenarioExecutor.ArbiterAgentTask("goal2", agentConfig)
      ),
      maxStepCount = 10,
    )
    arbiterScenarioExecutor.executeAsync(
      arbiterExecutorScenario
    )
    advanceUntilIdle()
  }
}
