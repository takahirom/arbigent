package com.github.takahirom.arbiter.ui

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.github.takahirom.arbiter.DeviceFormFactor
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.io.File

class ScenarioSerializer {

  @Serializable
  class FileContent(
    val scenarios: List<ScenarioContent>
  )

  @Serializable
  class ScenarioContent(
    val goal: String,
    val dependency: String?,
    val initializeMethods: InitializeMethods,
    val maxRetry: Int = 3,
    val maxTurn: Int = 10,
    val deviceFormFactor: DeviceFormFactor = DeviceFormFactor.Mobile,
    val cleanupData: CleanupData = CleanupData.Noop
  )

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

  fun save(scenarios: List<ScenarioStateHolder>, file: File) {
    val fileContent = FileContent(scenarios.map {
      ScenarioContent(
        goal = it.goal,
        dependency = it.dependencyScenarioStateFlow.value?.goal,
        initializeMethods = it.initializeMethodsStateFlow.value,
        maxRetry = it.maxRetryState.text.toString().toIntOrNull() ?: 3,
        maxTurn = it.maxTurnState.text.toString().toIntOrNull() ?: 10,
        deviceFormFactor = it.deviceFormFactorStateFlow.value,
        cleanupData = it.cleanupDataStateFlow.value
      )
    })
    val jsonString = yaml.encodeToString(FileContent.serializer(), fileContent)
    file.writeText(jsonString)
  }

  fun load(file: File): List<ScenarioContent> {
    val jsonString = file.readText()
    val fileContent = yaml.decodeFromString(FileContent.serializer(), jsonString)
    return fileContent.scenarios
  }
}