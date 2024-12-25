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
    return "viewTreeString"
  }
}

class FakeAi: Ai {
  override fun decideWhatToDo(
    arbiterContext: ArbiterContext,
    dumpHierarchy: String,
    screenshot: String?,
    agentCommandMap: Map<String, AgentCommand>,
    screenshotFileName: String
  ): AgentCommand {
    return ClickWithIdAgentCommand("id")
  }
}

class Test {
  @Test
  fun tests() = runTest{
    val arbiter = arbiter {
      device(FakeDevice())
      ai(FakeAi())
    }
    arbiter.execute("Open my app")
    arbiter.waitUntilFinished()
  }
}
