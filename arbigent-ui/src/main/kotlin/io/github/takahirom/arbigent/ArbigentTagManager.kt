package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.ui.ArbigentScenarioStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.collections.plus

public class ArbigentTagManager() {
  private val scenarioToTags: MutableStateFlow<Map<ArbigentScenarioStateHolder, Set<ArbigentTag>>> = MutableStateFlow(mapOf())
  public val scenarioToTagsStateFlow: StateFlow<Map<ArbigentScenarioStateHolder, Set<ArbigentTag>>> = scenarioToTags
  private fun getOrCreateTag(tagName: String): ArbigentTag {
    return scenarioToTags.value.values.flatten().find { it.name == tagName } ?: ArbigentTag.createFromManager(tagName)
  }

  public fun contentTags(): ArbigentContentTags {
    return scenarioToTags.value.values.flatten().map { ArbigentContentTag(it.name) }.toSet()
  }

  public fun loadTagsForScenario(scenario: ArbigentScenarioStateHolder, tags: Set<String>) {
    val newTags = tags.map { tag ->
      getOrCreateTag(tag)
    }.toSet()
    scenarioToTags.value = scenarioToTags.value + (scenario to newTags.toMutableSet())
  }

  public fun tagsForScenario(scenario: ArbigentScenarioStateHolder): ArbigentContentTags {
    return scenarioToTags.value[scenario]?.map { ArbigentContentTag(it.name) }?.toSet() ?: setOf()
  }

  public fun addTagForScenario(scenario: ArbigentScenarioStateHolder, tagName: String) {
    val tag = getOrCreateTag(tagName)
    scenarioToTags.value = scenarioToTags.value + (scenario to (scenarioToTags.value[scenario] ?: mutableSetOf()).plus(tag))
  }

  public fun removeTagForScenario(scenario: ArbigentScenarioStateHolder, tagName: String) {
    val tag = getOrCreateTag(tagName)
    scenarioToTags.value = scenarioToTags.value + (scenario to (scenarioToTags.value[scenario] ?: mutableSetOf()).minus(tag))
  }

  fun changeTagForScenario(holder: ArbigentScenarioStateHolder, oldName: String, newName: String) {
    val oldTag = getOrCreateTag(oldName)
    scenarioToTags.value = scenarioToTags.value.map { (scenario, tags) ->
      if (tags.contains(oldTag)) {
        scenario to (tags - oldTag + getOrCreateTag(newName))
      } else {
        scenario to tags
      }
    }.toMap()
  }
}