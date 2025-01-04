package com.github.takahirom.arbiter

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
public class ArbiterProjectFileContent(
  @SerialName("scenarios")
  public val scenarioContents: List<ArbiterScenarioContent>
)

public fun List<ArbiterScenarioContent>.createArbiterScenario(
  scenario: ArbiterScenarioContent,
  aiFactory: () -> ArbiterAi,
  deviceFactory: () -> ArbiterDevice,
): ArbiterScenario {
  val visited = mutableSetOf<ArbiterScenarioContent>()
  val result = mutableListOf<ArbiterAgentTask>()
  fun dfs(nodeScenario: ArbiterScenarioContent) {
    if (visited.contains(nodeScenario)) {
      return
    }
    visited.add(nodeScenario)
    nodeScenario.dependencyId?.let { dependency ->
      val dependencyScenario = first { it.id == dependency }
      dfs(dependencyScenario)
    }
    result.add(
      ArbiterAgentTask(
        scenarioId = nodeScenario.id,
        goal = nodeScenario.goal,
        maxStep = nodeScenario.maxStep,
        deviceFormFactor = nodeScenario.deviceFormFactor,
        agentConfig = AgentConfigBuilder(
          deviceFormFactor = nodeScenario.deviceFormFactor,
          initializeMethods = nodeScenario.initializeMethods,
          cleanupData = nodeScenario.cleanupData
        ).apply {
          ai(aiFactory())
          deviceFactory(deviceFactory)
        }.build(),
      )
    )
  }
  dfs(scenario)
  arbiterDebugLog("executing:$result")
  return ArbiterScenario(
    id = scenario.id,
    agentTasks = result,
    maxRetry = scenario.maxRetry,
    maxStepCount = scenario.maxStep,
    deviceFormFactor = scenario.deviceFormFactor
  )
}

@Serializable
public class ArbiterScenarioContent @OptIn(ExperimentalUuidApi::class) constructor(
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
  public val deviceFormFactor: ArbiterScenarioDeviceFormFactor = ArbiterScenarioDeviceFormFactor.Mobile,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  public val cleanupData: CleanupData = CleanupData.Noop
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


public class ArbiterProjectSerializer(
  private val fileSystem: FileSystem = object : FileSystem {}
) {
  private val yaml = Yaml(
    configuration = YamlConfiguration(
      strictMode = false,
      polymorphismStyle = PolymorphismStyle.Property
    )
  )

  public fun save(projectFileContent: ArbiterProjectFileContent, file: File) {
    save(projectFileContent, file.outputStream())
  }

  private fun save(projectFileContent: ArbiterProjectFileContent, outputStream: OutputStream) {
    val jsonString = yaml.encodeToString(ArbiterProjectFileContent.serializer(), projectFileContent)
    fileSystem.writeText(outputStream, jsonString)
  }

  public fun load(file: File): ArbiterProjectFileContent {
    return load(file.inputStream())
  }

  private fun load(inputStream: InputStream): ArbiterProjectFileContent {
    val jsonString = fileSystem.readText(inputStream)
    val projectFileContent =
      yaml.decodeFromString(ArbiterProjectFileContent.serializer(), jsonString)
    return projectFileContent
  }
}