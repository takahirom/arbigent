package com.github.takahirom.arbiter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.io.File

class ArbiterProjectSerializer {

  @Serializable
  class ProjectFile(
    val scenarios: List<ArbiterScenario>
  )

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
    val maxTurn: Int = 10,
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

  private val yaml = com.charleskorn.kaml.Yaml(
    module, com.charleskorn.kaml.YamlConfiguration(
      strictMode = false
    )
  )

  fun save(scenarios: List<ArbiterScenario>, file: File) {
    val projectFile = ProjectFile(scenarios)
    val jsonString = yaml.encodeToString(ProjectFile.serializer(), projectFile)
    file.writeText(jsonString)
  }

  fun load(file: File): List<ArbiterScenario> {
    val jsonString = file.readText()
    val projectFile = yaml.decodeFromString(ProjectFile.serializer(), jsonString)
    return projectFile.scenarios
  }
}