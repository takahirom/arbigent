package com.github.takahirom.arbiter

import com.github.takahirom.arbiter.ArbiterAgent.ExecuteCommandsInput
import com.github.takahirom.arbiter.ArbiterAgent.ExecuteCommandsOutput
import com.github.takahirom.arbiter.ArbiterAgent.StepInput
import com.github.takahirom.arbiter.ArbiterAgent.StepResult
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import maestro.MaestroException
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.WaitForAnimationToEndCommand

class ArbiterAgent(
  agentConfig: AgentConfig
) {
  private val ai = agentConfig.ai
  private val device = agentConfig.device
  private val interceptors: List<ArbiterInterceptor> = agentConfig.interceptors
  private val deviceFormFactor = agentConfig.deviceFormFactor

  private val decisionInterceptors: List<ArbiterDecisionInterceptor> = interceptors
    .filterIsInstance<ArbiterDecisionInterceptor>()
  private val decisionChain: (ArbiterAi.DecisionInput) -> ArbiterAi.DecisionOutput =
    decisionInterceptors.foldRight(
      { input: ArbiterAi.DecisionInput -> ai.decideWhatToDo(input) },
      { interceptor, acc ->
        { input ->
          interceptor.intercept(
            decisionInput = input,
            chain = { decisionInput -> acc(decisionInput) }
          )
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
  private val initializerChain: (ArbiterDevice) -> Unit = initializerInterceptors.foldRight(
    { device: ArbiterDevice ->
      // do nothing
    },
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
  private val coroutineScope =
    CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob())
  private var job: Job? = null
  private val arbiterContextHistoryStateFlow: MutableStateFlow<List<ArbiterContextHolder>> =
    MutableStateFlow(listOf())
  val latestArbiterContextStateFlow: StateFlow<ArbiterContextHolder?> =
    arbiterContextHistoryStateFlow
      .map { it.lastOrNull() }
      .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), null)

  fun latestArbiterContext(): ArbiterContextHolder? =
    arbiterContextHistoryStateFlow.value.lastOrNull()

  private val arbiterContextHolderStateFlow: MutableStateFlow<ArbiterContextHolder?> =
    MutableStateFlow(null)
  private val _isRunningStateFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
  val isRunningStateFlow: StateFlow<Boolean> = _isRunningStateFlow.asStateFlow()
  private val currentGoalStateFlow = MutableStateFlow<String?>(null)
  val isArchivedStateFlow = arbiterContextHolderStateFlow
    .flatMapLatest {
      it?.steps ?: flowOf()
    }
    .map { steps: List<ArbiterContextHolder.Step> ->
      steps.any { it.agentCommand is GoalAchievedAgentCommand }
    }
    .stateIn(coroutineScope, SharingStarted.Lazily, false)

  suspend fun waitUntilFinished() {
    isRunningStateFlow.first { !it }
  }

  suspend fun execute(
    agentTask: ArbiterScenarioExecutor.ArbiterAgentTask
  ) {
    execute(
      goal = agentTask.goal,
      maxStep = agentTask.maxStep,
      agentCommandTypes = when (agentTask.deviceFormFactor) {
        ArbiterScenarioDeviceFormFactor.Mobile -> defaultAgentCommandTypes()
        ArbiterScenarioDeviceFormFactor.Tv -> defaultAgentCommandTypesForTv()
      }
    )
  }

  suspend fun execute(
    goal: String,
    maxStep: Int = 10,
    agentCommandTypes: List<AgentCommandType> = defaultAgentCommandTypes()
  ) {
    arbiterDebugLog("Arbiter.execute agent.execute start $goal")
    try {
      _isRunningStateFlow.value = true
      currentGoalStateFlow.value = goal
      val arbiterContextHolder = ArbiterContextHolder(goal)
      arbiterDebugLog("Setting new ArbiterContextHolder: $arbiterContextHolder")
      arbiterContextHolderStateFlow.value = arbiterContextHolder
      arbiterContextHistoryStateFlow.value += arbiterContextHolder

      initializerChain(device)
      var stepRemain = maxStep
      while (stepRemain-- > 0 && isArchivedStateFlow.value.not()) {
        val stepInput = StepInput(
          arbiterContextHolder = arbiterContextHolder,
          agentCommandTypes = agentCommandTypes,
          device = device,
          deviceFormFactor = deviceFormFactor,
          ai = ai,
          decisionChain = decisionChain,
          executeCommandChain = executeCommandChain
        )
        when (stepChain(stepInput)) {
          StepResult.GoalAchieved -> {
            _isRunningStateFlow.value = false
            isArchivedStateFlow.first { it }
            break
          }

          StepResult.Continue -> {
            // continue
            delay(1000)
          }
        }
      }
      _isRunningStateFlow.value = false
    } catch (e: Exception) {
      arbiterDebugLog("Failed to run agent: $e")
      e.printStackTrace()
      _isRunningStateFlow.value = false
    } finally {
      arbiterDebugLog("Arbiter.execute agent.execute end $goal")
    }
  }

  data class StepInput(
    val arbiterContextHolder: ArbiterContextHolder,
    val agentCommandTypes: List<AgentCommandType>,
    val device: ArbiterDevice,
    val deviceFormFactor: ArbiterScenarioDeviceFormFactor,
    val ai: ArbiterAi,
    val decisionChain: (ArbiterAi.DecisionInput) -> ArbiterAi.DecisionOutput,
    val executeCommandChain: (ExecuteCommandsInput) -> ExecuteCommandsOutput,
  )

  sealed interface StepResult {
    object GoalAchieved : StepResult
    object Continue : StepResult
  }

  data class ExecuteCommandsInput(
    val decisionOutput: ArbiterAi.DecisionOutput,
    val arbiterContextHolder: ArbiterContextHolder,
    val screenshotFileName: String,
    val device: ArbiterDevice,
  )

  class ExecuteCommandsOutput


  fun cancel() {
    job?.cancel()
    _isRunningStateFlow.value = false
  }
}

class AgentConfig(
  val interceptors: List<ArbiterInterceptor>,
  val ai: ArbiterAi,
  val device: ArbiterDevice,
  val deviceFormFactor: ArbiterScenarioDeviceFormFactor
) {
  class Builder {
    private val interceptors = mutableListOf<ArbiterInterceptor>()
    private var device: ArbiterDevice? = null
    private var ai: ArbiterAi? = null
    private var deviceFormFactor: ArbiterScenarioDeviceFormFactor =
      ArbiterScenarioDeviceFormFactor.Mobile

    fun addInterceptor(interceptor: ArbiterInterceptor) {
      interceptors.add(0, interceptor)
    }

    fun device(device: ArbiterDevice) {
      this.device = device
    }

    fun ai(ai: ArbiterAi) {
      this.ai = ai
    }

    fun deviceFormFactor(deviceFormFactor: ArbiterScenarioDeviceFormFactor) {
      this.deviceFormFactor = deviceFormFactor
    }

    fun build(): AgentConfig {
      return AgentConfig(
        interceptors = interceptors,
        ai = ai!!,
        device = device!!,
        deviceFormFactor = deviceFormFactor
      )
    }
  }

  fun toBuilder(): Builder {
    val builder = Builder()
    interceptors.forEach {
      builder.addInterceptor(it)
    }
    builder.device(device)
    builder.ai(ai)
    return builder
  }
}

fun AgentConfig(block: AgentConfig.Builder.() -> Unit = {}): AgentConfig {
  val builder = AgentConfig.Builder()
  builder.block()
  return builder.build()
}

fun AgentConfigBuilder(block: AgentConfig.Builder.() -> Unit): AgentConfig.Builder {
  val builder = AgentConfig.Builder()
  builder.block()
  return builder
}


fun AgentConfigBuilder(
  deviceFormFactor: ArbiterScenarioDeviceFormFactor,
  initializeMethods: ArbiterScenarioContent.InitializeMethods,
  cleanupData: ArbiterScenarioContent.CleanupData
) = AgentConfigBuilder {
  deviceFormFactor(deviceFormFactor)
  when (val method = initializeMethods) {
    ArbiterScenarioContent.InitializeMethods.Back -> {
      addInterceptor(object : ArbiterInitializerInterceptor {
        override fun intercept(
          device: ArbiterDevice,
          chain: ArbiterInitializerInterceptor.Chain
        ) {
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
              arbiterDebugLog("Failed to back press: $e")
            }
          }
          chain.proceed(device)
        }
      })
    }

    ArbiterScenarioContent.InitializeMethods.Noop -> {
    }

    is ArbiterScenarioContent.InitializeMethods.OpenApp -> {
      addInterceptor(object : ArbiterInitializerInterceptor {
        override fun intercept(
          device: ArbiterDevice,
          chain: ArbiterInitializerInterceptor.Chain
        ) {
          device.executeCommands(
            listOf(
              MaestroCommand(
                launchAppCommand = LaunchAppCommand(
                  appId = method.packageName
                )
              )
            )
          )
          device.executeCommands(
            listOf(
              MaestroCommand(
                waitForAnimationToEndCommand = WaitForAnimationToEndCommand(
                  timeout = 100
                )
              )
            )
          )
          chain.proceed(device)
        }
      })
    }
  }
  when (val cleanupData = cleanupData) {
    ArbiterScenarioContent.CleanupData.Noop -> {
    }

    is ArbiterScenarioContent.CleanupData.Cleanup -> {
      addInterceptor(object : ArbiterInitializerInterceptor {
        override fun intercept(
          device: ArbiterDevice,
          chain: ArbiterInitializerInterceptor.Chain
        ) {
          device.executeCommands(
            listOf(
              MaestroCommand(
                clearStateCommand = ClearStateCommand(
                  appId = cleanupData.packageName
                )
              )
            )
          )
          chain.proceed(device)
        }
      })
    }
  }
}

interface ArbiterInterceptor

interface ArbiterInitializerInterceptor : ArbiterInterceptor {
  fun intercept(device: ArbiterDevice, chain: Chain)
  fun interface Chain {
    fun proceed(device: ArbiterDevice)
  }
}

interface ArbiterDecisionInterceptor : ArbiterInterceptor {
  fun intercept(decisionInput: ArbiterAi.DecisionInput, chain: Chain): ArbiterAi.DecisionOutput

  fun interface Chain {
    fun proceed(decisionInput: ArbiterAi.DecisionInput): ArbiterAi.DecisionOutput
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

fun defaultAgentCommandTypesForTv(): List<AgentCommandType> {
  return listOf(
    DpadUpArrowAgentCommand,
    DpadDownArrowAgentCommand,
    DpadLeftArrowAgentCommand,
    DpadRightArrowAgentCommand,
    DpadCenterAgentCommand,
    InputTextAgentCommand,
    BackPressAgentCommand,
    KeyPressAgentCommand,
    GoalAchievedAgentCommand
  )
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
      arbiterContextHolder.addStep(
        ArbiterContextHolder.Step(
          memo = "Failed to perform action: ${e.message}. Please try other actions.",
          screenshotFileName = screenshotFileName
        )
      )
    } catch (e: StatusRuntimeException) {
      e.printStackTrace()
      arbiterContextHolder.addStep(
        ArbiterContextHolder.Step(
          memo = "Failed to perform action: ${e.message}. Please try other actions.",
          screenshotFileName = screenshotFileName
        )
      )
    } catch (e: IllegalStateException) {
      e.printStackTrace()
      arbiterContextHolder.addStep(
        ArbiterContextHolder.Step(
          memo = "Failed to perform action: ${e.message}. Please try other actions.",
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
  val (arbiterContextHolder, agentCommandTypes, device, deviceFormFactor, ai, decisionChain, executeCommandChain) = stepInput
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
    arbiterDebugLog("Failed to take screenshot: $e")
  }
  arbiterDebugLog("Arbiter step(): ${arbiterContextHolder.prompt()}")
  val decisionInput = ArbiterAi.DecisionInput(
    arbiterContextHolder = arbiterContextHolder,
    dumpHierarchy = device.viewTreeString(),
    focusedTreeString = if (deviceFormFactor.isTv()) {
      // It is important to get focused tree string for TV form factor
      device.focusedTreeString()
    } else {
      null
    },
    agentCommandTypes = agentCommandTypes,
    screenshotFileName = screenshotFileName
  )
  val decisionOutput = decisionChain(decisionInput)
  arbiterContextHolder.addStep(decisionOutput.step)
  if (decisionOutput.agentCommands.size == 1 && decisionOutput.agentCommands.first() is GoalAchievedAgentCommand) {
    arbiterDebugLog("Goal achieved")
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
