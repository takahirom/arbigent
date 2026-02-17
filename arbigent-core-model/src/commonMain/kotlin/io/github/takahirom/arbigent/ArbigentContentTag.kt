package io.github.takahirom.arbigent

import kotlinx.serialization.Serializable

public typealias ArbigentContentTags = Set<ArbigentContentTag>

@Serializable
public data class ArbigentContentTag(
  public val name: String
)