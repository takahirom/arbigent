package io.github.takahirom.arbigent

import kotlinx.serialization.Serializable

/**
 * Configuration options for controlling caching behavior at the scenario level.
 */
@Serializable
public data class ArbigentScenarioCacheOptions(
    /**
     * Whether caching is enabled for this scenario.
     * When false, the scenario will not use any caching mechanisms.
     */
    val cacheEnabled: Boolean = true
)
