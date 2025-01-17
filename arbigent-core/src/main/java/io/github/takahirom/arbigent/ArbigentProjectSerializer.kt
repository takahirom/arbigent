package io.github.takahirom.arbigent

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import io.github.takahirom.arbigent.result.ArbigentProjectExecutionResult
import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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

@Serializable
public data class ArbigentProjectSettings(
  public val prompt: ArbigentPrompt = ArbigentPrompt()
)

@Serializable
public data class ArbigentPrompt(
  public val systemPrompts: List<String> = listOf(ArbigentPrompts.systemPrompt),
  public val systemPromptsForTv: List<String> = listOf(ArbigentPrompts.systemPromptForTv),
  public val additionalSystemPrompts: List<String> = listOf()
)

public fun List<ArbigentScenarioContent>.createArbigentScenario(
  projectSettings: ArbigentProjectSettings,
  scenario: ArbigentScenarioContent,
  aiFactory: () -> ArbigentAi,
  deviceFactory: () -> ArbigentDevice,
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
          deviceFormFactor = nodeScenario.deviceFormFactor,
          initializationMethods = nodeScenario.initializationMethods.ifEmpty { listOf(nodeScenario.initializeMethods) },
          cleanupData = nodeScenario.cleanupData,
          imageAssertions = nodeScenario.imageAssertions
        ).apply {
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
    deviceFormFactor = scenario.deviceFormFactor,
    isLeaf = this.none { it.dependencyId == scenario.id }
  )
}

@Serializable
public class ArbigentScenarioContent @OptIn(ExperimentalUuidApi::class) constructor(
  public val id: String = Uuid.random().toString(),
  public val goal: String,
  @SerialName("dependency")
  public val dependencyId: String? = null,
  public val initializationMethods: List<InitializationMethod> = listOf(),
  @Deprecated("use initializationMethods")
  public val initializeMethods: InitializationMethod = InitializationMethod.Noop,
  public val maxRetry: Int = 3,
  public val maxStep: Int = 10,
  public val deviceFormFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
  public val cleanupData: CleanupData = CleanupData.Noop,
  public val imageAssertions: List<ArbigentImageAssertion> = emptyList()
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
    @SerialName("Noop")
    public data object Noop : InitializationMethod

    @Serializable
    @SerialName("LaunchApp")
    public data class LaunchApp(val packageName: String) : InitializationMethod

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
    )
  )

  public fun save(projectFileContent: ArbigentProjectFileContent, file: File) {
    save(projectFileContent, file.outputStream())
  }

  private fun save(projectFileContent: ArbigentProjectFileContent, outputStream: OutputStream) {
    val jsonString = yaml.encodeToString(ArbigentProjectFileContent.serializer(), projectFileContent)
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
      ArbigentProjectExecutionResult.yaml.encodeToString(ArbigentProjectExecutionResult.serializer(), projectResult)
    fileSystem.writeText(outputStream, jsonString)
  }
}