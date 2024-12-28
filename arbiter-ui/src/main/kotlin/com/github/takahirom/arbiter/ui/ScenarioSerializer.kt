package com.github.takahirom.arbiter.ui

import com.charleskorn.kaml.Yaml
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
    val retry: Int = 0
  )

  private val module = SerializersModule {
    polymorphic(InitializeMethods::class) {
      subclass(InitializeMethods.Back::class)
      subclass(InitializeMethods.Noop::class)
      subclass(InitializeMethods.OpenApp::class)
    }
  }

  private val yaml = Yaml(module)

  fun save(scenarios: List<ScenarioStateHolder>, file: File) {
    val fileContent = FileContent(scenarios.map {
      ScenarioContent(
        it.goal,
        it.dependencyScenarioStateFlow.value,
        it.initializeMethodsStateFlow.value,
        it.retryStateFlow.value
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