package com.github.takahirom.arbiter.sample.test

import com.github.takahirom.arbiter.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import maestro.orchestra.MaestroCommand
import kotlin.test.Test

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

class Test {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbiterCorotuinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    val agentConfig = agentConfig {
      device(FakeDevice())
      ai(FakeAi())
      addInterceptor(
        object : ArbiterInitializerInterceptor {
          override fun intercept(
            device: Device,
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
            stepInput: Agent.StepInput,
            chain: ArbiterStepInterceptor.Chain
          ): Agent.StepResult {
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
            decisionInput: Ai.DecisionInput,
            chain: ArbiterDecisionInterceptor.Chain
          ): Ai.DecisionOutput {
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
            executeCommandsInput: Agent.ExecuteCommandsInput,
            chain: ArbiterExecuteCommandsInterceptor.Chain
          ): Agent.ExecuteCommandsOutput {
            println("    intercept executeCommandsInput: $executeCommandsInput")
            val result = chain.proceed(executeCommandsInput)
            println("    intercept executeCommandsInput result: $result")
            return result
          }
        }
      )
    }
    val arbiter = arbiter {
    }
    val scenario = Arbiter.Scenario(
      listOf(
        Arbiter.Task("goal1", agentConfig),
        Arbiter.Task("goal2", agentConfig)
      ),
      maxTurnCount = 10
    )
    arbiter.executeAsync(
      scenario
    )
    advanceUntilIdle()
  }
}
