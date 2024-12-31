package com.github.takahirom.arbiter.sample.test

import com.github.takahirom.arbiter.*
import dadb.Dadb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes


@Ignore("This test is not working on CI")
class RealArbiterTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest(
    timeout = 10.minutes
  ) {
    ArbiterCorotuinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!

    val project: ArbiterProjectConfig = ArbiterProjectSerializer().load(
      this::class.java.getResourceAsStream("/projects/nowinandroidsample.toml")
    )
    project.scenarios.forEach { scenario ->
      val executorScenario = project.cerateExecutorScenario(
        scenario = scenario,
        aiFactory = {
          OpenAIAi(
            apiKey = System.getenv("OPENAI_API_KEY")
              ?: error("OPENAI_API_KEY is not set")
          )
        },
        deviceFactory = {
          AvailableDevice.Android(
            dadb = Dadb.discover()!!
          ).connectToDevice()
        }
      )
      println("Start scenario: ${scenario.goal}")
      executorScenario.arbiterAgentTasks.forEach { task ->
        println("  task: ${task.goal}")
      }
      val executor = ArbiterScenarioExecutor()
      executor.execute(executorScenario)
      if (!executor.isArchived()) {
        error(
          "Failed to archive Goal:${scenario.goal}\n${
            executor.agentTaskToAgentStateFlow.value.map { it.first.goal + ":\n  isArchived:" + it.second.isArchivedStateFlow.value }
              .joinToString("\n")
          }"
        )
      }
    }
  }
}
