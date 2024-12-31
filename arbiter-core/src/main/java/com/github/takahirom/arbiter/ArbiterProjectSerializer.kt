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

interface FileSystem {
  fun readText(inputStream: InputStream): String {
    return inputStream.readAllBytes().decodeToString()
  }

  fun writeText(file: OutputStream, text: String) {
    file.write(text.toByteArray())
  }
}

var fileSystem: FileSystem = object : FileSystem {}

@Serializable
class ArbiterProjectConfig(
  val scenarios: List<ArbiterScenario>
) {
  fun cerateExecutorScenario(
    scenario: ArbiterScenario,
    aiFactory: () -> ArbiterAi,
    deviceFactory: () -> ArbiterDevice,
  ): ArbiterScenarioExecutor.ArbiterExecutorScenario {
    val visited = mutableSetOf<ArbiterScenario>()
    val result = mutableListOf<ArbiterScenarioExecutor.ArbiterAgentTask>()
    fun dfs(nodeScenario: ArbiterScenario) {
      if (visited.contains(nodeScenario)) {
        return
      }
      visited.add(nodeScenario)
      nodeScenario.goalDependency?.let { dependency ->
        val dependencyScenario = scenarios.first { it.goal == dependency }
        dfs(dependencyScenario)
        result.add(
          ArbiterScenarioExecutor.ArbiterAgentTask(
            goal = dependencyScenario.goal,
            agentConfig = AgentConfigBuilder(
              deviceFormFactor = dependencyScenario.deviceFormFactor,
              initializeMethods = dependencyScenario.initializeMethods,
              cleanupData = dependencyScenario.cleanupData
            ).apply {
              ai(aiFactory())
              device(deviceFactory())
            }.build(),
          )
        )
      }
      result.add(
        ArbiterScenarioExecutor.ArbiterAgentTask(
          goal = nodeScenario.goal,
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
    println("executing:$result")
    return ArbiterScenarioExecutor.ArbiterExecutorScenario(
      arbiterAgentTasks = result,
      maxRetry = scenario.maxRetry,
      maxStepCount = scenario.maxStep,
      deviceFormFactor = scenario.deviceFormFactor
    )
  }
}

@Serializable
class ArbiterScenario(
  val goal: String,
  val dependency: String?,
  val initializeMethods: InitializeMethods,
  val maxRetry: Int = 3,
  val maxStep: Int = 10,
  val deviceFormFactor: ArbiterScenarioDeviceFormFactor = ArbiterScenarioDeviceFormFactor.Mobile,
  val cleanupData: CleanupData = CleanupData.Noop
) {
  val goalDependency: String?
    get() = if (dependency?.contains(":") == true) {
      if (dependency.startsWith("goal:")) {
        dependency.split(":")[1]
      } else {
        null
      }
    } else {
      dependency
    }

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
    polymorphic(ArbiterScenario.InitializeMethods::class) {
      subclass(ArbiterScenario.InitializeMethods.Back::class)
      subclass(ArbiterScenario.InitializeMethods.Noop::class)
      subclass(ArbiterScenario.InitializeMethods.OpenApp::class)
    }
  }

  private val yaml = Yaml(
    serializersModule = module,
    configuration = YamlConfiguration(
      strictMode = false
    )
  )

  fun save(projectConfig: ArbiterProjectConfig, file: File) {
    save(projectConfig, file.outputStream())
  }

  fun save(projectConfig: ArbiterProjectConfig, outputStream: OutputStream) {
    val jsonString = yaml.encodeToString(ArbiterProjectConfig.serializer(), projectConfig)
    fileSystem.writeText(outputStream, jsonString)
  }

  fun load(file: File): ArbiterProjectConfig {
    return load(file.inputStream())
  }

  fun load(inputStream: InputStream): ArbiterProjectConfig {
    val jsonString = fileSystem.readText(inputStream)
    val projectConfig = yaml.decodeFromString(ArbiterProjectConfig.serializer(), jsonString)
    return projectConfig
  }
}