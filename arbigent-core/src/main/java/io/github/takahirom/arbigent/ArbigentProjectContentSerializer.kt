package io.github.takahirom.arbigent

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.EncodeDefault
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
)

public fun List<ArbigentScenarioContent>.createArbigentScenario(
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
          deviceFormFactor = nodeScenario.deviceFormFactor,
          initializeMethods = nodeScenario.initializeMethods,
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
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  public val dependencyId: String? = null,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  public val initializeMethods: InitializeMethods = InitializeMethods.Noop,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  public val maxRetry: Int = 3,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  public val maxStep: Int = 10,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  public val deviceFormFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  public val cleanupData: CleanupData = CleanupData.Noop,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
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
  public sealed interface InitializeMethods {
    @Serializable
    @SerialName("Back")
    public data class Back(
      val times: Int = 3
    ) : InitializeMethods

    @Serializable
    @SerialName("Noop")
    public data object Noop : InitializeMethods

    @Serializable
    @SerialName("LaunchApp")
    public data class LaunchApp(val packageName: String) : InitializeMethods
  }
}


public class ArbigentProjectContentSerializer(
  private val fileSystem: FileSystem = object : FileSystem {}
) {
  private val yaml = Yaml(
    configuration = YamlConfiguration(
      strictMode = false,
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

  private fun load(inputStream: InputStream): ArbigentProjectFileContent {
    val jsonString = fileSystem.readText(inputStream)
    val projectFileContent =
      yaml.decodeFromString(ArbigentProjectFileContent.serializer(), jsonString)
    return projectFileContent
  }
}