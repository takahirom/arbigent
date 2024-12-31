package com.github.takahirom.arbiter.ui

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.github.takahirom.arbiter.ArbiterScenarioDeviceFormFactor
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
  class ArbiterScenario(
    val goal: String,
    val dependency: String?,
    val initializeMethods: InitializeMethods,
    val maxRetry: Int = 3,
    val maxTurn: Int = 10,
    val deviceFormFactor: ArbiterScenarioDeviceFormFactor = ArbiterScenarioDeviceFormFactor.Mobile,
    val cleanupData: CleanupData = CleanupData.Noop
  ) {
    val goalDependency get() = if(dependency?.contains(":") == true) {
      if (dependency.startsWith("goal:")) {
        dependency.split(":")[1]
      } else {
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
    module, YamlConfiguration(
      strictMode = false
    )
  )

  fun save(scenarios: List<ArbiterScenarioStateHolder>, file: File) {
    val projectFile = ProjectFile(scenarios.map {
      ArbiterScenario(
        goal = it.goal,
        dependency = it.dependencyScenarioStateFlow.value?.goal?.let { "goal:$it" },
        initializeMethods = it.initializeMethodsStateFlow.value,
        maxRetry = it.maxRetryState.text.toString().toIntOrNull() ?: 3,
        maxTurn = it.maxTurnState.text.toString().toIntOrNull() ?: 10,
        deviceFormFactor = it.deviceFormFactorStateFlow.value,
        cleanupData = it.cleanupDataStateFlow.value
      )
    })
    val jsonString = yaml.encodeToString(ProjectFile.serializer(), projectFile)
    file.writeText(jsonString)
  }

  fun load(file: File): List<ArbiterScenario> {
    val jsonString = file.readText()
    val projectFile = yaml.decodeFromString(ProjectFile.serializer(), jsonString)
    return projectFile.scenarios
  }
}