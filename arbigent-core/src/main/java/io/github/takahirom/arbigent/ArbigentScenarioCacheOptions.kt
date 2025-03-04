package io.github.takahirom.arbigent

import kotlinx.serialization.Serializable

/**
 * Configuration options for controlling caching behavior at the scenario level.
 */
@Serializable
public data class ArbigentScenarioCacheOptions(
    /**
     * Override the default cache behavior for this scenario.
     * - null: Use default cache behavior
     * - true: Force enable cache
     * - false: Force disable cache
     */
    val overrideCacheEnabled: Boolean? = null
)
