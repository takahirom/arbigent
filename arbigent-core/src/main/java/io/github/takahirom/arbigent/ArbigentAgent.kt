package io.github.takahirom.arbigent

import com.mayakapps.kache.JavaFileKache
import com.mayakapps.kache.KacheStrategy
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import maestro.MaestroException
import maestro.orchestra.*
import org.mobilenativefoundation.store.cache5.CacheBuilder
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

public class ArbigentAgent(
  agentConfig: AgentConfig
) {
  private val ai = agentConfig.ai
  public val device: ArbigentDevice by lazy { agentConfig.deviceFactory() }
  private val interceptors: List<ArbigentInterceptor> = agentConfig.interceptors
  private val deviceFormFactor = agentConfig.deviceFormFactor
  private val prompt = agentConfig.prompt
  private val aiOptions = agentConfig.aiOptions

  private val executeInterceptors: List<ArbigentExecutionInterceptor> = interceptors
    .filterIsInstance<ArbigentExecutionInterceptor>()

  private val executeChain: suspend (ExecuteInput) -> ExecutionResult = { input ->
    var chain: suspend (ExecuteInput) -> ExecutionResult = { executeInput -> executeDefault(executeInput) }
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
  private val decisionChain: suspend (ArbigentAi.DecisionInput) -> ArbigentAi.DecisionOutput = { input ->
    var chain: suspend (ArbigentAi.DecisionInput) -> ArbigentAi.DecisionOutput = { decisionInput ->
      ArbigentGlobalStatus.onAi {
        ai.decideAgentActions(decisionInput)
      }
    }
    decisionInterceptors.reversed().forEach { interceptor ->
      val previousChain = chain
      chain = { currentInput ->
        interceptor.intercept(currentInput) { previousChain(it) }
      }
    }
    chain(input)
  }


  private val imageAssertionInterceptors: List<ArbigentImageAssertionInterceptor> = interceptors
    .filterIsInstance<ArbigentImageAssertionInterceptor>()
  private val imageAssertionChain: (ArbigentAi.ImageAssertionInput) -> ArbigentAi.ImageAssertionOutput =
    imageAssertionInterceptors.foldRight(
      { input: ArbigentAi.ImageAssertionInput ->
        val assertions = input.assertions
        if (assertions.isEmpty()) {
          return@foldRight ArbigentAi.ImageAssertionOutput(emptyList())
        }
        ArbigentGlobalStatus.onImageAssertion(assertions.assertionPromptSummary()) {
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
  private val executeActionsInterceptors: List<ArbigentExecuteActionsInterceptor> = interceptors
    .filterIsInstance<ArbigentExecuteActionsInterceptor>()
  private val executeActionChain: (ExecuteActionsInput) -> ExecuteActionsOutput =
    executeActionsInterceptors.foldRight(
      { input: ExecuteActionsInput -> executeActions(input) },
      { interceptor: ArbigentExecuteActionsInterceptor, acc: (ExecuteActionsInput) -> ExecuteActionsOutput ->
        { input ->
          interceptor.intercept(input) { executeActionsInput -> acc(executeActionsInput) }
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
      steps.any { it.agentAction is GoalAchievedAgentAction }
    }
    .shareIn(coroutineScope, SharingStarted.WhileSubscribed(), 1)

  public fun isGoalAchieved(): Boolean = arbigentContextHolderStateFlow
    .value
    ?.steps()
    ?.any { it.agentAction is GoalAchievedAgentAction }
    ?: false

  public suspend fun execute(
    agentTask: ArbigentAgentTask
  ) {
    execute(
      goal = agentTask.goal,
      maxStep = agentTask.maxStep,
      agentActionTypes = when (agentTask.deviceFormFactor) {
        ArbigentScenarioDeviceFormFactor.Mobile -> defaultAgentActionTypesForVisualMode()
        ArbigentScenarioDeviceFormFactor.Tv -> defaultAgentActionTypesForTvForVisualMode()
        else -> throw IllegalArgumentException("Unsupported device form factor: ${agentTask.deviceFormFactor}")
      }
    )
  }

  public suspend fun execute(
    goal: String,
    maxStep: Int = 10,
    agentActionTypes: List<AgentActionType> = defaultAgentActionTypesForVisualMode()
  ) {
    val executeInput = ExecuteInput(
      goal = goal,
      maxStep = maxStep,
      agentActionTypes = agentActionTypes,
      deviceFormFactor = deviceFormFactor,
      prompt = prompt,
      device = device,
      ai = ai,
      aiOptions = aiOptions,
      createContextHolder = { g, m ->
        ArbigentContextHolder(
          g,
          m,
          userPromptTemplate = UserPromptTemplate(prompt.userPromptTemplate)
        )
      },
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
      executeActionChain = executeActionChain
    )

    when (executeChain(executeInput)) {
      ExecutionResult.Success -> arbigentDebugLog("Execution succeeded.")
      is ExecutionResult.Failed -> arbigentDebugLog("Execution failed.")
      is ExecutionResult.Cancelled -> arbigentDebugLog("Execution cancelled.")
    }
  }

  public data class ExecuteInput(
    val goal: String,
    val maxStep: Int,
    val agentActionTypes: List<AgentActionType>,
    val deviceFormFactor: ArbigentScenarioDeviceFormFactor,
    val prompt: ArbigentPrompt,
    val device: ArbigentDevice,
    val ai: ArbigentAi,
    val aiOptions: ArbigentAiOptions?,
    val createContextHolder: (String, Int) -> ArbigentContextHolder,
    val addContextHolder: (ArbigentContextHolder) -> Unit,
    val updateIsRunning: (Boolean) -> Unit,
    val updateCurrentGoal: (String?) -> Unit,
    val initializerChain: (ArbigentDevice) -> Unit,
    val stepChain: suspend (StepInput) -> StepResult,
    val decisionChain: suspend (ArbigentAi.DecisionInput) -> ArbigentAi.DecisionOutput,
    val imageAssertionChain: (ArbigentAi.ImageAssertionInput) -> ArbigentAi.ImageAssertionOutput,
    val executeActionChain: (ExecuteActionsInput) -> ExecuteActionsOutput,
  )

  public sealed interface ExecutionResult {
    public object Success : ExecutionResult
    public data class Failed(val contextHolder: ArbigentContextHolder?) : ExecutionResult
    public data class Cancelled(val contextHolder: ArbigentContextHolder?) : ExecutionResult
  }

  public data class StepInput(
    val arbigentContextHolder: ArbigentContextHolder,
    val agentActionTypes: List<AgentActionType>,
    val device: ArbigentDevice,
    val deviceFormFactor: ArbigentScenarioDeviceFormFactor,
    val ai: ArbigentAi,
    val decisionChain: suspend (ArbigentAi.DecisionInput) -> ArbigentAi.DecisionOutput,
    val imageAssertionChain: (ArbigentAi.ImageAssertionInput) -> ArbigentAi.ImageAssertionOutput,
    val executeActionChain: (ExecuteActionsInput) -> ExecuteActionsOutput,
    val prompt: ArbigentPrompt,
    val aiOptions: ArbigentAiOptions?,
  )

  public sealed interface StepResult {
    public object GoalAchieved : StepResult
    public object Failed : StepResult
    public object Continue : StepResult
  }

  public data class ExecuteActionsInput(
    val stepId: String,
    val decisionOutput: ArbigentAi.DecisionOutput,
    val arbigentContextHolder: ArbigentContextHolder,
    val screenshotFilePath: String,
    val device: ArbigentDevice,
    val cacheKey: String,
    val elements: ArbigentElementList,
  )

  public class ExecuteActionsOutput


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
  internal val prompt: ArbigentPrompt,
  internal val aiOptions: ArbigentAiOptions?
) {
  public class Builder {
    private val interceptors = mutableListOf<ArbigentInterceptor>()
    private var deviceFactory: (() -> ArbigentDevice)? = null
    private var ai: ArbigentAi? = null
    private var deviceFormFactor: ArbigentScenarioDeviceFormFactor =
      ArbigentScenarioDeviceFormFactor.Mobile
    private var prompt: ArbigentPrompt = ArbigentPrompt()
    private var aiOptions: ArbigentAiOptions? = null

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

    public fun aiOptions(aiOptions: ArbigentAiOptions?) {
      this.aiOptions = aiOptions
    }

    public fun build(): AgentConfig {
      return AgentConfig(
        interceptors = interceptors,
        ai = ai!!,
        deviceFactory = deviceFactory!!,
        deviceFormFactor = deviceFormFactor,
        prompt = prompt,
        aiOptions = aiOptions
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
    builder.aiOptions(aiOptions)
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

public sealed interface ArbigentAiDecisionCache {
  public sealed interface Enabled : ArbigentAiDecisionCache {
    public suspend fun get(key: String): ArbigentAi.DecisionOutput?
    public suspend fun set(key: String, value: ArbigentAi.DecisionOutput)
    public suspend fun remove(key: String)
  }

  // FileCache
  public class Disk private constructor(
    private val cache: JavaFileKache
  ) : Enabled {
    private val json: Json = Json {
      ignoreUnknownKeys = true
      useArrayPolymorphism = true
    }

    public override suspend fun get(key: String): ArbigentAi.DecisionOutput? {
      val file = cache.get(key)
      arbigentInfoLog("AI-decision cache get with key: $key")
      if (file == null) {
        arbigentInfoLog("AI-decision cache miss with key: $key")
        return null
      }
      return json.decodeFromString<ArbigentAi.DecisionOutput>(file.readText())
    }

    public override suspend fun set(key: String, value: ArbigentAi.DecisionOutput) {
      arbigentInfoLog("AI-decision cache put with key: $key")
      cache.put(key, { file ->
        file.writeText(
          json.encodeToString(
            value
          )
        )
        true
      })
    }

    public override suspend fun remove(key: String) {
      arbigentInfoLog("AI-decision cache remove with key: $key")
      cache.remove(key)
    }

    public companion object {
      public fun create(
        maxSize: Long = 500 * 1024 * 1024, // 500MB
      ): Disk {
        return runBlocking {
          try {
            ArbigentFiles.cacheDir.mkdirs()
            val cache = JavaFileKache(directory = ArbigentFiles.cacheDir, maxSize = maxSize) {
              strategy = KacheStrategy.LRU
              this.maxSize = maxSize
            }
            Disk(cache)
          } catch (e: Exception) {
            errorHandler.invoke(e)
            arbigentErrorLog("Failed to create AI-decision cache: $e. Recovering...")
            ArbigentFiles.cacheDir.deleteRecursively()
            ArbigentFiles.cacheDir.mkdirs()
            val cache = JavaFileKache(directory = ArbigentFiles.cacheDir, maxSize = maxSize) {
              strategy = KacheStrategy.LRU
              this.maxSize = maxSize
            }
            Disk(cache)
          }
        }
      }
    }
  }

  public class Memory private constructor(
    private val cache: org.mobilenativefoundation.store.cache5.Cache<String, String>
  ) : Enabled {
    private val json: Json = Json {
      ignoreUnknownKeys = true
      useArrayPolymorphism = true
    }
    // This feature is experimental so we are logging the cache operations.

    public override suspend fun get(key: String): ArbigentAi.DecisionOutput? {
      val cache = cache.getIfPresent(key)
      arbigentInfoLog("AI-decision cache get with key: $key")
      if (cache == null) {
        arbigentInfoLog("AI-decision cache miss with key: $key")
        return null
      }
      return json.decodeFromString(cache)
    }

    public override suspend fun set(key: String, value: ArbigentAi.DecisionOutput) {
      arbigentInfoLog("AI-decision cache put with key: $key")
      cache.put(key, json.encodeToString(value))
    }

    public override suspend fun remove(key: String) {
      arbigentInfoLog("AI-decision cache remove with key: $key")
      cache.invalidate(key)
    }

    public companion object {
      public fun create(
        maxSize: Long = 100,
        expireAfterAccess: Duration = 24.hours
      ): Memory {
        val cache = CacheBuilder<String, String>()
          .maximumSize(maxSize)
          .expireAfterAccess(expireAfterAccess)
          .build()

        return Memory(cache)
      }
    }
  }

  public object Disabled : ArbigentAiDecisionCache
}

public fun AgentConfigBuilder(
  prompt: ArbigentPrompt,
  scenarioType: ArbigentScenarioType,
  deviceFormFactor: ArbigentScenarioDeviceFormFactor,
  initializationMethods: List<ArbigentScenarioContent.InitializationMethod>,
  imageAssertions: ArbigentImageAssertions,
  aiDecisionCache: ArbigentAiDecisionCache = ArbigentAiDecisionCache.Disabled,
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
                device.executeActions(
                  actions = listOf(
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
            device.executeActions(
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
            device.executeActions(
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
            device.executeActions(
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
  if (aiDecisionCache is ArbigentAiDecisionCache.Enabled) {
    addInterceptor(object : ArbigentDecisionInterceptor, ArbigentExecutionInterceptor {
      override suspend fun intercept(
        decisionInput: ArbigentAi.DecisionInput,
        chain: ArbigentDecisionInterceptor.Chain
      ): ArbigentAi.DecisionOutput {
        val cached = aiDecisionCache.get(decisionInput.cacheKey)
        if (cached != null) {
          arbigentInfoLog("AI-decision cache hit with view tree and prompt")
          return cached.copy(
            step = cached.step.copy(
              screenshotFilePath = decisionInput.screenshotFilePath
            )
          )
        }
        arbigentDebugLog("AI-decision cache miss with view tree and prompt")
        val output = chain.proceed(decisionInput)
        aiDecisionCache.set(
          decisionInput.cacheKey, output.copy(
            step = output.step.copy(
              cacheHit = true,
            )
          )
        )
        return output
      }

      override suspend fun intercept(
        executeInput: ExecuteInput,
        chain: ArbigentExecutionInterceptor.Chain
      ): ExecutionResult {
        val output: ExecutionResult = chain.proceed(executeInput)
        when (output) {
          is ExecutionResult.Failed -> {
            output.contextHolder?.let { contextHolder ->
              contextHolder.steps().forEach { step ->
                aiDecisionCache.remove(step.cacheKey)
              }
            }
          }

          is ExecutionResult.Cancelled -> {
            output.contextHolder?.let { contextHolder ->
              contextHolder.steps().forEach { step ->
                aiDecisionCache.remove(step.cacheKey)
              }
            }
          }

          ExecutionResult.Success -> {
          }
        }
        return output
      }
    })
  }
  if (imageAssertions.assertions.isNotEmpty()) {
    addInterceptor(object : ArbigentImageAssertionInterceptor {
      override fun intercept(
        imageAssertionInput: ArbigentAi.ImageAssertionInput,
        chain: ArbigentImageAssertionInterceptor.Chain
      ): ArbigentAi.ImageAssertionOutput {
        val output = chain.proceed(
          imageAssertionInput.copy(
            screenshotFilePaths = (1..imageAssertions.historyCount).mapNotNull { index ->
              val path: String? = if (index == 1) {
                imageAssertionInput.screenshotFilePaths.first()
              } else {
                imageAssertionInput.arbigentContextHolder.steps().reversed()
                  .map { it.screenshotFilePath }
                  .distinct()
                  .getOrNull(index - 1)
              }
              path
            },
            assertions = imageAssertionInput.assertions + imageAssertions
          )
        )
        return output
      }
    })
  }

  if (scenarioType.isExecution()) {
    addInterceptor(object : ArbigentDecisionInterceptor {
      override suspend fun intercept(
        decisionInput: ArbigentAi.DecisionInput,
        chain: ArbigentDecisionInterceptor.Chain
      ): ArbigentAi.DecisionOutput {
        return ArbigentAi.DecisionOutput(
          agentActions = listOf(
            GoalAchievedAgentAction()
          ),
          step = ArbigentContextHolder.Step(
            stepId = decisionInput.stepId,
            agentAction = GoalAchievedAgentAction(),
            screenshotFilePath = decisionInput.screenshotFilePath,
            cacheKey = decisionInput.cacheKey
          )
        )
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
  public suspend fun intercept(
    decisionInput: ArbigentAi.DecisionInput,
    chain: Chain
  ): ArbigentAi.DecisionOutput

  public fun interface Chain {
    public suspend fun proceed(decisionInput: ArbigentAi.DecisionInput): ArbigentAi.DecisionOutput
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

public interface ArbigentExecuteActionsInterceptor : ArbigentInterceptor {
  public fun intercept(
    executeActionsInput: ExecuteActionsInput,
    chain: Chain
  ): ExecuteActionsOutput

  public fun interface Chain {
    public fun proceed(executeActionsInput: ExecuteActionsInput): ExecuteActionsOutput
  }
}

public interface ArbigentExecutionInterceptor : ArbigentInterceptor {
  public suspend fun intercept(
    executeInput: ExecuteInput,
    chain: Chain
  ): ExecutionResult

  public fun interface Chain {
    public suspend fun proceed(executeInput: ExecuteInput): ExecutionResult
  }
}

public interface ArbigentStepInterceptor : ArbigentInterceptor {
  public suspend fun intercept(stepInput: StepInput, chain: Chain): StepResult
  public fun interface Chain {
    public suspend fun proceed(stepInput: StepInput): StepResult
  }
}

public fun defaultAgentActionTypesForVisualMode(): List<AgentActionType> {
  return listOf(
//    ClickWithIdAgentAction,
//    ClickWithTextAgentAction,
    ClickWithIndex,
    InputTextAgentAction,
    BackPressAgentAction,
    KeyPressAgentAction,
    ScrollAgentAction,
    WaitAgentAction,
    GoalAchievedAgentAction,
    FailedAgentAction
  )
}

public fun defaultAgentActionTypesForTvForVisualMode(): List<AgentActionType> {
  return listOf(
    DpadAutoFocusWithIndexAgentAction,
//    DpadAutoFocusWithIdAgentAction,
//    DpadAutoFocusWithTextAgentAction,
    DpadUpArrowAgentAction,
    DpadDownArrowAgentAction,
    DpadLeftArrowAgentAction,
    DpadRightArrowAgentAction,
    DpadCenterAgentAction,
    InputTextAgentAction,
    BackPressAgentAction,
    KeyPressAgentAction,
    WaitAgentAction,
    GoalAchievedAgentAction,
    FailedAgentAction
  )
}

private fun executeActions(
  executeActionsInput: ExecuteActionsInput,
): ExecuteActionsOutput {
  val (stepId, decisionOutput, arbigentContextHolder, screenshotFilePath, device) = executeActionsInput
  decisionOutput.agentActions.forEach { agentAction ->
    try {
      agentAction.runDeviceAction(
        ArbigentAgentAction.RunInput(
          device = device,
          elements = executeActionsInput.elements,
        )
      )
    } catch (e: MaestroException) {
      e.printStackTrace()
      arbigentContextHolder.addStep(
        ArbigentContextHolder.Step(
          stepId = stepId,
          feedback = "Failed to perform action: ${e.message}. Please try other actions.",
          cacheKey = executeActionsInput.cacheKey,
          screenshotFilePath = screenshotFilePath
        )
      )
    } catch (e: StatusRuntimeException) {
      e.printStackTrace()
      arbigentContextHolder.addStep(
        ArbigentContextHolder.Step(
          stepId = stepId,
          feedback = "Failed to perform action: ${e.message}. Please try other actions.",
          cacheKey = executeActionsInput.cacheKey,
          screenshotFilePath = screenshotFilePath
        )
      )
    } catch (e: IllegalStateException) {
      e.printStackTrace()
      arbigentContextHolder.addStep(
        ArbigentContextHolder.Step(
          stepId = stepId,
          feedback = "Failed to perform action: ${e.message}. Please try other actions.",
          cacheKey = executeActionsInput.cacheKey,
          screenshotFilePath = screenshotFilePath
        )
      )
    }
  }
  return ExecuteActionsOutput()
}

private suspend fun executeDefault(input: ExecuteInput): ExecutionResult {
  val nullableContextHolder: ArbigentContextHolder? = null
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
        agentActionTypes = input.agentActionTypes,
        device = input.device,
        deviceFormFactor = input.deviceFormFactor,
        ai = input.ai,
        decisionChain = input.decisionChain,
        imageAssertionChain = input.imageAssertionChain,
        executeActionChain = input.executeActionChain,
        prompt = input.prompt,
        aiOptions = input.aiOptions
      )
      when (input.stepChain(stepInput)) {
        StepResult.GoalAchieved -> break
        StepResult.Failed -> return ExecutionResult.Failed(contextHolder)
        StepResult.Continue -> {}
      }
      yield()
    }

    ArbigentGlobalStatus.onFinished()
    return if (contextHolder.isGoalAchieved()) {
      ExecutionResult.Success
    } else {
      ExecutionResult.Failed(contextHolder)
    }
  } catch (e: CancellationException) {
    ArbigentGlobalStatus.onCanceled()
    return ExecutionResult.Cancelled(nullableContextHolder)
  } catch (e: Exception) {
    errorHandler(e)
    ArbigentGlobalStatus.onError(e)
    return ExecutionResult.Failed(null)
  } finally {
    input.updateIsRunning(false)
    ArbigentGlobalStatus.onFinished()
  }
}

private suspend fun step(
  stepInput: StepInput
): StepResult {
  val contextHolder = stepInput.arbigentContextHolder
  arbigentDebugLog("step start: ${contextHolder.context()}")
  val actionTypes = stepInput.agentActionTypes
  val device = stepInput.device
  val deviceFormFactor = stepInput.deviceFormFactor
  val ai = stepInput.ai
  val decisionChain = stepInput.decisionChain
  val imageAssertionChain = stepInput.imageAssertionChain
  val executeActionChain = stepInput.executeActionChain

  val stepId = contextHolder.generateStepId()
  val elements = device.elements()
  for (it in 0..2) {
    try {
      device.executeActions(
        actions = listOf(
          MaestroCommand(
            takeScreenshotCommand = TakeScreenshotCommand(
              stepId
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
  val uiTreeHash = uiTreeStrings.optimizedTreeString.hashCode().toString().replace("-", "")
  val contextHash = contextHolder.context().hashCode().toString().replace("-", "")
  val cacheKey = "v${BuildConfig.VERSION_NAME}-uitree-${uiTreeHash}-context-${contextHash}"
  arbigentInfoLog("cacheKey: $cacheKey")
  val originalScreenshotFilePath =
    ArbigentFiles.screenshotsDir.absolutePath + File.separator + "$stepId.png"
  if (File(originalScreenshotFilePath).exists().not()) {
    arbigentErrorLog("Failed to take screenshot. originalScreenshotFilePath:$originalScreenshotFilePath")
    contextHolder.addStep(
      ArbigentContextHolder.Step(
        stepId = stepId,
        feedback = "Failed to take screenshot.",
        cacheKey = cacheKey,
        screenshotFilePath = originalScreenshotFilePath
      )
    )
    return StepResult.Failed
  }
  val imageFormat = stepInput.aiOptions?.imageFormat ?: ImageFormat.PNG
  val screenshotFilePath = if (imageFormat == ImageFormat.PNG) {
    originalScreenshotFilePath
  } else {
    val convertedScreenshotFilePath =
      ArbigentFiles.screenshotsDir.absolutePath + File.separator + "$stepId.webp"
    val originalFile = File(originalScreenshotFilePath)
    val originalImage = ImageIO.read(originalFile)
    ArbigentImageEncoder.saveImage(
      originalImage,
      convertedScreenshotFilePath,
      ImageFormat.WEBP
    )
    originalFile.delete()
    originalImage.flush()
    convertedScreenshotFilePath
  }
  val decisionJsonlFilePath =
    ArbigentFiles.jsonlsDir.absolutePath + File.separator + "$stepId.jsonl"
  val lastScreenshot = contextHolder.steps().lastOrNull()?.screenshotFilePath
  val newScreenshot = File(screenshotFilePath)
  if (detectStuckScreen(lastScreenshot, newScreenshot)) {
    arbigentDebugLog("Stuck screen detected.")
    contextHolder.addStep(
      ArbigentContextHolder.Step(
        stepId = stepId,
        feedback = "Failed to produce the intended outcome. The current screen is identical to the previous one. Please try other actions.",
        cacheKey = cacheKey,
        screenshotFilePath = screenshotFilePath
      )
    )
  }
  val decisionInput = ArbigentAi.DecisionInput(
    stepId = stepId,
    contextHolder = contextHolder,
    formFactor = deviceFormFactor,
    elements = elements,
    uiTreeStrings = uiTreeStrings,
    apiCallJsonLFilePath = decisionJsonlFilePath,
    focusedTreeString = if (deviceFormFactor.isTv()) {
      // It is important to get focused tree string for TV form factor
      device.focusedTreeString()
    } else {
      null
    },
    agentActionTypes = actionTypes,
    screenshotFilePath = screenshotFilePath,
    prompt = stepInput.prompt,
    cacheKey = cacheKey,
    aiOptions = stepInput.aiOptions
  )
  val decisionOutput = decisionChain(decisionInput)
  if (decisionOutput.agentActions.any { it is GoalAchievedAgentAction }) {
    val imageAssertionOutput = imageAssertionChain(
      ArbigentAi.ImageAssertionInput(
        ai = ai,
        arbigentContextHolder = contextHolder,
        screenshotFilePaths = listOf(screenshotFilePath),
        // Added by interceptors
        assertions = ArbigentImageAssertions()
      )
    )
    imageAssertionOutput.results.forEach {
      contextHolder.addStep(
        ArbigentContextHolder.Step(
          stepId = stepId,
          feedback = "Image assertion ${if (it.isPassed) "passed" else "failed"}. \nfulfillmentPercent:${it.fulfillmentPercent} \nprompt:${it.assertionPrompt} \nexplanation:${it.explanation}",
          screenshotFilePath = screenshotFilePath,
          aiRequest = decisionOutput.step.aiRequest,
          cacheKey = cacheKey,
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
            stepId = stepId,
            feedback = "Failed to reach the goal by image assertion. Image assertion prompt:${it.assertionPrompt}. explanation:${it.explanation}",
            screenshotFilePath = screenshotFilePath,
            aiRequest = decisionOutput.step.aiRequest,
            cacheKey = cacheKey,
            aiResponse = decisionOutput.step.aiResponse
          )
        )
      }
    }
  } else {
    contextHolder.addStep(decisionOutput.step)
  }
  if (contextHolder.steps().last().agentAction is GoalAchievedAgentAction) {
    return StepResult.GoalAchieved
  }
  if (contextHolder.steps().last().agentAction is FailedAgentAction) {
    return StepResult.Failed
  }
  executeActionChain(
    ExecuteActionsInput(
      stepId = stepId,
      decisionOutput = decisionOutput,
      elements = elements,
      arbigentContextHolder = contextHolder,
      screenshotFilePath = screenshotFilePath,
      device = device,
      cacheKey = cacheKey,
    )
  )
  return StepResult.Continue
}
