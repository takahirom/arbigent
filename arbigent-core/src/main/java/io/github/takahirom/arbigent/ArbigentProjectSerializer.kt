package io.github.takahirom.arbigent

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMultiLineStringStyle
import com.charleskorn.kaml.MultiLineStringStyle
import io.github.takahirom.arbigent.result.ArbigentProjectExecutionResult
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

public interface FileSystem {
  public fun readText(inputStream: InputStream): String {
    return inputStream.readAllBytes().decodeToString()
  }

  public fun writeText(file: OutputStream, text: String) {
    file.write(text.toByteArray())
  }
}

@Serializable
public class ArbigentProjectFileContent(
  @SerialName("scenarios")
  public val scenarioContents: List<ArbigentScenarioContent>,
  public val settings: ArbigentProjectSettings = ArbigentProjectSettings()
)

public typealias ArbigentContentTags = Set<ArbigentContentTag>

@Serializable
public data class ArbigentContentTag(
  public val name: String
)

@Serializable
public enum class ImageDetailLevel {
  @SerialName("high")
  HIGH,
  @SerialName("low")
  LOW
}

@Serializable
public enum class ImageFormat {
  @SerialName("png")
  PNG,
  @SerialName("webp")
  WEBP,
  @SerialName("lossy_webp")
  LOSSY_WEBP;

  public val fileExtension: String
    get() = when (this) {
      PNG -> "png"
      WEBP, LOSSY_WEBP -> "webp"
    }

  public val mimeType: String
    get() = when (this) {
      PNG -> "image/png"
      WEBP, LOSSY_WEBP -> "image/webp"
    }
}

@Serializable
public data class ArbigentAiOptions(
  public val temperature: Double? = null,
  public val imageDetail: ImageDetailLevel? = null,
  public val imageFormat: ImageFormat? = null,
  public val historicalStepLimit: Int? = null
) {
  public fun mergeWith(other: ArbigentAiOptions?): ArbigentAiOptions {
    if (other == null) return this
    return ArbigentAiOptions(
      temperature = other.temperature ?: temperature,
      imageDetail = other.imageDetail ?: imageDetail,
      imageFormat = other.imageFormat ?: imageFormat,
      historicalStepLimit = other.historicalStepLimit ?: historicalStepLimit
    )
  }
}

@Serializable
public data class ArbigentProjectSettings(
  public val prompt: ArbigentPrompt = ArbigentPrompt(),
  public val cacheStrategy: CacheStrategy = CacheStrategy(),
  public val aiOptions: ArbigentAiOptions? = null,
  @YamlMultiLineStringStyle(MultiLineStringStyle.Literal)
  public val mcpJson: String = "{}"
)

@Serializable
public data class ArbigentPrompt(
  public val systemPrompts: List<String> = listOf(ArbigentPrompts.systemPrompt),
  public val systemPromptsForTv: List<String> = listOf(ArbigentPrompts.systemPromptForTv),
  @YamlMultiLineStringStyle(MultiLineStringStyle.Literal)
  public val additionalSystemPrompts: List<String> = listOf(),
  @YamlMultiLineStringStyle(MultiLineStringStyle.Literal)
  public val userPromptTemplate: String = UserPromptTemplate.DEFAULT_TEMPLATE
)

@Serializable
public data class CacheStrategy(
  public val aiDecisionCacheStrategy: AiDecisionCacheStrategy = AiDecisionCacheStrategy.Disabled
)

@Serializable
public sealed interface AiDecisionCacheStrategy {
  @Serializable
  @SerialName("Disabled")
  public data object Disabled : AiDecisionCacheStrategy

  @Serializable
  @SerialName("InMemory")
  public data class InMemory(
    val maxCacheSize: Long = 100,
    val expireAfterWriteMs: Long = 24.hours.inWholeMilliseconds
  ) : AiDecisionCacheStrategy

  @Serializable
  @SerialName("Disk")
  public data class Disk(
    val maxCacheSize: Long = 500 * 1024 * 1024, // 500MB
  ) : AiDecisionCacheStrategy

}

public fun List<ArbigentScenarioContent>.createArbigentScenario(
  projectSettings: ArbigentProjectSettings,
  scenario: ArbigentScenarioContent,
  aiFactory: () -> ArbigentAi,
  deviceFactory: () -> ArbigentDevice,
  aiDecisionCache: ArbigentAiDecisionCache
): ArbigentScenario {
  val visited = mutableSetOf<ArbigentScenarioContent>()
  val result = mutableListOf<ArbigentAgentTask>()
  fun dfs(nodeScenario: ArbigentScenarioContent) {
    if (visited.contains(nodeScenario)) {
      return
    }
    visited.add(nodeScenario)
    nodeScenario.dependencyId?.let { dependency ->
      val dependencyScenario = first { it.id == dependency }
      dfs(dependencyScenario)
    }
    result.add(
      ArbigentAgentTask(
        scenarioId = nodeScenario.id,
        goal = nodeScenario.goal,
        maxStep = nodeScenario.maxStep,
        deviceFormFactor = nodeScenario.deviceFormFactor,
        agentConfig = AgentConfigBuilder(
          prompt = projectSettings.prompt,
          scenarioType = nodeScenario.type,
          deviceFormFactor = nodeScenario.deviceFormFactor,
          initializationMethods = nodeScenario.initializationMethods.ifEmpty { listOf(nodeScenario.initializeMethods) },
          imageAssertions = ArbigentImageAssertions(
            nodeScenario.imageAssertions,
            nodeScenario.imageAssertionHistoryCount
          ),
          aiDecisionCache = aiDecisionCache,
          cacheOptions = nodeScenario.cacheOptions ?: ArbigentScenarioCacheOptions()
        ).apply {
          aiOptions(projectSettings.aiOptions?.mergeWith(nodeScenario.aiOptions) ?: nodeScenario.aiOptions)
          ai(aiFactory())
          deviceFactory(deviceFactory)
        }.build(),
      )
    )
  }
  dfs(scenario)
  arbigentDebugLog("executing:$result")
  return ArbigentScenario(
    id = scenario.id,
    agentTasks = result,
    maxRetry = scenario.maxRetry,
    maxStepCount = scenario.maxStep,
    tags = scenario.tags,
    deviceFormFactor = scenario.deviceFormFactor,
    isLeaf = this.none { it.dependencyId == scenario.id },
    cacheOptions = scenario.cacheOptions
  )
}

@Serializable
public sealed interface ArbigentScenarioType {
  @Serializable
  @SerialName("Scenario")
  public data object Scenario : ArbigentScenarioType

  @Serializable
  @SerialName("Execution")
  public data object Execution : ArbigentScenarioType

  public fun isScenario(): Boolean = this is Scenario
  public fun isExecution(): Boolean = this is Execution
}

@Serializable
public class ArbigentScenarioContent @OptIn(ExperimentalUuidApi::class) constructor(
  public val id: String = Uuid.random().toString(),
  @YamlMultiLineStringStyle(MultiLineStringStyle.Literal)
  public val goal: String,
  public val type: ArbigentScenarioType = ArbigentScenarioType.Scenario,
  @SerialName("dependency")
  public val dependencyId: String? = null,
  public val initializationMethods: List<InitializationMethod> = listOf(),
  @Deprecated("use initializationMethods")
  public val initializeMethods: InitializationMethod = InitializationMethod.Noop,
  @YamlMultiLineStringStyle(MultiLineStringStyle.Literal)
  public val noteForHumans: String = "",
  public val maxRetry: Int = 3,
  public val maxStep: Int = 10,
  public val tags: ArbigentContentTags = setOf(),
  public val deviceFormFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
  // This is no longer used and will be removed in the future.
  public val cleanupData: CleanupData = CleanupData.Noop,
  public val imageAssertionHistoryCount: Int = 1,
  public val imageAssertions: List<ArbigentImageAssertion> = emptyList(),
  public val userPromptTemplate: String = UserPromptTemplate.DEFAULT_TEMPLATE,
  public val aiOptions: ArbigentAiOptions? = null,
  public val cacheOptions: ArbigentScenarioCacheOptions? = null
) {
  @Serializable
  public sealed interface CleanupData {
    @Serializable
    @SerialName("Noop")
    public data object Noop : CleanupData

    @Serializable
    @SerialName("Cleanup")
    public data class Cleanup(val packageName: String) : CleanupData
  }

  @Serializable
  public sealed interface InitializationMethod {
    @Serializable
    @SerialName("Back")
    public data class Back(
      val times: Int = 3
    ) : InitializationMethod

    @Serializable
    @SerialName("Wait")
    public data class Wait(
      val durationMs: Long
    ) : InitializationMethod

    @Serializable
    @SerialName("Noop")
    public data object Noop : InitializationMethod

    @Serializable
    @SerialName("LaunchApp")
    public data class LaunchApp(
      val packageName: String,
      val launchArguments: Map<String, @Contextual ArgumentValue> = emptyMap()
    ) : InitializationMethod {
      @Serializable
      public sealed interface ArgumentValue {
        public val value: Any

        @Serializable
        @SerialName("String")
        public data class StringVal(override val value: String) : ArgumentValue

        @Serializable
        @SerialName("Int")
        public data class IntVal(override val value: Int) : ArgumentValue

        @Serializable
        @SerialName("Boolean")
        public data class BooleanVal(override val value: Boolean) : ArgumentValue
      }
    }


    @Serializable
    @SerialName("CleanupData")
    public data class CleanupData(val packageName: String) : InitializationMethod

    @Serializable
    @SerialName("OpenLink")
    public data class OpenLink(val link: String) : InitializationMethod
  }
}


public class ArbigentProjectSerializer(
  private val fileSystem: FileSystem = object : FileSystem {}
) {
  private val yaml = Yaml(
    configuration = YamlConfiguration(
      strictMode = false,
      encodeDefaults = false,
      polymorphismStyle = PolymorphismStyle.Property
    ),
    serializersModule = SerializersModule {
      polymorphic(Any::class) {
        subclass(String::class)
        subclass(Int::class)
        subclass(Boolean::class)
      }
    }
  )

  public fun save(projectFileContent: ArbigentProjectFileContent, file: File) {
    save(projectFileContent, file.outputStream())
  }

  private fun save(projectFileContent: ArbigentProjectFileContent, outputStream: OutputStream) {
    val jsonString =
      yaml.encodeToString(ArbigentProjectFileContent.serializer(), projectFileContent)
    fileSystem.writeText(outputStream, jsonString)
  }

  public fun load(file: File): ArbigentProjectFileContent {
    return load(file.inputStream())
  }

  internal fun load(yamlString: String): ArbigentProjectFileContent {
    return yaml.decodeFromString(ArbigentProjectFileContent.serializer(), yamlString)
  }

  private fun load(inputStream: InputStream): ArbigentProjectFileContent {
    val jsonString = fileSystem.readText(inputStream)
    val projectFileContent =
      yaml.decodeFromString(ArbigentProjectFileContent.serializer(), jsonString)
    return projectFileContent
  }

  public fun save(projectResult: ArbigentProjectExecutionResult, file: File) {
    val outputStream = file.outputStream()
    val jsonString =
      ArbigentProjectExecutionResult.yaml.encodeToString(
        ArbigentProjectExecutionResult.serializer(),
        projectResult
      )
    fileSystem.writeText(outputStream, jsonString)
  }
}
