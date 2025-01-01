package com.github.takahirom.arbiter

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface FileSystem {
  fun readText(inputStream: InputStream): String {
    return inputStream.readAllBytes().decodeToString()
  }

  fun writeText(file: OutputStream, text: String) {
    file.write(text.toByteArray())
  }
}

@Serializable
class ArbiterProjectFileContent(
  @SerialName("scenarios")
  val scenarioContents: List<ArbiterScenarioContent>
)

fun List<ArbiterScenarioContent>.createArbiterScenario(
  scenario: ArbiterScenarioContent,
  aiFactory: () -> ArbiterAi,
  deviceFactory: () -> ArbiterDevice,
): ArbiterScenario {
  val visited = mutableSetOf<ArbiterScenarioContent>()
  val result = mutableListOf<ArbiterScenarioExecutor.ArbiterAgentTask>()
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
      ArbiterScenarioExecutor.ArbiterAgentTask(
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
          device(deviceFactory())
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
class ArbiterScenarioContent @OptIn(ExperimentalUuidApi::class) constructor(
  val id: String = Uuid.random().toString(),
  val goal: String,
  @SerialName("dependency")
  val dependencyId: String?,
  val initializeMethods: InitializeMethods,
  val maxRetry: Int = 3,
  val maxStep: Int = 10,
  val deviceFormFactor: ArbiterScenarioDeviceFormFactor = ArbiterScenarioDeviceFormFactor.Mobile,
  val cleanupData: CleanupData = CleanupData.Noop
) {
  @Serializable
  sealed interface CleanupData {
    @Serializable
    @SerialName("Noop")
    data object Noop : CleanupData

    @Serializable
    @SerialName("Cleanup")
    data class Cleanup(val packageName: String) : CleanupData
  }

  @Serializable
  sealed interface InitializeMethods {
    @Serializable
    @SerialName("Back")
    data object Back : InitializeMethods

    @Serializable
    @SerialName("Noop")
    data object Noop : InitializeMethods

    @Serializable
    @SerialName("OpenApp")
    data class OpenApp(val packageName: String) : InitializeMethods
  }
}


class ArbiterProjectSerializer(
  private val fileSystem: FileSystem = object : FileSystem {}
) {

  private val module = SerializersModule {
    polymorphic(ArbiterScenarioContent.InitializeMethods::class) {
      subclass(ArbiterScenarioContent.InitializeMethods.Back::class)
      subclass(ArbiterScenarioContent.InitializeMethods.Noop::class)
      subclass(ArbiterScenarioContent.InitializeMethods.OpenApp::class)
    }
  }

  private val yaml = Yaml(
    serializersModule = module,
    configuration = YamlConfiguration(
      strictMode = false
    )
  )

  fun save(projectFileContent: ArbiterProjectFileContent, file: File) {
    save(projectFileContent, file.outputStream())
  }

  fun save(projectFileContent: ArbiterProjectFileContent, outputStream: OutputStream) {
    val jsonString = yaml.encodeToString(ArbiterProjectFileContent.serializer(), projectFileContent)
    fileSystem.writeText(outputStream, jsonString)
  }

  fun load(file: File): ArbiterProjectFileContent {
    return load(file.inputStream())
  }

  fun load(inputStream: InputStream): ArbiterProjectFileContent {
    val jsonString = fileSystem.readText(inputStream)
    val projectFileContent =
      yaml.decodeFromString(ArbiterProjectFileContent.serializer(), jsonString)
    return projectFileContent
  }
}