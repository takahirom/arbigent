package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentProjectExecutionResult
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

public class FailedToArchiveException(message: String) : RuntimeException(message)

public class ArbigentProject(
  public val settings: ArbigentProjectSettings,
  initialScenarios: List<ArbigentScenario>,
  public val appSettings: ArbigentAppSettings
) {
  private val _scenarioAssignmentsFlow =
    MutableStateFlow<List<ArbigentScenarioAssignment>>(listOf())
  public val scenarioAssignmentsFlow: Flow<List<ArbigentScenarioAssignment>> =
    _scenarioAssignmentsFlow.asSharedFlow()

  public fun scenarioAssignments(): List<ArbigentScenarioAssignment> =
    _scenarioAssignmentsFlow.value

  public val scenarios: List<ArbigentScenario> get() = scenarioAssignments().map { it.scenario }

  init {
    _scenarioAssignmentsFlow.value = initialScenarios.map { scenario ->
      ArbigentScenarioAssignment(scenario, ArbigentScenarioExecutor())
    }
  }

  /**
   * Executes the given block within an MCP scope, connecting to the MCP server before execution
   * and closing the connection after execution.
   *
   * @param block The block of code to execute within the MCP scope.
   * @return The result of the block execution.
   */
  public suspend fun <T> mcpScope(block: suspend (mcpClient: MCPClient) -> T): T {
    val mcpClient = MCPClient(settings.mcpJson, appSettings)
    try {
      arbigentInfoLog("Connecting to MCP server...")
      mcpClient.connect()
      arbigentInfoLog("Connected to MCP server")

      return block(mcpClient)
    } finally {
      arbigentInfoLog("Closing MCP connection...")
      mcpClient.close()
      arbigentInfoLog("MCP connection closed")
    }
  }

  public suspend fun execute() {
    executeScenarios(leafScenarioAssignments().map { it.scenario })
  }

  public suspend fun executeScenarios(scenarios: List<ArbigentScenario>) {
    mcpScope { mcpClient ->
      scenarios.forEach { scenario ->
        arbigentInfoLog("Start scenario: $scenario")
        val scenarioExecutor =
          scenarioAssignments().first { it.scenario.id == scenario.id }.scenarioExecutor
        try {
          scenarioExecutor.execute(scenario, mcpClient)
        } catch (e: FailedToArchiveException) {
          arbigentErrorLog("Failed to archive: $scenario" + e.stackTraceToString())
        }
        arbigentDebugLog(scenarioExecutor.statusText())
      }
    }
  }

  public fun leafScenarioAssignments(): List<ArbigentScenarioAssignment> = scenarioAssignments()
    .filter { it.scenario.isLeaf }

  public suspend fun execute(scenario: ArbigentScenario) {
    mcpScope { mcpClient ->
      arbigentInfoLog("Start scenario: $scenario")
      val scenarioExecutor =
        scenarioAssignments().first { it.scenario.id == scenario.id }.scenarioExecutor
      scenarioExecutor.execute(scenario, mcpClient)
      arbigentDebugLog(scenarioExecutor.statusText())
    }
  }

  public fun cancel() {
    scenarioAssignments().forEach { (_, scenarioExecutor) ->
      scenarioExecutor.cancel()
    }
  }

  public fun isScenariosSuccessful(scenarios: List<ArbigentScenario>): Boolean {
    return scenarios
      .map { selectedScenario ->
        scenarioAssignments().first { it.scenario.id == selectedScenario.id }.scenarioExecutor
      }
      .all { it.isSuccessful() }
  }

  public fun getResult(selectedScenarios: List<ArbigentScenario> = scenarioAssignments().map { it.scenario }): ArbigentProjectExecutionResult {
    return ArbigentProjectExecutionResult(
      selectedScenarios.map { selectedScenario ->
        scenarioAssignments().first { selectedScenario.id == it.scenario.id }.getResult()
      }
    )
  }
}

public fun ArbigentProject(
  projectFileContent: ArbigentProjectFileContent,
  aiFactory: () -> ArbigentAi,
  deviceFactory: () -> ArbigentDevice,
  appSettings: ArbigentAppSettings
): ArbigentProject {
  return ArbigentProject(
    settings = projectFileContent.settings,
    initialScenarios = projectFileContent.scenarioContents.map {
      projectFileContent.scenarioContents.createArbigentScenario(
        projectSettings = projectFileContent.settings,
        scenario = it,
        aiFactory = aiFactory,
        deviceFactory = deviceFactory,
        aiDecisionCache = projectFileContent.settings.cacheStrategy.aiDecisionCacheStrategy.toCache(),
        appSettings = appSettings
      )
    },
    appSettings = appSettings
  )
}

public fun ArbigentProject(
  file: File,
  aiFactory: () -> ArbigentAi,
  deviceFactory: () -> ArbigentDevice,
  appSettings: ArbigentAppSettings
): ArbigentProject {
  val projectContentFileContent = ArbigentProjectSerializer().load(file)
  return ArbigentProject(projectContentFileContent, aiFactory, deviceFactory, appSettings)
}

public data class ArbigentScenario(
  val id: String,
  val agentTasks: List<ArbigentAgentTask>,
  val maxRetry: Int = 0,
  val maxStepCount: Int,
  val tags: ArbigentContentTags,
  val deviceFormFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
  // Leaf means that the scenario does not have any dependant scenarios.
  // Even if we only run leaf scenarios, we can run all scenarios.
  val isLeaf: Boolean,
  val cacheOptions: ArbigentScenarioCacheOptions? = null,
) {
  public fun goal(): String? {
    return agentTasks.lastOrNull()?.goal
  }
}

/**
 * [index] starts from 1
 */
public data class ArbigentShard(val index: Int, val total: Int) {
  init {
    require(total >= 1) { "Total shards must be at least 1" }
    require(index >= 1) { "Shard number must be at least 1" }
    require(index <= total) { "Shard number ($index) exceeds total ($total)" }
  }

  override fun toString(): String {
    if (total == 1) return ""
    return "Shard($index/$total)"
  }
}

@ArbigentInternalApi
public fun <T> List<T>.shard(
  shard: ArbigentShard
): List<T> {
  val (current, total) = shard

  if (current > total || total <= 0 || current <= 0) {
    return emptyList()
  }

  val size = this.size
  if (size == 0) return emptyList()

  val baseShardSize = size / total
  val remainder = size % total

  val shardSize = if (current <= remainder) baseShardSize + 1 else baseShardSize

  val start = if (current <= remainder) {
    (current - 1) * (baseShardSize + 1)
  } else {
    remainder * (baseShardSize + 1) + (current - 1 - remainder) * baseShardSize
  }

  if (start >= size) {
    return emptyList()
  }

  val end = start + shardSize
  val adjustedEnd = min(end, size)

  return subList(start, adjustedEnd)
}

@ArbigentInternalApi
public fun AiDecisionCacheStrategy.toCache(): ArbigentAiDecisionCache =
  when (val decisionStrategy = this) {
    is AiDecisionCacheStrategy.Disabled -> {
      ArbigentAiDecisionCache.Disabled
    }

    is AiDecisionCacheStrategy.InMemory -> {
      ArbigentAiDecisionCache.Memory.create(
        decisionStrategy.maxCacheSize,
        decisionStrategy.expireAfterWriteMs.milliseconds
      )
    }

    is AiDecisionCacheStrategy.Disk -> {
      ArbigentAiDecisionCache.Disk.create(
        maxSize = decisionStrategy.maxCacheSize
      )
    }
  }
