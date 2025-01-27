package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.ArbigentAgent.*
import io.github.takahirom.arbigent.result.ArbigentAgentResult
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import maestro.MaestroException
import maestro.orchestra.*
import java.io.File

public class ArbigentAgent(
  agentConfig: AgentConfig
) {
  private val ai = agentConfig.ai
  public val device: ArbigentDevice by lazy { agentConfig.deviceFactory() }
  private val interceptors: List<ArbigentInterceptor> = agentConfig.interceptors
  private val deviceFormFactor = agentConfig.deviceFormFactor
  private val prompt = agentConfig.prompt

  private val executeInterceptors: List<ArbigentExecuteInterceptor> = interceptors
    .filterIsInstance<ArbigentExecuteInterceptor>()

  private val executeChain: suspend (ExecuteInput) -> ExecuteResult = { input ->
    var chain: suspend (ExecuteInput) -> ExecuteResult = { executeInput -> executeDefault(executeInput) }
    executeInterceptors.reversed().forEach { interceptor ->
      val previousChain = chain
      chain = { currentInput ->
        interceptor.intercept(currentInput) { previousChain(it) }
      }
    }
    chain(input)
  }


  private val initializerInterceptors: List<ArbigentInitializerInterceptor> = interceptors
    .filterIsInstance<ArbigentInitializerInterceptor>()
  private val initializerChain: (ArbigentDevice) -> Unit = initializerInterceptors.foldRight(
    { device: ArbigentDevice ->
      // do nothing
    },
    { interceptor, acc ->
      { device ->
        interceptor.intercept(device) { d -> acc(d) }
      }
    }
  )
  private val stepInterceptors: List<ArbigentStepInterceptor> = interceptors
    .filterIsInstance<ArbigentStepInterceptor>()
  private val stepChain: suspend (StepInput) -> StepResult = { input ->
    var chain: suspend (StepInput) -> StepResult = { stepInput -> step(stepInput) }
    stepInterceptors.reversed().forEach { interceptor ->
      val previousChain = chain
      chain = { currentInput ->
        interceptor.intercept(currentInput) { previousChain(it) }
      }
    }
    chain(input)
  }
  private val decisionInterceptors: List<ArbigentDecisionInterceptor> = interceptors
    .filterIsInstance<ArbigentDecisionInterceptor>()
  private val decisionChain: (ArbigentAi.DecisionInput) -> ArbigentAi.DecisionOutput =
    decisionInterceptors.foldRight(
      { input: ArbigentAi.DecisionInput ->
        ArbigentGlobalStatus.onAi {
          ai.decideAgentCommands(input)
        }
      },
      { interceptor, acc ->
        { input ->
          interceptor.intercept(
            decisionInput = input,
            chain = { decisionInput -> acc(decisionInput) }
          )
        }
      }
    )
  private val imageAssertionInterceptors: List<ArbigentImageAssertionInterceptor> = interceptors
    .filterIsInstance<ArbigentImageAssertionInterceptor>()
  private val imageAssertionChain: (ArbigentAi.ImageAssertionInput) -> ArbigentAi.ImageAssertionOutput =
    imageAssertionInterceptors.foldRight(
      { input: ArbigentAi.ImageAssertionInput ->
        ArbigentGlobalStatus.onImageAssertion {
          input.ai.assertImage(input)
        }
      },
      { interceptor, acc ->
        { input ->
          interceptor.intercept(
            imageAssertionInput = input,
            chain = { imageAssertionInput -> acc(imageAssertionInput) }
          )
        }
      }
    )
  private val executeCommandsInterceptors: List<ArbigentExecuteCommandsInterceptor> = interceptors
    .filterIsInstance<ArbigentExecuteCommandsInterceptor>()
  private val executeCommandChain: (ExecuteCommandsInput) -> ExecuteCommandsOutput =
    executeCommandsInterceptors.foldRight(
      { input: ExecuteCommandsInput -> executeCommands(input) },
      { interceptor: ArbigentExecuteCommandsInterceptor, acc: (ExecuteCommandsInput) -> ExecuteCommandsOutput ->
        { input ->
          interceptor.intercept(input) { executeCommandsInput -> acc(executeCommandsInput) }
        }
      }
    )
  private val coroutineScope =
    CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob())
  private var job: Job? = null
  private val arbigentContextHistoryStateFlow: MutableStateFlow<List<ArbigentContextHolder>> =
    MutableStateFlow(listOf())
  public val latestArbigentContextFlow: Flow<ArbigentContextHolder?> =
    arbigentContextHistoryStateFlow
      .map { it.lastOrNull() }
      .shareIn(coroutineScope, SharingStarted.WhileSubscribed(), 1)

  public fun latestArbigentContext(): ArbigentContextHolder? =
    arbigentContextHistoryStateFlow.value.lastOrNull()

  private val arbigentContextHolderStateFlow: MutableStateFlow<ArbigentContextHolder?> =
    MutableStateFlow(null)
  private val _isRunningStateFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
  public val isRunningFlow: StateFlow<Boolean> = _isRunningStateFlow.asStateFlow()
  public fun isRunning(): Boolean = _isRunningStateFlow.value
  private val currentGoalStateFlow = MutableStateFlow<String?>(null)

  @OptIn(ExperimentalCoroutinesApi::class)
  public val isGoalAchievedFlow: Flow<Boolean> = arbigentContextHolderStateFlow
    .flatMapLatest {
      it?.stepsFlow ?: flowOf()
    }
    .map { steps: List<ArbigentContextHolder.Step> ->
      steps.any { it.agentCommand is GoalAchievedAgentCommand }
    }
    .shareIn(coroutineScope, SharingStarted.WhileSubscribed(), 1)

  public fun isGoalAchieved(): Boolean = arbigentContextHolderStateFlow
    .value
    ?.steps()
    ?.any { it.agentCommand is GoalAchievedAgentCommand }
    ?: false

  public suspend fun execute(
    agentTask: ArbigentAgentTask
  ) {
    execute(
      goal = agentTask.goal,
      maxStep = agentTask.maxStep,
      agentCommandTypes = when (agentTask.deviceFormFactor) {
        ArbigentScenarioDeviceFormFactor.Mobile -> defaultAgentCommandTypesForVisualMode()
        ArbigentScenarioDeviceFormFactor.Tv -> defaultAgentCommandTypesForTvForVisualMode()
        else -> throw IllegalArgumentException("Unsupported device form factor: ${agentTask.deviceFormFactor}")
      }
    )
  }

  public suspend fun execute(
    goal: String,
    maxStep: Int = 10,
    agentCommandTypes: List<AgentCommandType> = defaultAgentCommandTypesForVisualMode()
  ) {
    val executeInput = ExecuteInput(
      goal = goal,
      maxStep = maxStep,
      agentCommandTypes = agentCommandTypes,
      deviceFormFactor = deviceFormFactor,
      prompt = prompt,
      device = device,
      ai = ai,
      createContextHolder = { g, m -> ArbigentContextHolder(g, m) },
      addContextHolder = { holder ->
        arbigentContextHolderStateFlow.value = holder
        arbigentContextHistoryStateFlow.value += holder
      },
      updateIsRunning = { value -> _isRunningStateFlow.value = value },
      updateCurrentGoal = { value -> currentGoalStateFlow.value = value },
      initializerChain = initializerChain,
      stepChain = stepChain,
      decisionChain = decisionChain,
      imageAssertionChain = imageAssertionChain,
      executeCommandChain = executeCommandChain
    )

    when (executeChain(executeInput)) {
      ExecuteResult.Success -> arbigentDebugLog("Execution succeeded.")
      ExecuteResult.Failed -> arbigentDebugLog("Execution failed.")
      ExecuteResult.Cancelled -> arbigentDebugLog("Execution cancelled.")
    }
  }

  public data class ExecuteInput(
    val goal: String,
    val maxStep: Int,
    val agentCommandTypes: List<AgentCommandType>,
    val deviceFormFactor: ArbigentScenarioDeviceFormFactor,
    val prompt: ArbigentPrompt,
    val device: ArbigentDevice,
    val ai: ArbigentAi,
    val createContextHolder: (String, Int) -> ArbigentContextHolder,
    val addContextHolder: (ArbigentContextHolder) -> Unit,
    val updateIsRunning: (Boolean) -> Unit,
    val updateCurrentGoal: (String?) -> Unit,
    val initializerChain: (ArbigentDevice) -> Unit,
    val stepChain: suspend (StepInput) -> StepResult,
    val decisionChain: (ArbigentAi.DecisionInput) -> ArbigentAi.DecisionOutput,
    val imageAssertionChain: (ArbigentAi.ImageAssertionInput) -> ArbigentAi.ImageAssertionOutput,
    val executeCommandChain: (ExecuteCommandsInput) -> ExecuteCommandsOutput,
  )

  public sealed interface ExecuteResult {
    public object Success : ExecuteResult
    public object Failed : ExecuteResult
    public object Cancelled : ExecuteResult
  }

  public data class StepInput(
    val arbigentContextHolder: ArbigentContextHolder,
    val agentCommandTypes: List<AgentCommandType>,
    val device: ArbigentDevice,
    val deviceFormFactor: ArbigentScenarioDeviceFormFactor,
    val ai: ArbigentAi,
    val decisionChain: (ArbigentAi.DecisionInput) -> ArbigentAi.DecisionOutput,
    val imageAssertionChain: (ArbigentAi.ImageAssertionInput) -> ArbigentAi.ImageAssertionOutput,
    val executeCommandChain: (ExecuteCommandsInput) -> ExecuteCommandsOutput,
    val prompt: ArbigentPrompt,
  )

  public sealed interface StepResult {
    public object GoalAchieved : StepResult
    public object Failed : StepResult
    public object Continue : StepResult
  }

  public data class ExecuteCommandsInput(
    val decisionOutput: ArbigentAi.DecisionOutput,
    val arbigentContextHolder: ArbigentContextHolder,
    val screenshotFilePath: String,
    val device: ArbigentDevice,
  )

  public class ExecuteCommandsOutput


  public fun cancel() {
    job?.cancel()
    _isRunningStateFlow.value = false
  }

  public fun getResult(): ArbigentAgentResult {
    val context = latestArbigentContext()
    return ArbigentAgentResult(
      goal = context?.goal ?: "",
      maxStep = context?.maxStep ?: 10,
      startTimestamp = context?.startTimestamp,
      endTimestamp = context?.steps()?.lastOrNull()?.timestamp,
      deviceName = device.deviceName(),
      deviceFormFactor = deviceFormFactor,
      isGoalAchieved = isGoalAchieved(),
      steps = context?.steps()?.map {
        it.getResult()
      } ?: emptyList(),
    )
  }
}

public class AgentConfig(
  internal val interceptors: List<ArbigentInterceptor>,
  internal val ai: ArbigentAi,
  internal val deviceFactory: () -> ArbigentDevice,
  internal val deviceFormFactor: ArbigentScenarioDeviceFormFactor,
  internal val prompt: ArbigentPrompt
) {
  public class Builder {
    private val interceptors = mutableListOf<ArbigentInterceptor>()
    private var deviceFactory: (() -> ArbigentDevice)? = null
    private var ai: ArbigentAi? = null
    private var deviceFormFactor: ArbigentScenarioDeviceFormFactor =
      ArbigentScenarioDeviceFormFactor.Mobile
    private var prompt: ArbigentPrompt = ArbigentPrompt()

    public fun addInterceptor(interceptor: ArbigentInterceptor) {
      interceptors.add(0, interceptor)
    }

    public fun deviceFactory(deviceFactory: () -> ArbigentDevice) {
      this.deviceFactory = deviceFactory
    }

    public fun ai(ai: ArbigentAi) {
      this.ai = ai
    }

    public fun deviceFormFactor(deviceFormFactor: ArbigentScenarioDeviceFormFactor) {
      this.deviceFormFactor = deviceFormFactor
    }

    public fun prompt(prompt: ArbigentPrompt) {
      this.prompt = prompt
    }

    public fun build(): AgentConfig {
      return AgentConfig(
        interceptors = interceptors,
        ai = ai!!,
        deviceFactory = deviceFactory!!,
        deviceFormFactor = deviceFormFactor,
        prompt = prompt
      )
    }
  }

  public fun toBuilder(): Builder {
    val builder = Builder()
    interceptors.forEach {
      builder.addInterceptor(it)
    }
    builder.deviceFactory(deviceFactory)
    builder.ai(ai)
    return builder
  }
}

public fun AgentConfig(block: AgentConfig.Builder.() -> Unit = {}): AgentConfig {
  val builder = AgentConfig.Builder()
  builder.block()
  return builder.build()
}

public fun AgentConfigBuilder(block: AgentConfig.Builder.() -> Unit): AgentConfig.Builder {
  val builder = AgentConfig.Builder()
  builder.block()
  return builder
}


public fun AgentConfigBuilder(
  prompt: ArbigentPrompt,
  deviceFormFactor: ArbigentScenarioDeviceFormFactor,
  initializationMethods: List<ArbigentScenarioContent.InitializationMethod>,
  imageAssertions: List<ArbigentImageAssertion>
): AgentConfig.Builder = AgentConfigBuilder {
  deviceFormFactor(deviceFormFactor)
  prompt(prompt)
  initializationMethods.reversed().forEach { initializeMethod ->
    when (initializeMethod) {
      is ArbigentScenarioContent.InitializationMethod.Back -> {
        addInterceptor(object : ArbigentInitializerInterceptor {
          override fun intercept(
            device: ArbigentDevice,
            chain: ArbigentInitializerInterceptor.Chain
          ) {
            repeat(initializeMethod.times) {
              try {
                device.executeCommands(
                  commands = listOf(
                    MaestroCommand(
                      backPressCommand = BackPressCommand()
                    )
                  ),
                )
              } catch (e: Exception) {
                arbigentDebugLog("Failed to back press: $e")
              }
            }
            chain.proceed(device)
          }
        })
      }

      is ArbigentScenarioContent.InitializationMethod.Wait -> {
        addInterceptor(object : ArbigentInitializerInterceptor {
          override fun intercept(
            device: ArbigentDevice,
            chain: ArbigentInitializerInterceptor.Chain
          ) {
            try {
              Thread.sleep(initializeMethod.durationMs)
            } catch (e: Exception) {
              arbigentDebugLog("Failed to wait: $e")
            }
            chain.proceed(device)
          }
        })
      }

      ArbigentScenarioContent.InitializationMethod.Noop -> {
      }

      is ArbigentScenarioContent.InitializationMethod.LaunchApp -> {
        addInterceptor(object : ArbigentInitializerInterceptor {
          override fun intercept(
            device: ArbigentDevice,
            chain: ArbigentInitializerInterceptor.Chain
          ) {
            device.executeCommands(
              listOf(
                MaestroCommand(
                  launchAppCommand = LaunchAppCommand(
                    appId = initializeMethod.packageName,
                    launchArguments = initializeMethod.launchArguments.mapValues { (_, value) ->
                      value.value
                    }
                  )
                )
              )
            )
            device.waitForAppToSettle(initializeMethod.packageName)
            chain.proceed(device)
          }
        })
      }

      is ArbigentScenarioContent.InitializationMethod.CleanupData -> {
        addInterceptor(object : ArbigentInitializerInterceptor {
          override fun intercept(
            device: ArbigentDevice,
            chain: ArbigentInitializerInterceptor.Chain
          ) {
            device.executeCommands(
              listOf(
                MaestroCommand(
                  clearStateCommand = ClearStateCommand(
                    appId = initializeMethod.packageName
                  )
                )
              )
            )
            chain.proceed(device)
          }
        })
      }

      is ArbigentScenarioContent.InitializationMethod.OpenLink -> {
        addInterceptor(object : ArbigentInitializerInterceptor {
          override fun intercept(
            device: ArbigentDevice,
            chain: ArbigentInitializerInterceptor.Chain
          ) {
            device.executeCommands(
              listOf(
                MaestroCommand(
                  openLinkCommand = OpenLinkCommand(
                    link = initializeMethod.link
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
  if (imageAssertions.isNotEmpty()) {
    addInterceptor(object : ArbigentImageAssertionInterceptor {
      override fun intercept(
        imageAssertionInput: ArbigentAi.ImageAssertionInput,
        chain: ArbigentImageAssertionInterceptor.Chain
      ): ArbigentAi.ImageAssertionOutput {
        val output = chain.proceed(
          imageAssertionInput.copy(
            assertions = imageAssertionInput.assertions + imageAssertions
          )
        )
        return output
      }
    })
  }
}

public interface ArbigentInterceptor

public interface ArbigentInitializerInterceptor : ArbigentInterceptor {
  public fun intercept(device: ArbigentDevice, chain: Chain)
  public fun interface Chain {
    public fun proceed(device: ArbigentDevice)
  }
}

public interface ArbigentDecisionInterceptor : ArbigentInterceptor {
  public fun intercept(
    decisionInput: ArbigentAi.DecisionInput,
    chain: Chain
  ): ArbigentAi.DecisionOutput

  public fun interface Chain {
    public fun proceed(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput
  }
}

public interface ArbigentImageAssertionInterceptor : ArbigentInterceptor {
  public fun intercept(
    imageAssertionInput: ArbigentAi.ImageAssertionInput,
    chain: Chain
  ): ArbigentAi.ImageAssertionOutput

  public fun interface Chain {
    public fun proceed(imageAssertionInput: ArbigentAi.ImageAssertionInput): ArbigentAi.ImageAssertionOutput
  }
}

public interface ArbigentExecuteCommandsInterceptor : ArbigentInterceptor {
  public fun intercept(
    executeCommandsInput: ExecuteCommandsInput,
    chain: Chain
  ): ExecuteCommandsOutput

  public fun interface Chain {
    public fun proceed(executeCommandsInput: ExecuteCommandsInput): ExecuteCommandsOutput
  }
}

public interface ArbigentExecuteInterceptor : ArbigentInterceptor {
  public suspend fun intercept(
    executeInput: ExecuteInput,
    chain: Chain
  ): ExecuteResult

  public fun interface Chain {
    public suspend fun proceed(executeInput: ExecuteInput): ExecuteResult
  }
}

public interface ArbigentStepInterceptor : ArbigentInterceptor {
  public suspend fun intercept(stepInput: StepInput, chain: Chain): StepResult
  public fun interface Chain {
    public suspend fun proceed(stepInput: StepInput): StepResult
  }
}

public fun defaultAgentCommandTypesForVisualMode(): List<AgentCommandType> {
  return listOf(
//    ClickWithIdAgentCommand,
//    ClickWithTextAgentCommand,
    ClickWithIndex,
    InputTextAgentCommand,
    BackPressAgentCommand,
    KeyPressAgentCommand,
    ScrollAgentCommand,
    WaitAgentCommand,
    GoalAchievedAgentCommand,
    FailedAgentCommand
  )
}

public fun defaultAgentCommandTypesForTvForVisualMode(): List<AgentCommandType> {
  return listOf(
    DpadAutoFocusWithIndexAgentCommand,
//    DpadAutoFocusWithIdAgentCommand,
//    DpadAutoFocusWithTextAgentCommand,
    DpadUpArrowAgentCommand,
    DpadDownArrowAgentCommand,
    DpadLeftArrowAgentCommand,
    DpadRightArrowAgentCommand,
    DpadCenterAgentCommand,
    InputTextAgentCommand,
    BackPressAgentCommand,
    KeyPressAgentCommand,
    WaitAgentCommand,
    GoalAchievedAgentCommand,
    FailedAgentCommand
  )
}

private fun executeCommands(
  executeCommandsInput: ExecuteCommandsInput,
): ExecuteCommandsOutput {
  val (decisionOutput, arbigentContextHolder, screenshotFilePath, device) = executeCommandsInput
  decisionOutput.agentCommands.forEach { agentCommand ->
    try {
      agentCommand.runDeviceCommand(device)
    } catch (e: MaestroException) {
      e.printStackTrace()
      arbigentContextHolder.addStep(
        ArbigentContextHolder.Step(
          feedback = "Failed to perform action: ${e.message}. Please try other actions.",
          screenshotFilePath = screenshotFilePath
        )
      )
    } catch (e: StatusRuntimeException) {
      e.printStackTrace()
      arbigentContextHolder.addStep(
        ArbigentContextHolder.Step(
          feedback = "Failed to perform action: ${e.message}. Please try other actions.",
          screenshotFilePath = screenshotFilePath
        )
      )
    } catch (e: IllegalStateException) {
      e.printStackTrace()
      arbigentContextHolder.addStep(
        ArbigentContextHolder.Step(
          feedback = "Failed to perform action: ${e.message}. Please try other actions.",
          screenshotFilePath = screenshotFilePath
        )
      )
    }
  }
  return ExecuteCommandsOutput()
}

private suspend fun executeDefault(input: ExecuteInput): ExecuteResult {
  try {
    input.updateIsRunning(true)
    input.updateCurrentGoal(input.goal)
    val contextHolder = input.createContextHolder(input.goal, input.maxStep)
    input.addContextHolder(contextHolder)

    ArbigentGlobalStatus.onInitializing {
      input.initializerChain(input.device)
    }

    var stepRemain = input.maxStep
    while (stepRemain-- > 0 && !contextHolder.isGoalAchieved()) {
      val stepInput = StepInput(
        arbigentContextHolder = contextHolder,
        agentCommandTypes = input.agentCommandTypes,
        device = input.device,
        deviceFormFactor = input.deviceFormFactor,
        ai = input.ai,
        decisionChain = input.decisionChain,
        imageAssertionChain = input.imageAssertionChain,
        executeCommandChain = input.executeCommandChain,
        prompt = input.prompt
      )
      when (input.stepChain(stepInput)) {
        StepResult.GoalAchieved -> break
        StepResult.Failed -> return ExecuteResult.Failed
        StepResult.Continue -> {}
      }
      yield()
    }

    ArbigentGlobalStatus.onFinished()
    return if (contextHolder.isGoalAchieved()) {
      ExecuteResult.Success
    } else {
      ExecuteResult.Failed
    }
  } catch (e: CancellationException) {
    ArbigentGlobalStatus.onCanceled()
    return ExecuteResult.Cancelled
  } catch (e: Exception) {
    errorHandler(e)
    ArbigentGlobalStatus.onError(e)
    return ExecuteResult.Failed
  } finally {
    input.updateIsRunning(false)
    ArbigentGlobalStatus.onFinished()
  }
}

private suspend fun step(
  stepInput: StepInput
): StepResult {
  val contextHolder = stepInput.arbigentContextHolder
  arbigentDebugLog("step start: ${contextHolder.prompt()}")
  val commandTypes = stepInput.agentCommandTypes
  val device = stepInput.device
  val deviceFormFactor = stepInput.deviceFormFactor
  val ai = stepInput.ai
  val decisionChain = stepInput.decisionChain
  val imageAssertionChain = stepInput.imageAssertionChain
  val executeCommandChain = stepInput.executeCommandChain

  val screenshotFileID = System.currentTimeMillis().toString()
  val elements = device.elements()
  for (it in 0..2) {
    try {
      device.executeCommands(
        commands = listOf(
          MaestroCommand(
            takeScreenshotCommand = TakeScreenshotCommand(
              screenshotFileID
            )
          ),
        ),
      )
      break
    } catch (e: StatusRuntimeException) {
      arbigentDebugLog("Failed to take screenshot: $e retry:$it")
      Thread.sleep(1000)
    }
  }
  val uiTreeStrings = device.viewTreeString()
  val screenshotFilePath =
    ArbigentFiles.screenshotsDir.absolutePath + File.separator + "$screenshotFileID.png"
  val lastScreenshot = contextHolder.steps().lastOrNull()?.screenshotFilePath
  val newScreenshot = File(screenshotFilePath)
  if (detectStuckScreen(lastScreenshot, newScreenshot)) {
    arbigentDebugLog("Stuck screen detected.")
    contextHolder.addStep(
      ArbigentContextHolder.Step(
        feedback = "Failed to produce the intended outcome. The current screen is identical to the previous one. Please try other actions.",
        screenshotFilePath = screenshotFilePath
      )
    )
  }
  val decisionInput = ArbigentAi.DecisionInput(
    contextHolder = contextHolder,
    formFactor = deviceFormFactor,
    elements = elements,
    uiTreeStrings = uiTreeStrings,
    focusedTreeString = if (deviceFormFactor.isTv()) {
      // It is important to get focused tree string for TV form factor
      device.focusedTreeString()
    } else {
      null
    },
    agentCommandTypes = commandTypes,
    screenshotFilePath = screenshotFilePath,
    prompt = stepInput.prompt,
  )
  val decisionOutput = decisionChain(decisionInput)
  if (decisionOutput.agentCommands.any { it is GoalAchievedAgentCommand }) {
    val imageAssertionOutput = imageAssertionChain(
      ArbigentAi.ImageAssertionInput(
        ai = ai,
        arbigentContextHolder = contextHolder,
        screenshotFilePath = screenshotFilePath,
        // Added by interceptors
        assertions = listOf()
      )
    )
    imageAssertionOutput.results.forEach {
      contextHolder.addStep(
        ArbigentContextHolder.Step(
          feedback = "Image assertion ${if (it.isPassed) "passed" else "failed"}. \nfulfillmentPercent:${it.fulfillmentPercent} \nprompt:${it.assertionPrompt} \nexplanation:${it.explanation}",
          screenshotFilePath = screenshotFilePath,
          aiRequest = decisionOutput.step.aiRequest,
          aiResponse = decisionOutput.step.aiResponse
        )
      )
    }
    if (imageAssertionOutput.results.all {
        it.isPassed
      }) {
      // All assertions are passed
      contextHolder.addStep(decisionOutput.step)
    } else {
      imageAssertionOutput.results.filter { it.isPassed.not() }.forEach {
        contextHolder.addStep(
          ArbigentContextHolder.Step(
            feedback = "Failed to reach the goal by image assertion. Image assertion prompt:${it.assertionPrompt}. explanation:${it.explanation}",
            screenshotFilePath = screenshotFilePath,
            aiRequest = decisionOutput.step.aiRequest,
            aiResponse = decisionOutput.step.aiResponse
          )
        )
      }
    }
  } else {
    contextHolder.addStep(decisionOutput.step)
  }
  if (contextHolder.steps().last().agentCommand is GoalAchievedAgentCommand) {
    return StepResult.GoalAchieved
  }
  if (contextHolder.steps().last().agentCommand is FailedAgentCommand) {
    return StepResult.Failed
  }
  executeCommandChain(
    ExecuteCommandsInput(
      decisionOutput = decisionOutput,
      arbigentContextHolder = contextHolder,
      screenshotFilePath = screenshotFilePath,
      device = device
    )
  )
  return StepResult.Continue
}
