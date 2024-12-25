package com.github.takahirom.arbiter.sample.test

import com.github.takahirom.arbiter.*
import kotlinx.coroutines.test.runTest
import maestro.orchestra.MaestroCommand
import kotlin.test.Test

class FakeDevice : Device {
  override fun executeCommands(commands: List<MaestroCommand>) {
    println("executeCommands: $commands")
  }

  override fun viewTreeString(): String {
    println("viewTreeString")
    return "viewTreeString"
  }
}

class FakeAi: Ai {
  override fun decideWhatToDo(decisionInput: Ai.DecisionInput): Ai.DecisionOutput {
    println("decideWhatToDo")
    return Ai.DecisionOutput(
      listOf(ClickWithTextAgentCommand("text"))
    )
  }
}

class Test {
  @Test
  fun tests() = runTest{
    val arbiter = arbiter {
      device(FakeDevice())
      ai(FakeAi())
      addInterceptor(
        object: ArbiterInitializerInterceptor {
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
        object: ArbiterStepInterceptor {
          override fun intercept(
            stepInput: Arbiter.StepInput,
            chain: ArbiterStepInterceptor.Chain
          ): Arbiter.StepResult {
            println("intercept stepInput: $stepInput")
            val result = chain.proceed(stepInput)
            println("intercept stepInput result: $result")
            return result
          }
        }
      )
      addInterceptor(
        object: ArbiterDecisionInterceptor {
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
        object: ArbiterExecuteCommandsInterceptor {
          override fun intercept(
            executeCommandsInput: Arbiter.ExecuteCommandsInput,
            chain: ArbiterExecuteCommandsInterceptor.Chain
          ): Arbiter.ExecuteCommandsOutput {
            println("    intercept executeCommandsInput: $executeCommandsInput")
            val result = chain.proceed(executeCommandsInput)
            println("    intercept executeCommandsInput result: $result")
            return result
          }
        }
      )
    }
    arbiter.execute("Open my app")
    arbiter.waitUntilFinished()
  }
}
