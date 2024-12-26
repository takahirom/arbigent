package com.github.takahirom.arbiter

import com.github.takahirom.arbiter.Arbiter.*
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import maestro.MaestroException
import maestro.orchestra.BackPressCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TakeScreenshotCommand
import kotlin.time.Duration.Companion.milliseconds

class Arbiter(
  interceptors: List<ArbiterInterceptor>,
  private val ai: Ai,
  private val device: Device,
) {
  private val decisionInterceptors: List<ArbiterDecisionInterceptor> = interceptors
    .filterIsInstance<ArbiterDecisionInterceptor>()
  private val decisionChain: (Ai.DecisionInput) -> Ai.DecisionOutput = decisionInterceptors.foldRight(
    { input: Ai.DecisionInput -> ai.decideWhatToDo(input) },
    { interceptor, acc ->
      { input ->
        interceptor.intercept(input) { decisionInput -> acc(decisionInput) }
      }
    }
  )
  private val executeCommandsInterceptors: List<ArbiterExecuteCommandsInterceptor> = interceptors
    .filterIsInstance<ArbiterExecuteCommandsInterceptor>()
  private val executeCommandChain: (ExecuteCommandsInput) -> ExecuteCommandsOutput =
    executeCommandsInterceptors.foldRight(
      { input: ExecuteCommandsInput -> executeCommands(input) },
      { interceptor: ArbiterExecuteCommandsInterceptor, acc: (ExecuteCommandsInput) -> ExecuteCommandsOutput ->
        { input ->
          interceptor.intercept(input) { executeCommandsInput -> acc(executeCommandsInput) }
        }
      }
    )
  private val initializerInterceptors: List<ArbiterInitializerInterceptor> = interceptors
    .filterIsInstance<ArbiterInitializerInterceptor>()
  private val initializerChain: (Device) -> Unit = initializerInterceptors.foldRight(
    { device: Device -> initialize(device) },
    { interceptor, acc ->
      { device ->
        interceptor.intercept(device) { device -> acc(device) }
      }
    }
  )
  private val stepInterceptors: List<ArbiterStepInterceptor> = interceptors
    .filterIsInstance<ArbiterStepInterceptor>()
  private val stepChain: (StepInput) -> StepResult = stepInterceptors.foldRight(
    { input: StepInput -> step(input) },
    { interceptor, acc ->
      { input ->
        interceptor.intercept(input) { stepInput -> acc(stepInput) }
      }
    }
  )
  private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var job: Job? = null
  val arbiterContextHistoryStateFlow: MutableStateFlow<List<ArbiterContextHolder>> = MutableStateFlow(listOf())
  val arbiterContextHolderStateFlow: MutableStateFlow<ArbiterContextHolder?> = MutableStateFlow(null)
  val isRunningStateFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
  private val currentGoalStateFlow = MutableStateFlow<String?>(null)
  val isArchivedStateFlow = arbiterContextHolderStateFlow
    .flatMapLatest {
      it?.turns ?: flowOf()
    }
    .map { turns: List<ArbiterContextHolder.Turn> ->
      turns.any { it.agentCommand is GoalAchievedAgentCommand }
    }
    .stateIn(coroutineScope, SharingStarted.Lazily, false)

  suspend fun waitUntilFinished() {
    isRunningStateFlow.first { !it }
  }

  fun execute(
    goal: String,
    maxStep: Int = 10,
    retry: Int = 3,
    agentCommandTypes: List<AgentCommandType> = defaultAgentCommandTypes()
  ) {
    println("Start executing goal: $goal")
    job?.cancel()
    job = coroutineScope.launch {
      var remainRetry = retry
      do {
        execute(goal, maxStep, agentCommandTypes)
        yield()
        try {
          withTimeout(100.milliseconds) {
            isArchivedStateFlow.first { it }
          }
        } catch (e: TimeoutCancellationException) {
        }
      } while (isArchivedStateFlow.value.not() && remainRetry-- > 0)
    }
  }

  private suspend fun execute(
      goal: String,
      maxStep: Int,
      agentCommandTypes: List<AgentCommandType>
  ) {
    try {
      isRunningStateFlow.value = true
      currentGoalStateFlow.value = goal
      val arbiterContextHolder = ArbiterContextHolder(goal)
      println("Setting new ArbiterContextHolder: $arbiterContextHolder")
      arbiterContextHolderStateFlow.value = arbiterContextHolder
      arbiterContextHistoryStateFlow.value = arbiterContextHistoryStateFlow.value + arbiterContextHolder

      initializerChain(device)
      var stepRemain = maxStep
      while(stepRemain-- > 0 && isArchivedStateFlow.value.not()) {
        val stepInput = StepInput(
          arbiterContextHolder = arbiterContextHolder,
          agentCommandTypes = agentCommandTypes,
          device = device,
          ai = ai,
          decisionChain = decisionChain,
          executeCommandChain = executeCommandChain
        )
        when (stepChain(stepInput)) {
          StepResult.GoalAchieved -> {
            isRunningStateFlow.value = false
            break
          }

          StepResult.Continue -> {
            // continue
            delay(1000)
          }
        }
      }
      println("Finish")
      isRunningStateFlow.value = false
    } catch (e: Exception) {
      println("Failed to run agent: $e")
      e.printStackTrace()
      isRunningStateFlow.value = false
    }
  }

  data class StepInput(
    val arbiterContextHolder: ArbiterContextHolder,
    val agentCommandTypes: List<AgentCommandType>,
    val device: Device,
    val ai: Ai,
    val decisionChain: (Ai.DecisionInput) -> Ai.DecisionOutput,
    val executeCommandChain: (ExecuteCommandsInput) -> ExecuteCommandsOutput,
  )

  sealed interface StepResult {
    object GoalAchieved : StepResult
    object Continue : StepResult
  }

  data class ExecuteCommandsInput(
    val decisionOutput: Ai.DecisionOutput,
    val arbiterContextHolder: ArbiterContextHolder,
    val screenshotFileName: String,
    val device: Device,
  )

  class ExecuteCommandsOutput


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

fun arbiter(block: Arbiter.Builder.() -> Unit = {}): Arbiter {
  val builder = Arbiter.Builder()
  builder.block()
  return builder.build()
}

interface ArbiterInterceptor

interface ArbiterInitializerInterceptor : ArbiterInterceptor {
  fun intercept(device: Device, chain: Chain)
  fun interface Chain {
    fun proceed(device: Device)
  }
}

interface ArbiterDecisionInterceptor : ArbiterInterceptor {
  fun intercept(decisionInput: Ai.DecisionInput, chain: Chain): Ai.DecisionOutput

  fun interface Chain {
    fun proceed(decisionInput: Ai.DecisionInput): Ai.DecisionOutput
  }
}

interface ArbiterExecuteCommandsInterceptor : ArbiterInterceptor {
  fun intercept(executeCommandsInput: ExecuteCommandsInput, chain: Chain): ExecuteCommandsOutput
  fun interface Chain {
    fun proceed(executeCommandsInput: ExecuteCommandsInput): ExecuteCommandsOutput
  }
}

interface ArbiterStepInterceptor : ArbiterInterceptor {
  fun intercept(stepInput: StepInput, chain: Chain): StepResult
  fun interface Chain {
    fun proceed(stepInput: StepInput): StepResult
  }
}

fun defaultAgentCommandTypes(): List<AgentCommandType> {
  return listOf(
    ClickWithIdAgentCommand,
    ClickWithTextAgentCommand,
    InputTextAgentCommand,
    BackPressAgentCommand,
    KeyPressAgentCommand,
    ScrollAgentCommand,
    GoalAchievedAgentCommand
  )
}

private fun initialize(device: Device) {
  repeat(10) {
    try {
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
}


private fun executeCommands(
  executeCommandsInput: ExecuteCommandsInput,
): ExecuteCommandsOutput {
  val (decisionOutput, arbiterContextHolder, screenshotFileName, device) = executeCommandsInput
  decisionOutput.agentCommands.forEach { agentCommand ->
    try {
      agentCommand.runOrchestraCommand(device)
    } catch (e: MaestroException) {
      e.printStackTrace()
      arbiterContextHolder.addTurn(
        ArbiterContextHolder.Turn(
          memo = "Failed to perform action: ${e.message}",
          screenshotFileName = screenshotFileName
        )
      )
    } catch (e: StatusRuntimeException) {
      e.printStackTrace()
      arbiterContextHolder.addTurn(
        ArbiterContextHolder.Turn(
          memo = "Failed to perform action: ${e.message}",
          screenshotFileName = screenshotFileName
        )
      )
    } catch (e: IllegalStateException) {
      e.printStackTrace()
      arbiterContextHolder.addTurn(
        ArbiterContextHolder.Turn(
          memo = "Failed to perform action: ${e.message}",
          screenshotFileName = screenshotFileName
        )
      )
    }
  }
  return ExecuteCommandsOutput()
}

private fun step(
  stepInput: StepInput
): StepResult {
  val (arbiterContextHolder, agentCommandTypes, device, ai, decisionChain, executeCommandChain) = stepInput
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
  println("Inputs: ${arbiterContextHolder.prompt()}")
  val decisionInput = Ai.DecisionInput(
    arbiterContextHolder = arbiterContextHolder,
    dumpHierarchy = device.viewTreeString(),
    agentCommandTypes = agentCommandTypes,
    screenshotFileName = screenshotFileName
  )
  val decisionOutput = decisionChain(decisionInput)
  if (decisionOutput.agentCommands.size == 1 && decisionOutput.agentCommands.first() is GoalAchievedAgentCommand) {
    println("Goal achieved")
    return StepResult.GoalAchieved
  }
  executeCommandChain(
    ExecuteCommandsInput(
      decisionOutput = decisionOutput,
      arbiterContextHolder = arbiterContextHolder,
      screenshotFileName = screenshotFileName,
      device = device
    )
  )
  return StepResult.Continue
}
