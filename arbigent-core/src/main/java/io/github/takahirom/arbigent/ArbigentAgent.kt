package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.ArbigentAgent.ExecuteCommandsInput
import io.github.takahirom.arbigent.ArbigentAgent.ExecuteCommandsOutput
import io.github.takahirom.arbigent.ArbigentAgent.StepInput
import io.github.takahirom.arbigent.ArbigentAgent.StepResult
import io.github.takahirom.arbigent.result.ArbigentAgentResult
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import maestro.MaestroException
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TakeScreenshotCommand
import java.io.File

public class ArbigentAgent(
  agentConfig: AgentConfig
) {
  private val ai = agentConfig.ai
  public val device: ArbigentDevice by lazy { agentConfig.deviceFactory() }
  private val interceptors: List<ArbigentInterceptor> = agentConfig.interceptors
  private val deviceFormFactor = agentConfig.deviceFormFactor

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
  private val stepChain: (StepInput) -> StepResult = stepInterceptors.foldRight(
    { input: StepInput -> step(input) },
    { interceptor, acc ->
      { input ->
        interceptor.intercept(input) { stepInput -> acc(stepInput) }
      }
    }
  )
  private val decisionInterceptors: List<ArbigentDecisionInterceptor> = interceptors
    .filterIsInstance<ArbigentDecisionInterceptor>()
  private val decisionChain: (ArbigentAi.DecisionInput) -> ArbigentAi.DecisionOutput =
    decisionInterceptors.foldRight(
      { input: ArbigentAi.DecisionInput -> ai.decideAgentCommands(input) },
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
        input.ai.assertImage(input)
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
  public val isGoalArchivedFlow: Flow<Boolean> = arbigentContextHolderStateFlow
    .flatMapLatest {
      it?.stepsFlow ?: flowOf()
    }
    .map { steps: List<ArbigentContextHolder.Step> ->
      steps.any { it.agentCommand is GoalAchievedAgentCommand }
    }
    .shareIn(coroutineScope, SharingStarted.WhileSubscribed(), 1)

  public fun isGoalArchived(): Boolean = arbigentContextHolderStateFlow
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
    arbigentDebugLog("Arbigent.execute agent.execute start $goal")
    try {
      _isRunningStateFlow.value = true
      currentGoalStateFlow.value = goal
      val arbigentContextHolder = ArbigentContextHolder(goal, maxStep)
      arbigentDebugLog("Setting new ArbigentContextHolder: $arbigentContextHolder")
      arbigentContextHolderStateFlow.value = arbigentContextHolder
      arbigentContextHistoryStateFlow.value += arbigentContextHolder
      val supportedAgentCommandTypes = agentCommandTypes.filter { it.isSupported(device.os()) }

      initializerChain(device)
      var stepRemain = maxStep
      while (stepRemain-- > 0 && isGoalArchived().not()) {
        val stepInput = StepInput(
          arbigentContextHolder = arbigentContextHolder,
          agentCommandTypes = supportedAgentCommandTypes,
          device = device,
          deviceFormFactor = deviceFormFactor,
          ai = ai,
          decisionChain = decisionChain,
          imageAssertionChain = imageAssertionChain,
          executeCommandChain = executeCommandChain
        )
        when (stepChain(stepInput)) {
          StepResult.GoalAchieved -> {
            isGoalArchivedFlow.first { it }
            arbigentDebugLog("Goal achieved: $goal stepRemain:$stepRemain")
            break
          }

          StepResult.Failed -> {
            arbigentDebugLog("Failed to run agent: $goal stepRemain:$stepRemain")
            break
          }

          StepResult.Continue -> {
            // continue
            delay(1000)
          }
        }
      }
    } catch (e: CancellationException) {
      arbigentDebugLog("Cancelled to run agent: $e")
    } catch (e: Exception) {
      arbigentDebugLog("Failed to run agent: $e")
      errorHandler(e)
    } finally {
      _isRunningStateFlow.value = false
      arbigentDebugLog("Arbigent.execute agent.execute end $goal")
    }
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
    return ArbigentAgentResult(
      goal = latestArbigentContext()?.goal ?: "",
      maxStep = 10,
      deviceFormFactor = deviceFormFactor,
      isGoalArchived = isGoalArchived(),
      steps = latestArbigentContext()?.steps()?.map {
        it.getResult()
      } ?: emptyList(),
    )
  }
}

public class AgentConfig(
  internal val interceptors: List<ArbigentInterceptor>,
  internal val ai: ArbigentAi,
  internal val deviceFactory: () -> ArbigentDevice,
  internal val deviceFormFactor: ArbigentScenarioDeviceFormFactor
) {
  public class Builder {
    private val interceptors = mutableListOf<ArbigentInterceptor>()
    private var deviceFactory: (() -> ArbigentDevice)? = null
    private var ai: ArbigentAi? = null
    private var deviceFormFactor: ArbigentScenarioDeviceFormFactor =
      ArbigentScenarioDeviceFormFactor.Mobile

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

    public fun build(): AgentConfig {
      return AgentConfig(
        interceptors = interceptors,
        ai = ai!!,
        deviceFactory = deviceFactory!!,
        deviceFormFactor = deviceFormFactor
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
  deviceFormFactor: ArbigentScenarioDeviceFormFactor,
  initializationMethods: List<ArbigentScenarioContent.InitializationMethods>,
  cleanupData: ArbigentScenarioContent.CleanupData,
  imageAssertions: List<ArbigentImageAssertion>
): AgentConfig.Builder = AgentConfigBuilder {
  deviceFormFactor(deviceFormFactor)
  initializationMethods.reversed().forEach { initializeMethod ->
    when (initializeMethod) {
      is ArbigentScenarioContent.InitializationMethods.Back -> {
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

      ArbigentScenarioContent.InitializationMethods.Noop -> {
      }

      is ArbigentScenarioContent.InitializationMethods.LaunchApp -> {
        addInterceptor(object : ArbigentInitializerInterceptor {
          override fun intercept(
            device: ArbigentDevice,
            chain: ArbigentInitializerInterceptor.Chain
          ) {
            device.executeCommands(
              listOf(
                MaestroCommand(
                  launchAppCommand = LaunchAppCommand(
                    appId = initializeMethod.packageName
                  )
                )
              )
            )
            device.waitForAppToSettle(initializeMethod.packageName)
            chain.proceed(device)
          }
        })
      }
    }
  }
  when (val cleanupData = cleanupData) {
    is ArbigentScenarioContent.CleanupData.Cleanup -> {
      addInterceptor(object : ArbigentInitializerInterceptor {
        override fun intercept(
          device: ArbigentDevice,
          chain: ArbigentInitializerInterceptor.Chain
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

    else -> {
      // do nothing
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

public interface ArbigentStepInterceptor : ArbigentInterceptor {
  public fun intercept(stepInput: StepInput, chain: Chain): StepResult
  public fun interface Chain {
    public fun proceed(stepInput: StepInput): StepResult
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
          memo = "Failed to perform action: ${e.message}. Please try other actions.",
          screenshotFilePath = screenshotFilePath
        )
      )
    } catch (e: StatusRuntimeException) {
      e.printStackTrace()
      arbigentContextHolder.addStep(
        ArbigentContextHolder.Step(
          memo = "Failed to perform action: ${e.message}. Please try other actions.",
          screenshotFilePath = screenshotFilePath
        )
      )
    } catch (e: IllegalStateException) {
      e.printStackTrace()
      arbigentContextHolder.addStep(
        ArbigentContextHolder.Step(
          memo = "Failed to perform action: ${e.message}. Please try other actions.",
          screenshotFilePath = screenshotFilePath
        )
      )
    }
  }
  return ExecuteCommandsOutput()
}

private fun step(
  stepInput: StepInput
): StepResult {
  val (arbigentContextHolder, agentCommandTypes, device, deviceFormFactor, ai, decisionChain, imageAssertionChain, executeCommandChain) = stepInput
  val screenshotFileID = System.currentTimeMillis().toString()
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
  } catch (e: Exception) {
    arbigentDebugLog("Failed to take screenshot: $e")
  }
  arbigentDebugLog("Arbigent step(): ${arbigentContextHolder.prompt()}")
  val screenshotFilePath =
    ArbigentDir.screenshotsDir.absolutePath + File.separator + "$screenshotFileID.png"
  val decisionInput = ArbigentAi.DecisionInput(
    arbigentContextHolder = arbigentContextHolder,
    formFactor = deviceFormFactor,
    elements = device.elements(),
    uiTreeStrings = device.viewTreeString(),
    focusedTreeString = if (deviceFormFactor.isTv()) {
      // It is important to get focused tree string for TV form factor
      device.focusedTreeString()
    } else {
      null
    },
    agentCommandTypes = agentCommandTypes,
    screenshotFilePath = screenshotFilePath
  )
  val decisionOutput = decisionChain(decisionInput)
  if (decisionOutput.agentCommands.any { it is GoalAchievedAgentCommand }) {
    val imageAssertionOutput = imageAssertionChain(
      ArbigentAi.ImageAssertionInput(
        ai = ai,
        arbigentContextHolder = arbigentContextHolder,
        screenshotFilePath = screenshotFilePath,
        // Added by interceptors
        assertions = listOf()
      )
    )
    imageAssertionOutput.results.forEach {
      arbigentContextHolder.addStep(
        ArbigentContextHolder.Step(
          memo = "Image assertion ${if (it.isPassed) "passed" else "failed"}. \nfulfillmentPercent:${it.fulfillmentPercent} \nprompt:${it.assertionPrompt} \nexplanation:${it.explanation}",
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
      arbigentContextHolder.addStep(decisionOutput.step)
    } else {
      imageAssertionOutput.results.filter { it.isPassed.not() }.forEach {
        arbigentContextHolder.addStep(
          ArbigentContextHolder.Step(
            memo = "Failed to reach the goal by image assertion. Image assertion prompt:${it.assertionPrompt}. explanation:${it.explanation}",
            screenshotFilePath = screenshotFilePath,
            aiRequest = decisionOutput.step.aiRequest,
            aiResponse = decisionOutput.step.aiResponse
          )
        )
      }
    }
  } else {
    arbigentContextHolder.addStep(decisionOutput.step)
  }
  if (arbigentContextHolder.steps().last().agentCommand is GoalAchievedAgentCommand) {
    arbigentDebugLog("Goal achieved: ${decisionInput.arbigentContextHolder.goal}")
    return StepResult.GoalAchieved
  }
  if (arbigentContextHolder.steps().last().agentCommand is FailedAgentCommand) {
    arbigentDebugLog("Failed to achieve the goal: ${decisionInput.arbigentContextHolder.goal}")
    return StepResult.Failed
  }
  executeCommandChain(
    ExecuteCommandsInput(
      decisionOutput = decisionOutput,
      arbigentContextHolder = arbigentContextHolder,
      screenshotFilePath = screenshotFilePath,
      device = device
    )
  )
  return StepResult.Continue
}
