package io.github.takahirom.arbigent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

public class ArbigentTag private constructor(initialTagName: String) {
  private val _name: MutableStateFlow<String> = MutableStateFlow(initialTagName)
  public val nameStateFlow: StateFlow<String> get() = _name
  public val name: String get() = nameStateFlow.value
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ArbigentTag

    return name == other.name
  }

  public fun onNameChanged(newName: String) {
    _name.value = newName
  }

  override fun hashCode(): Int {
    return name.hashCode()
  }

  override fun toString(): String {
    return "ArbigentTag(name=$name, nameStateFlow=$nameStateFlow)"
  }

  public companion object {
    public fun createFromManager(initialTagName: String): ArbigentTag {
      return ArbigentTag(initialTagName)
    }
  }
}
