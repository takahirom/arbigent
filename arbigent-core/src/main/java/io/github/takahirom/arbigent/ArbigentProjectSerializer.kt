package io.github.takahirom.arbigent

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMultiLineStringStyle
import com.charleskorn.kaml.MultiLineStringStyle
import io.github.takahirom.arbigent.ArbigentProjectSettings.Companion.DefaultMcpJson
import io.github.takahirom.arbigent.result.ArbigentProjectExecutionResult
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
  public val fixedScenarios: List<FixedScenario> = emptyList(),
  public val settings: ArbigentProjectSettings = ArbigentProjectSettings(),
)

@Serializable
@OptIn(ExperimentalUuidApi::class)
public data class FixedScenario(
  public val id: String = Uuid.random().toString(),
  public val type: String = "maestro yaml",
  public val title: String,
  public val description: String,
  @YamlMultiLineStringStyle(MultiLineStringStyle.Literal)
  public val yamlText: String
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

/**
 * Serializer that converts between YAML maps and JsonObject.
 * This allows extraBody to be written as YAML maps in project files
 * while being used as JsonObject in the code.
 */
internal object YamlCompatibleJsonObjectSerializer : KSerializer<JsonObject> {
  override val descriptor: SerialDescriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("JsonObject")

  override fun deserialize(decoder: Decoder): JsonObject {
    // For kaml, directly process the YamlNode
    if (decoder is com.charleskorn.kaml.YamlInput) {
      val node = decoder.node
      if (node is com.charleskorn.kaml.YamlMap) {
        return yamlMapToJsonObject(node)
      }
    }
    // Fallback for JSON decoder
    return decoder.decodeSerializableValue(JsonObject.serializer())
  }

  override fun serialize(encoder: Encoder, value: JsonObject) {
    // Always use Map serialization for compatibility with YAML
    val map = jsonObjectToMap(value)
    encoder.encodeSerializableValue(
      MapSerializer(String.serializer(), AnyValueSerializer),
      map
    )
  }

  private fun yamlMapToJsonObject(yamlMap: com.charleskorn.kaml.YamlMap): JsonObject {
    val result = mutableMapOf<String, JsonElement>()
    for ((key, value) in yamlMap.entries) {
      result[key.content] = yamlNodeToJsonElement(value)
    }
    return JsonObject(result)
  }

  private fun yamlNodeToJsonElement(node: com.charleskorn.kaml.YamlNode): JsonElement = when (node) {
    is com.charleskorn.kaml.YamlScalar -> {
      val content = node.content
      // Try to parse as appropriate type
      content.toLongOrNull()?.let { JsonPrimitive(it) }
        ?: content.toDoubleOrNull()?.let { JsonPrimitive(it) }
        ?: content.toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
        ?: JsonPrimitive(content)
    }
    is com.charleskorn.kaml.YamlMap -> yamlMapToJsonObject(node)
    is com.charleskorn.kaml.YamlList -> JsonArray(node.items.map { yamlNodeToJsonElement(it) })
    is com.charleskorn.kaml.YamlNull -> JsonNull
    is com.charleskorn.kaml.YamlTaggedNode -> yamlNodeToJsonElement(node.innerNode)
  }

  private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any?> {
    return jsonObject.mapValues { (_, v) -> jsonElementToAny(v) }
  }

  private fun jsonElementToAny(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> when {
      element.isString -> element.content
      element.content == "true" -> true
      element.content == "false" -> false
      element.content.contains('.') -> element.content.toDoubleOrNull() ?: element.content
      else -> element.content.toLongOrNull() ?: element.content
    }
    is JsonObject -> jsonObjectToMap(element)
    is JsonArray -> element.map { jsonElementToAny(it) }
  }
}

internal object AnyValueSerializer : KSerializer<Any?> {
  override val descriptor: SerialDescriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("Any")

  override fun deserialize(decoder: Decoder): Any? {
    // For kaml, we need to check the input type first
    if (decoder is com.charleskorn.kaml.YamlInput) {
      return deserializeYamlNode(decoder.node)
    }
    // This serializer is designed for YAML only
    throw IllegalStateException("AnyValueSerializer only supports YamlInput decoder, got: ${decoder::class.simpleName}")
  }

  private fun deserializeYamlNode(node: com.charleskorn.kaml.YamlNode): Any? {
    return when (node) {
      is com.charleskorn.kaml.YamlScalar -> {
        val content = node.content
        content.toLongOrNull() ?: content.toDoubleOrNull() ?: content.toBooleanStrictOrNull() ?: content
      }
      is com.charleskorn.kaml.YamlMap -> {
        val result = mutableMapOf<String, Any?>()
        for ((key, value) in node.entries) {
          result[key.content] = deserializeYamlNode(value)
        }
        result.toMap()
      }
      is com.charleskorn.kaml.YamlList -> {
        node.items.map { deserializeYamlNode(it) }
      }
      is com.charleskorn.kaml.YamlNull -> null
      is com.charleskorn.kaml.YamlTaggedNode -> deserializeYamlNode(node.innerNode)
    }
  }

  override fun serialize(encoder: Encoder, value: Any?) {
    when (value) {
      null -> encoder.encodeNull()
      is String -> encoder.encodeString(value)
      is Int -> encoder.encodeInt(value)
      is Long -> encoder.encodeLong(value)
      is Double -> encoder.encodeDouble(value)
      is Boolean -> encoder.encodeBoolean(value)
      is Map<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        encoder.encodeSerializableValue(
          MapSerializer(String.serializer(), this),
          value as Map<String, Any?>
        )
      }
      is List<*> -> {
        encoder.encodeSerializableValue(
          kotlinx.serialization.builtins.ListSerializer(this),
          value
        )
      }
      else -> encoder.encodeString(value.toString())
    }
  }
}

@Serializable
public data class ArbigentAiOptions(
  public val temperature: Double? = null,
  public val imageDetail: ImageDetailLevel? = null,
  public val imageFormat: ImageFormat? = null,
  public val historicalStepLimit: Int? = null,
  @Serializable(with = YamlCompatibleJsonObjectSerializer::class)
  public val extraBody: JsonObject? = null
) {
  public fun mergeWith(other: ArbigentAiOptions?): ArbigentAiOptions {
    if (other == null) return this
    return ArbigentAiOptions(
      temperature = other.temperature ?: temperature,
      imageDetail = other.imageDetail ?: imageDetail,
      imageFormat = other.imageFormat ?: imageFormat,
      historicalStepLimit = other.historicalStepLimit ?: historicalStepLimit,
      extraBody = mergeJsonObjects(extraBody, other.extraBody)
    )
  }

  private fun mergeJsonObjects(base: JsonObject?, overlay: JsonObject?): JsonObject? {
    if (base == null) return overlay
    if (overlay == null) return base
    val merged = base.toMutableMap()
    overlay.forEach { (key, value) -> merged[key] = value }
    return JsonObject(merged)
  }
}

@Serializable
public data class ArbigentProjectSettings(
  public val prompt: ArbigentPrompt = ArbigentPrompt(),
  public val cacheStrategy: CacheStrategy = CacheStrategy(),
  public val aiOptions: ArbigentAiOptions? = null,
  @YamlMultiLineStringStyle(MultiLineStringStyle.Literal)
  public val mcpJson: String = DefaultMcpJson,
  public val deviceFormFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Unspecified,
  public val additionalActions: List<String>? = null,
) {
  public companion object {
    public const val DefaultMcpJson: String = "{}"
  }
}

@Serializable
public data class ArbigentPrompt(
  public val systemPrompts: List<String> = listOf(ArbigentPrompts.systemPrompt),
  public val systemPromptsForTv: List<String> = listOf(ArbigentPrompts.systemPromptForTv),
  @YamlMultiLineStringStyle(MultiLineStringStyle.Literal)
  public val additionalSystemPrompts: List<String> = listOf(),
  @YamlMultiLineStringStyle(MultiLineStringStyle.Literal)
  public val userPromptTemplate: String = UserPromptTemplate.DEFAULT_TEMPLATE,
  @YamlMultiLineStringStyle(MultiLineStringStyle.Literal)
  public val appUiStructure: String = "",
  @YamlMultiLineStringStyle(MultiLineStringStyle.Literal)
  public val scenarioGenerationCustomInstruction: String = ""
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
  aiDecisionCache: ArbigentAiDecisionCache,
  appSettings: ArbigentAppSettings = DefaultArbigentAppSettings,
  fixedScenarios: List<FixedScenario> = emptyList()
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
    // Determine which device form factor to use
    val effectiveDeviceFormFactor = if (nodeScenario.deviceFormFactor is ArbigentScenarioDeviceFormFactor.Unspecified) {
      if (projectSettings.deviceFormFactor is ArbigentScenarioDeviceFormFactor.Unspecified) {
        ArbigentScenarioDeviceFormFactor.Mobile
      } else {
        // If the scenario is from the YAML file and doesn't specify a device form factor,
        // use Mobile as the default (for backward compatibility)
        if (nodeScenario.id == "default-not-using-project") {
          ArbigentScenarioDeviceFormFactor.Mobile
        } else {
          projectSettings.deviceFormFactor
        }
      }
    } else {
      nodeScenario.deviceFormFactor
    }

    // Merge additionalActions from project and scenario
    val mergedAdditionalActions = (projectSettings.additionalActions.orEmpty() + nodeScenario.additionalActions.orEmpty()).distinct()

    result.add(
      ArbigentAgentTask(
        scenarioId = nodeScenario.id,
        goal = nodeScenario.goal,
        maxStep = nodeScenario.maxStep,
        deviceFormFactor = effectiveDeviceFormFactor,
        additionalActions = mergedAdditionalActions,
        mcpOptions = nodeScenario.mcpOptions,
        agentConfig = AgentConfigBuilder(
          prompt = projectSettings.prompt,
          scenarioType = nodeScenario.type,
          deviceFormFactor = effectiveDeviceFormFactor,
          initializationMethods = nodeScenario.initializationMethods.ifEmpty { listOf(nodeScenario.initializeMethods) },
          imageAssertions = ArbigentImageAssertions(
            nodeScenario.imageAssertions,
            nodeScenario.imageAssertionHistoryCount
          ),
          aiDecisionCache = aiDecisionCache,
          cacheOptions = nodeScenario.cacheOptions ?: ArbigentScenarioCacheOptions(),
          mcpClient = if (projectSettings.mcpJson.isNotBlank() && projectSettings.mcpJson != DefaultMcpJson) {
            MCPClient(projectSettings.mcpJson, appSettings)
          } else {
            null
          },
          fixedScenarios = fixedScenarios,
          appSettings = appSettings
        ).apply {
          aiOptions(projectSettings.aiOptions?.mergeWith(nodeScenario.aiOptions) ?: nodeScenario.aiOptions)
          aiFactory(aiFactory)
          deviceFactory(deviceFactory)
        }.build(),
      )
    )
  }
  dfs(scenario)
  arbigentDebugLog("Built scenario ${scenario.id} with ${result.size} tasks: ${result.map { it.scenarioId }}")
  // Determine which device form factor to use for the scenario
  val effectiveScenarioDeviceFormFactor = if (scenario.deviceFormFactor is ArbigentScenarioDeviceFormFactor.Unspecified) {
    if (projectSettings.deviceFormFactor is ArbigentScenarioDeviceFormFactor.Unspecified) {
      ArbigentScenarioDeviceFormFactor.Mobile
    } else {
      // If the scenario is from the YAML file and doesn't specify a device form factor,
      // use Mobile as the default (for backward compatibility)
      if (scenario.id == "default-not-using-project") {
        ArbigentScenarioDeviceFormFactor.Mobile
      } else {
        projectSettings.deviceFormFactor
      }
    }
  } else {
    scenario.deviceFormFactor
  }

  return ArbigentScenario(
    id = scenario.id,
    agentTasks = result,
    maxRetry = scenario.maxRetry,
    maxStepCount = scenario.maxStep,
    tags = scenario.tags,
    deviceFormFactor = effectiveScenarioDeviceFormFactor,
    isLeaf = this.none { it.dependencyId == scenario.id },
    cacheOptions = scenario.cacheOptions,
    mcpOptions = scenario.mcpOptions
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
  public val deviceFormFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Unspecified,
  // This is no longer used and will be removed in the future.
  public val cleanupData: CleanupData = CleanupData.Noop,
  public val imageAssertionHistoryCount: Int = 1,
  public val imageAssertions: List<ArbigentImageAssertion> = emptyList(),
  public val userPromptTemplate: String = UserPromptTemplate.DEFAULT_TEMPLATE,
  public val aiOptions: ArbigentAiOptions? = null,
  public val cacheOptions: ArbigentScenarioCacheOptions? = null,
  public val additionalActions: List<String>? = null,
  public val mcpOptions: ArbigentMcpOptions? = null
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

    @Serializable
    @SerialName("MaestroYaml")
    public data class MaestroYaml(
        val scenarioId: String,
        val yamlContent: String? = null
    ) : InitializationMethod
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
    val result = load(file.inputStream())
    return result
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

  public fun encodeToString(projectFileContent: ArbigentProjectFileContent): String {
    return yaml.encodeToString(ArbigentProjectFileContent.serializer(), projectFileContent)
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
