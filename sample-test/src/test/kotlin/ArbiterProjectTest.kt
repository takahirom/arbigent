package com.github.takahirom.arbiter.sample.test

import com.github.takahirom.arbiter.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test


class ArbiterProjectTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbiterCorotuinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!

    val project = ArbiterProjectSerializer().load(
      this::class.java.getResourceAsStream("/projects/nowinandroidsample.toml")
    )
    project.scenarios.forEach { scenario ->
      val executorScenario = project.cerateExecutorScenario(
        scenario = scenario,
        aiFactory = { FakeAi() },
        deviceFactory = { FakeDevice() }
      )
      val executor = ArbiterScenarioExecutor()
      executor.execute(executorScenario)
    }
  }
}
