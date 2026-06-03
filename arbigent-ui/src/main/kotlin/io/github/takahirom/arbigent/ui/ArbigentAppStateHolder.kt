package io.github.takahirom.arbigent.ui

import io.github.takahirom.arbigent.*
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import io.github.takahirom.arbigent.result.StepFeedback
import io.github.takahirom.arbigent.result.StepFeedbackEvent
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID
import kotlin.collections.firstOrNull

@OptIn(ArbigentInternalApi::class)
class ArbigentAppStateHolder(
  private val aiFactory: () -> ArbigentAi,
  val deviceFactory: (ArbigentAvailableDevice) -> ArbigentDevice = { avaiableDevice ->
    ArbigentGlobalStatus.onConnect {
      kotlinx.coroutines.runBlocking { avaiableDevice.connectToDevice() }
    }
  },
  val availableDeviceListFactory: (ArbigentDeviceOs) -> List<ArbigentAvailableDevice> = { os ->
    fetchAvailableDevicesByOs(os)
  }
) {
  private val _fixedScenariosFlow = MutableStateFlow<List<FixedScenario>>(emptyList())
  val fixedScenariosFlow: StateFlow<List<FixedScenario>> = _fixedScenariosFlow.asStateFlow()

  fun addFixedScenario(scenario: FixedScenario) {
    _fixedScenariosFlow.value = _fixedScenariosFlow.value + scenario
  }

  fun removeFixedScenario(fixedScenarioId: String) {
    // First remove all initialization methods that reference this scenario
    cleanupInitializationMethodReferences(fixedScenarioId)
    
    // Then remove the scenario itself
    _fixedScenariosFlow.value = _fixedScenariosFlow.value.filter { it.id != fixedScenarioId }
  }
  
  private fun cleanupInitializationMethodReferences(scenarioId: String) {
    allScenarioStateHoldersStateFlow.value.forEach { scenarioStateHolder ->
      val methods = scenarioStateHolder.initializationMethodStateFlow.value
      val cleanedMethods = methods.map { method ->
        if (method is ArbigentScenarioContent.InitializationMethod.MaestroYaml && 
            method.scenarioId == scenarioId) {
          // Replace with Noop instead of removing to maintain indices
          ArbigentScenarioContent.InitializationMethod.Noop
        } else {
          method
        }
      }
      
      // Update the initialization methods if any changes were made
      if (cleanedMethods != methods) {
        scenarioStateHolder.setInitializationMethods(cleanedMethods)
      }
    }
  }

  fun updateFixedScenario(scenario: FixedScenario) {
    _fixedScenariosFlow.value = _fixedScenariosFlow.value.map { 
      if (it.id == scenario.id) scenario else it 
    }
  }

  fun getFixedScenarioById(scenarioId: String): FixedScenario? {
    return _fixedScenariosFlow.value.find { it.id == scenarioId }
  }

  fun getScenarioReferences(scenarioId: String): List<Pair<ArbigentScenarioStateHolder, Int>> {
    val references = mutableListOf<Pair<ArbigentScenarioStateHolder, Int>>()
    
    allScenarioStateHoldersStateFlow.value.forEach { scenarioStateHolder ->
      scenarioStateHolder.initializationMethodStateFlow.value.forEachIndexed { index, method ->
        if (method is ArbigentScenarioContent.InitializationMethod.MaestroYaml && 
            method.scenarioId == scenarioId) {
          references.add(scenarioStateHolder to index)
        }
      }
    }
    
    return references
  }

  // Store the current initialization method that needs to be updated
  private val _currentInitializationMethod = MutableStateFlow<Pair<ArbigentScenarioStateHolder, Int>?>(null)

  fun showFixedScenariosDialog() {
    projectDialogState.value = ProjectDialogState.ShowFixedScenariosDialog
  }

  // This function is called from Scenario.kt when the user clicks on the scenario title or browse button
  fun onShowFixedScenariosDialogWithContext(scenarioStateHolder: ArbigentScenarioStateHolder, index: Int) {
    _currentInitializationMethod.value = scenarioStateHolder to index
    showFixedScenariosDialog()
  }

  fun updateInitializationMethod(scenarioId: String) {
    val (scenarioStateHolder, index) = _currentInitializationMethod.value ?: return

    scenarioStateHolder.onInitializationMethodChanged(
      index,
      ArbigentScenarioContent.InitializationMethod.MaestroYaml(
        scenarioId = scenarioId
      )
    )
  }
  // AppSettings for working directory
  val appSettingsStateHolder = AppSettingsStateHolder()
  val appSettings get() = appSettingsStateHolder.appSettings
  val devicesStateHolder = DevicesStateHolder(availableDeviceListFactory)

  sealed interface DeviceConnectionState {
    data object NotConnected : DeviceConnectionState
    data class Connected(val device: ArbigentDevice) : DeviceConnectionState

    fun isConnected(): Boolean = this is Connected
  }

  sealed interface ProjectDialogState {
    data object NotSelected : ProjectDialogState
    data object LoadProjectContent : ProjectDialogState
    data object SaveProjectContent : ProjectDialogState
    data object SaveProjectResult : ProjectDialogState
    data object ShowProjectSettings : ProjectDialogState
    data object ShowGenerateScenarioDialog : ProjectDialogState
    data object ShowFixedScenariosDialog : ProjectDialogState
  }

  val deviceConnectionState: MutableStateFlow<DeviceConnectionState> =
    MutableStateFlow(DeviceConnectionState.NotConnected)
  val projectDialogState: MutableStateFlow<ProjectDialogState> =
    MutableStateFlow(ProjectDialogState.NotSelected)
  private val projectStateFlow = MutableStateFlow<ArbigentProject?>(null)
  private val allScenarioStateHoldersStateFlow: MutableStateFlow<List<ArbigentScenarioStateHolder>> =
    MutableStateFlow(listOf())
  val sortedScenariosAndDepthsStateFlow: StateFlow<List<Pair<ArbigentScenarioStateHolder, Int>>> =
    allScenarioStateHoldersStateFlow
      .flatMapLatest { scenarios: List<ArbigentScenarioStateHolder> ->
        combine(scenarios.map { scenario ->
          scenario.dependencyScenarioStateHolderStateFlow
            .map { scenario to it }
        }) { list ->
          list
        }
      }
      .map {
        val result = sortedScenarioAndDepth(it.map { it.first })
        arbigentDebugLog("sortedScenariosAndDepthsStateFlow: ${it.map { it.first.goal }} -> ${result.map { it.first.goal }}")
        result
      }
      .stateIn(
        scope = CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob()),
        started = SharingStarted.WhileSubscribed(),
        initialValue = emptyList()
      )
  val promptFlow = MutableStateFlow(ArbigentPrompt())
  val tagManager = ArbigentTagManager()
  val cacheStrategyFlow = MutableStateFlow(CacheStrategy())
  val aiOptionsFlow = MutableStateFlow<ArbigentAiOptions?>(null)
  val mcpJsonFlow = MutableStateFlow("{}")
  val mcpServerNamesFlow: StateFlow<List<String>> = mcpJsonFlow
    .map { json -> parseMcpServerNames(json) }
    .stateIn(
      scope = CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob()),
      started = SharingStarted.Eagerly,
      initialValue = emptyList()
    )
  val defaultDeviceFormFactorFlow =
    MutableStateFlow<ArbigentScenarioDeviceFormFactor>(ArbigentScenarioDeviceFormFactor.Unspecified)
  val additionalActionsFlow = MutableStateFlow<List<String>?>(null)
  val decisionCache = cacheStrategyFlow
    .map {
      val decisionCacheStrategy = it.aiDecisionCacheStrategy
      decisionCacheStrategy.toCache()
    }
    .stateIn(
      scope = CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob()),
      started = SharingStarted.Eagerly,
      initialValue = ArbigentAiDecisionCache.Disabled
    )

  fun sortedScenariosAndDepths() = sortedScenarioAndDepth(allScenarioStateHoldersStateFlow.value)

  val selectedScenarioIndex: MutableStateFlow<Int> = MutableStateFlow(0)
  private val coroutineScope =
    CoroutineScope(ArbigentCoroutinesDispatcher.dispatcher + SupervisorJob())

  val stepFeedbacks: MutableStateFlow<Set<StepFeedback>> = MutableStateFlow(setOf())
  
  init {
    // Watch for appSettings changes and recreate project if needed
    coroutineScope.launch {
      snapshotFlow { appSettings.variables }
        .distinctUntilChanged()
        .drop(1) // Skip initial value
        .collect {
          if (projectStateFlow.value != null) {
            recreateProject()
          }
        }
    }
  }

  fun addSubScenario(parent: ArbigentScenarioStateHolder) {
    val scenarioStateHolder = ArbigentScenarioStateHolder(tagManager = tagManager).apply {
      onAddAsSubScenario(parent)
    }
    allScenarioStateHoldersStateFlow.value += scenarioStateHolder
    selectedScenarioIndex.value =
      sortedScenariosAndDepths().indexOfFirst { it.first.id == scenarioStateHolder.id }
  }

  fun addScenario() {
    val scenarioStateHolder = ArbigentScenarioStateHolder(tagManager = tagManager)
    allScenarioStateHoldersStateFlow.value += scenarioStateHolder
    selectedScenarioIndex.value =
      sortedScenariosAndDepths().indexOfFirst { it.first.id == scenarioStateHolder.id }
  }

  private var job: Job? = null

  fun runAll() {
    job?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    recreateProject()
    job = coroutineScope.launch {
      projectStateFlow.value?.scenarios
        ?.filter { it.isLeaf }
        ?.forEach { scenario ->
          selectedScenarioIndex.value =
            sortedScenariosAndDepths().indexOfFirst { it.first.id == scenario.id }
          executeScenario(scenario)
          delay(10)
        }
    }
  }

  fun run(scenarioStateHolder: ArbigentScenarioStateHolder) {
    job?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    recreateProject()
    job = coroutineScope.launch {
      executeScenario(scenarioStateHolder.createScenario(allScenarioStateHoldersStateFlow.value))
      selectedScenarioIndex.value =
        sortedScenariosAndDepths().indexOfFirst { it.first.id == scenarioStateHolder.id }
    }
  }

  fun runDebug(scenarioStateHolder: ArbigentScenarioStateHolder) {
    job?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    recreateProject()
    job = coroutineScope.launch {
      // Create a regular scenario and then modify it to only include the current task
      val scenario = scenarioStateHolder.createScenario(allScenarioStateHoldersStateFlow.value)
      // Modify the scenario to only include the last task (the current scenario's task)
      val lastTask = scenario.agentTasks.last()
      val debugScenario = scenario.copy(
        agentTasks = listOf(lastTask)
      )
      executeScenario(debugScenario)
      selectedScenarioIndex.value =
        sortedScenariosAndDepths().indexOfFirst { it.first.id == scenarioStateHolder.id }
    }
  }


  private fun recreateProject() {
    projectStateFlow.value?.cancel()
    val arbigentProject = ArbigentProject(
      settings = ArbigentProjectSettings(
        prompt = promptFlow.value,
        cacheStrategy = cacheStrategyFlow.value,
        aiOptions = aiOptionsFlow.value,
        mcpJson = mcpJsonFlow.value,
        deviceFormFactor = defaultDeviceFormFactorFlow.value,
        additionalActions = additionalActionsFlow.value
      ),
      initialScenarios = allScenarioStateHoldersStateFlow.value.map { scenario ->
        scenario.createScenario(allScenarioStateHoldersStateFlow.value)
      },
      appSettings = appSettings
    )
    projectStateFlow.value = arbigentProject
    allScenarioStateHoldersStateFlow.value.forEach { scenarioStateHolder ->
      arbigentProject.scenarioAssignments().firstOrNull { (scenario, _) ->
        scenario.id == scenarioStateHolder.id
      }
        ?.let {
          scenarioStateHolder.onExecute(it.scenarioExecutor)
        }
    }
  }

  private val deviceCache = mutableMapOf<ArbigentAvailableDevice, ArbigentDevice>()

  private fun ArbigentScenarioStateHolder.createScenario(allScenarioStateHolder: List<ArbigentScenarioStateHolder>) =
    allScenarioStateHolder.map { it.createArbigentScenarioContent() }
      .createArbigentScenario(
        projectSettings = ArbigentProjectSettings(
          prompt = this@ArbigentAppStateHolder.promptFlow.value,
          cacheStrategy = this@ArbigentAppStateHolder.cacheStrategyFlow.value,
          aiOptions = this@ArbigentAppStateHolder.aiOptionsFlow.value,
          mcpJson = this@ArbigentAppStateHolder.mcpJsonFlow.value,
          deviceFormFactor = this@ArbigentAppStateHolder.defaultDeviceFormFactorFlow.value,
          additionalActions = this@ArbigentAppStateHolder.additionalActionsFlow.value
        ),
        scenario = createArbigentScenarioContent(),
        aiFactory = aiFactory,
        deviceFactory = {
          val selectedDevice = devicesStateHolder.selectedDevice.value!!
          deviceCache.getOrPut(selectedDevice) {
            this@ArbigentAppStateHolder.deviceFactory(selectedDevice)
          }
        },
        aiDecisionCache = decisionCache.value,
        appSettings = appSettings,
        fixedScenarios = this@ArbigentAppStateHolder._fixedScenariosFlow.value
      )

  private fun sortedScenarioAndDepth(allScenarios: List<ArbigentScenarioStateHolder>): List<Pair<ArbigentScenarioStateHolder, Int>> {
    // Build dependency map using goals as keys
    val dependentMap =
      mutableMapOf<ArbigentScenarioStateHolder, MutableList<ArbigentScenarioStateHolder>>()
    val rootScenarios = mutableListOf<ArbigentScenarioStateHolder>()

    allScenarios.forEach { scenario ->
      allScenarios.firstOrNull { it == scenario.dependencyScenarioStateHolderStateFlow.value }
        ?.let {
          if (it == scenario) {
            rootScenarios.add(scenario)
            return@forEach
          }
          dependentMap.getOrPut(it) { mutableListOf() }.add(scenario)
        } ?: run {
        rootScenarios.add(scenario)
      }
    }
    dependentMap.forEach { (k, v) ->
      if (v.isEmpty()) {
        rootScenarios.add(k)
      }
    }

    // Assign depths using BFS
    val result = mutableListOf<Pair<ArbigentScenarioStateHolder, Int>>()
    fun dfs(scenario: ArbigentScenarioStateHolder, depth: Int) {
      result.add(scenario to depth)
      dependentMap[scenario]?.forEach {
        dfs(it, depth + 1)
      }
    }
    rootScenarios.forEach {
      dfs(it, 0)
    }
    return result
  }

  fun runAllFailed() {
    job?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    recreateProject()
    job = coroutineScope.launch {
      sortedScenariosAndDepthsStateFlow.value.map { it.first }.filter { scenario ->
        !scenario.isGoalAchieved()
      }
        .map { scenarioStateHolder ->
          scenarioStateHolder.createScenario(allScenarioStateHoldersStateFlow.value)
        }
        .filter { it.isLeaf }
        .forEach { scenario ->
          selectedScenarioIndex.value =
            sortedScenariosAndDepths().indexOfFirst { it.first.id == scenario.id }
          executeScenario(scenario)
        }
    }
  }

  private suspend fun executeScenario(scenario: ArbigentScenario) {
    try {
      projectStateFlow.value?.execute(scenario)
    } catch (e: FailedToArchiveException) {
      arbigentDebugLog("Failed to archive scenario: ${e.message}")
    }
  }

  private val arbigentProjectSerializer = ArbigentProjectSerializer()

  // Track dirty state for unsaved changes warning
  // Initialized to the initial content so edits to new (never-saved) projects are detected.
  private var lastSavedYaml: String? = getCurrentContentAsYaml()

  private fun getCurrentProjectFileContent(): ArbigentProjectFileContent {
    val sortedScenarios = sortedScenariosAndDepthsStateFlow.value.map { it.first }
    return ArbigentProjectFileContent(
      settings = ArbigentProjectSettings(
        prompt = promptFlow.value,
        cacheStrategy = cacheStrategyFlow.value,
        aiOptions = aiOptionsFlow.value,
        mcpJson = mcpJsonFlow.value,
        deviceFormFactor = defaultDeviceFormFactorFlow.value,
        additionalActions = additionalActionsFlow.value
      ),
      scenarioContents = sortedScenarios.map { it.createArbigentScenarioContent() },
      fixedScenarios = _fixedScenariosFlow.value
    )
  }

  private fun getCurrentContentAsYaml(): String {
    return arbigentProjectSerializer.encodeToString(getCurrentProjectFileContent())
  }

  fun hasUnsavedChanges(): Boolean {
    val saved = lastSavedYaml ?: return false
    return getCurrentContentAsYaml() != saved
  }

  fun saveProjectContents(file: File?) {
    if (file == null) {
      return
    }
    val content = getCurrentProjectFileContent()
    arbigentProjectSerializer.save(
      projectFileContent = content,
      file = file
    )
    lastSavedYaml = arbigentProjectSerializer.encodeToString(content)
  }

  fun loadProjectContents(file: File?) {
    if (file == null) {
      return
    }
    val projectFile = ArbigentProjectSerializer().load(file)
    val scenarios = projectFile.scenarioContents
    val arbigentScenarioStateHolders = scenarios.map { scenarioContent ->
      ArbigentScenarioStateHolder(
        id = scenarioContent.id,
        tagManager = tagManager,
      ).apply {
        load(scenarioContent)
      }
    }
    scenarios.forEachIndexed { index, scenario ->
      arbigentScenarioStateHolders[index].dependencyScenarioStateHolderStateFlow.value =
        arbigentScenarioStateHolders.firstOrNull {
          it.id == scenario.dependencyId
        }
    }
    promptFlow.value = projectFile.settings.prompt
    cacheStrategyFlow.value = projectFile.settings.cacheStrategy
    aiOptionsFlow.value = projectFile.settings.aiOptions
    mcpJsonFlow.value = projectFile.settings.mcpJson
    defaultDeviceFormFactorFlow.value = projectFile.settings.deviceFormFactor
    additionalActionsFlow.value = projectFile.settings.additionalActions
    _fixedScenariosFlow.value = projectFile.fixedScenarios
    projectStateFlow.value = ArbigentProject(
      settings = projectFile.settings,
      initialScenarios = arbigentScenarioStateHolders.map {
        it.createScenario(
          arbigentScenarioStateHolders
        )
      },
      appSettings = appSettings
    )
    allScenarioStateHoldersStateFlow.value = arbigentScenarioStateHolders
    // Use the loaded content directly to avoid format differences
    lastSavedYaml = arbigentProjectSerializer.encodeToString(projectFile)
  }

  fun cancel() {
    job?.cancel()
  }

  fun close() {
    job?.cancel()
    projectStateFlow.value?.cancel()
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    deviceConnectionState.value = DeviceConnectionState.NotConnected
    ArbigentGlobalStatus.onDisconnect {
      deviceCache.values.forEach { it.close() }
    }
  }

  fun onClickConnect(devicesStateHolder: DevicesStateHolder) {
    coroutineScope.launch {
      val currentConnection = deviceConnectionState.value
      if (currentConnection is DeviceConnectionState.Connected) {
        currentConnection.device.close()
      }
      if (devicesStateHolder.selectedDevice.value == null) {
        devicesStateHolder.onSelectedDeviceChanged(devicesStateHolder.devices.value.firstOrNull())
      }
      val selectedDevice = devicesStateHolder.selectedDevice.value!!
      var device = deviceCache.getOrPut(selectedDevice) {
        deviceFactory(devicesStateHolder.selectedDevice.value!!)
      }
      if (device.isClosed()) {
        device = deviceFactory(devicesStateHolder.selectedDevice.value!!)
        deviceCache[selectedDevice] = device
      }
      deviceConnectionState.value = DeviceConnectionState.Connected(device)
    }
  }

  fun removeScenario(scenario: ArbigentScenarioStateHolder) {
    scenario.arbigentScenarioExecutorStateFlow.value?.cancel()
    allScenarioStateHoldersStateFlow.value =
      allScenarioStateHoldersStateFlow.value.filter { it != scenario }
    val removedScenarioDependency = scenario.dependencyScenarioStateHolderStateFlow.value
    allScenarioStateHoldersStateFlow.value.forEach {
      if (it.dependencyScenarioStateHolderStateFlow.value == scenario) {
        it.dependencyScenarioStateHolderStateFlow.value = removedScenarioDependency
      }
    }
  }

  fun saveProjectResult(file: File?) {
    if (file == null) {
      return
    }
    projectStateFlow.value?.getResult()?.copy(stepFeedbacks = stepFeedbacks.value.toList())?.let {
      file.mkdirs()
      arbigentProjectSerializer.save(it, File(file, "result.yml"))
      ArbigentHtmlReport().saveReportHtml(file.absolutePath, it)
    }
  }

  fun onPromptChanged(prompt: ArbigentPrompt) {
    promptFlow.value = prompt
  }

  fun onCacheStrategyChanged(strategy: CacheStrategy) {
    cacheStrategyFlow.value = strategy
  }

  fun onAiOptionsChanged(options: ArbigentAiOptions?) {
    if (options == ArbigentAiOptions()) {
      aiOptionsFlow.value = null
      return
    }
    aiOptionsFlow.value = options
  }

  fun onMcpJsonChanged(json: String) {
    mcpJsonFlow.value = json
  }

  fun onAppUiStructureChanged(structure: String) {
    promptFlow.value = promptFlow.value.copy(appUiStructure = structure)
  }

  fun onScenarioGenerationCustomInstructionChanged(instruction: String) {
    promptFlow.value = promptFlow.value.copy(scenarioGenerationCustomInstruction = instruction)
  }

  fun onDefaultDeviceFormFactorChanged(formFactor: ArbigentScenarioDeviceFormFactor) {
    defaultDeviceFormFactorFlow.value = formFactor
  }

  fun onAdditionalActionsChanged(actions: List<String>?) {
    additionalActionsFlow.value = actions
  }

  fun scenarioCountById(newScenarioId: String): Int {
    return allScenarioStateHoldersStateFlow.value.count { it.id == newScenarioId }
  }

  fun onStepFeedback(feedback: StepFeedbackEvent) {
    when (feedback) {
      is StepFeedback -> {
        stepFeedbacks.value += feedback
      }

      is StepFeedbackEvent.RemoveBad -> {
        stepFeedbacks.value = stepFeedbacks.value.filter {
          !(it is StepFeedback.Bad && it.stepId == feedback.stepId)
        }.toSet()
      }

      is StepFeedbackEvent.RemoveGood -> {
        stepFeedbacks.value = stepFeedbacks.value.filter {
          !(it is StepFeedback.Good && it.stepId == feedback.stepId)
        }.toSet()
      }
    }
  }

  fun onGenerateScenarios(
    scenariosToGenerate: String,
    appUiStructure: String,
    customInstruction: String,
    useExistingScenarios: Boolean
  ) {
    val ai = getAi()
    val requestUuid = UUID.randomUUID().toString()
    val generatedScenarios = ai.generateScenarios(
      ArbigentAi.ScenarioGenerationInput(
        requestUuid = requestUuid,
        scenariosToGenerate = scenariosToGenerate,
        appUiStructure = appUiStructure,
        customInstruction = customInstruction,
        scenariosToBeUsedAsContext = if (useExistingScenarios) {
          allScenarioStateHoldersStateFlow.value.map {
            it.createArbigentScenarioContent()
          }
        } else {
          emptyList()
        }
      )
    )
    allScenarioStateHoldersStateFlow.value.forEach { it.cancel() }
    val scenarios = allScenarioStateHoldersStateFlow.value + generatedScenarios.scenarios.map {
      ArbigentScenarioStateHolder(tagManager = tagManager).apply {
        load(it)
        isNewlyGenerated.value = true
      }
    }
    generatedScenarios.scenarios.forEach { generatedScenario ->
      val scenario = scenarios.firstOrNull { it.id == generatedScenario.id }
      val dependencyScenario = scenarios.firstOrNull { it.id == generatedScenario.dependencyId }
      scenario?.dependencyScenarioStateHolderStateFlow?.value = dependencyScenario
    }
    allScenarioStateHoldersStateFlow.value = scenarios
  }

  fun getAi(): ArbigentAi {
    return aiFactory()
  }
}

private fun parseMcpServerNames(json: String): List<String> {
  return try {
    val jsonParser = Json { ignoreUnknownKeys = true }
    val jsonElement = jsonParser.parseToJsonElement(json)
    val mcpServers = jsonElement.jsonObject["mcpServers"]?.jsonObject
    mcpServers?.keys?.toList() ?: emptyList()
  } catch (e: Exception) {
    emptyList()
  }
}
