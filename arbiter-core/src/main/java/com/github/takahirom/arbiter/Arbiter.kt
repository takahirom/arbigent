package com.github.takahirom.arbiter

import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import maestro.MaestroException
import maestro.orchestra.BackPressCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TakeScreenshotCommand
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO


class Arbiter(
  private val interceptors: List<ArbiterInterceptor>,
  private val ai: Ai,
  private val device: Device
) {
  private val decisionInterceptors: List<ArbiterDecisionInterceptor> = interceptors
    .filterIsInstance<ArbiterDecisionInterceptor>()
  private val executeCommandInterceptors: List<ArbiterExecuteCommandInterceptor> = interceptors
    .filterIsInstance<ArbiterExecuteCommandInterceptor>()
  private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var job: Job? = null
  val arbiterContextStateFlow: MutableStateFlow<ArbiterContext?> = MutableStateFlow(null)
  val isRunningStateFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
  private val currentGoalStateFlow = MutableStateFlow<String?>(null)
  val isArchivedStateFlow = arbiterContextStateFlow
    .flatMapConcat { it?.turns ?: flowOf() }
    .map { it.any { it.agentCommand is GoalAchievedAgentCommand } }
    .stateIn(coroutineScope, SharingStarted.Lazily, false)

  suspend fun waitUntilFinished() {
    isRunningStateFlow.first { !it }
  }

  fun execute(goal: String) {
    isRunningStateFlow.value = true
    this.currentGoalStateFlow.value = goal
    val arbiterContext = ArbiterContext(goal)
    arbiterContextStateFlow.value = arbiterContext
    job?.cancel()
    job = coroutineScope.launch {
      try {
        val agentCommands: List<AgentCommand> = defaultAgentCommands()
        val agentCommandMap = agentCommands.associateBy { it.actionName }
        repeat(10) {
          try {
            yield()
            device.executeCommands(
              commands = listOf(
                MaestroCommand(
                  backPressCommand = BackPressCommand()
                )
              ),
            )
          } catch (e: Exception) {
            println("Failed to back press: $e")
          }
        }
        for (i in 0..10) {
          yield()
          if (step(arbiterContext, agentCommandMap)) {
            isRunningStateFlow.value = false
            return@launch
          }
        }
        println("終わり")
        isRunningStateFlow.value = false
      } catch (e: Exception) {
        println("Failed to run agent: $e")
        e.printStackTrace()
        isRunningStateFlow.value = false
      }
    }
  }

  private fun step(
    arbiterContext: ArbiterContext,
    agentCommandMap: Map<String, AgentCommand>
  ): Boolean {
    val screenshotFileName = System.currentTimeMillis().toString()
    try {
      device.executeCommands(
        commands = listOf(
          MaestroCommand(
            takeScreenshotCommand = TakeScreenshotCommand(
              screenshotFileName
            )
          ),
        ),
      )
    } catch (e: Exception) {
      println("Failed to take screenshot: $e")
    }
    println("Inputs: ${arbiterContext.prompt()}")
    val agentCommand = decisionInterceptors.fold(
      ArbiterDecisionInterceptor.Chain({
        ai.decideWhatToDo(
          arbiterContext = arbiterContext,
          dumpHierarchy = device.viewTreeString(),
          screenshotFileName = screenshotFileName,
          screenshot = null, //"screenshots/test.png",
          agentCommandMap = agentCommandMap
        )
      }),
      createChain = { chain, interceptor ->
        ArbiterDecisionInterceptor.Chain { arbiterContext ->
          interceptor.intercept(arbiterContext, chain)
        }
      },
      context = arbiterContext
    )
      .proceed(arbiterContext)
    println("What to do: ${agentCommand}")
    if (agentCommand is GoalAchievedAgentCommand) {
      println("Goal achieved")
      return true
    } else {
      try {
        agentCommand.runOrchestraCommand(device)
      } catch (e: MaestroException) {
        arbiterContext.add(
          ArbiterContext.Turn(
            memo = "Failed to perform action: ${e.message}",
            screenshotFileName = screenshotFileName
          )
        )
      } catch (e: StatusRuntimeException) {
        arbiterContext.add(
          ArbiterContext.Turn(
            memo = "Failed to perform action: ${e.message}",
            screenshotFileName = screenshotFileName
          )
        )
      } catch (e: IllegalStateException) {
        arbiterContext.add(
          ArbiterContext.Turn(
            memo = "Failed to perform action: ${e.message}",
            screenshotFileName = screenshotFileName
          )
        )
      }
    }
    return false
  }

  private fun <I, C> List<I>.fold(chain: C, createChain: (C, I) -> C, context: ArbiterContext): C {
    return fold(chain) { acc, interceptor ->
      createChain(acc, interceptor)
    }
  }

  fun cancel() {
    job?.cancel()
    isRunningStateFlow.value = false
  }

  class Builder {
    private val interceptors = mutableListOf<ArbiterInterceptor>()
    private var device: Device? = null
    private var ai: Ai? = null

    fun addInterceptor(interceptor: ArbiterInterceptor) {
      interceptors.add(interceptor)
    }

    fun device(device: Device) {
      this.device = device
    }

    fun ai(ai: Ai) {
      this.ai = ai
    }

    fun build(): Arbiter {
      return Arbiter(interceptors, ai!!, device!!)
    }
  }
}

// DSL
fun arbiter(block: Arbiter.Builder.() -> Unit = {}): Arbiter {
  val builder = Arbiter.Builder()
  builder.block()
  return builder.build()
}

interface ArbiterInterceptor

interface ArbiterDecisionInterceptor : ArbiterInterceptor {
  fun intercept(arbiterContext: ArbiterContext, chain: Chain): AgentCommand

  fun interface Chain {
    fun proceed(arbiterContext: ArbiterContext): AgentCommand
  }
}

interface ArbiterExecuteCommandInterceptor : ArbiterInterceptor {
  fun intercept(arbiterContext: ArbiterContext, chain: Chain)
  interface Chain {
    fun proceed(arbiterContext: ArbiterContext)
  }
}



fun defaultAgentCommands(): List<AgentCommand> {
  return listOf(
    ClickWithTextAgentCommand(""),
    ClickWithIdAgentCommand(""),
    InputTextAgentCommand(""),
    BackPressAgentCommand,
    KeyPressAgentCommand(""),
    ScrollAgentCommand,
    GoalAchievedAgentCommand
  )
}

