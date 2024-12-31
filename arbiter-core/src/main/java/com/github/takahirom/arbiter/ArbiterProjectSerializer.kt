package com.github.takahirom.arbiter

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.io.File

interface FileSystem {
  fun readText(file: File): String {
    return file.readText()
  }

  fun writeText(file: File, text: String) {
    file.writeText(text)
  }
}

var fileSystem: FileSystem = object : FileSystem {}

class ArbiterProjectSerializer(
  private val fileSystem: FileSystem = object : FileSystem {}
) {
  @Serializable
  class ArbiterProjectConfig(
    val scenarios: List<ArbiterScenario>
  ) {
    fun scenarioDependencyList(
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
  }

  private val module = SerializersModule {
    polymorphic(InitializeMethods::class) {
      subclass(InitializeMethods.Back::class)
      subclass(InitializeMethods.Noop::class)
      subclass(InitializeMethods.OpenApp::class)
    }
  }

  private val yaml = Yaml(
    serializersModule = module,
    configuration = YamlConfiguration(
      strictMode = false
    )
  )

  fun save(projectConfig: ArbiterProjectConfig, file: File) {
    val jsonString = yaml.encodeToString(ArbiterProjectConfig.serializer(), projectConfig)
    fileSystem.writeText(file, jsonString)
  }

  fun load(file: File): ArbiterProjectConfig {
    val jsonString = fileSystem.readText(file)
    val projectConfig = yaml.decodeFromString(ArbiterProjectConfig.serializer(), jsonString)
    return projectConfig
  }
}