package io.github.takahirom.arbigent

import kotlinx.serialization.Serializable

/**
 * Configuration options for controlling caching behavior at the scenario level.
 */
@Serializable
public data class ArbigentScenarioCacheOptions(
    /**
     * Override the default cache behavior for this scenario.
     * - true: Force cache disable
     * - false: Do nothing
     */
    val forceCacheDisabled: Boolean = false,
    /**
     * Replay the last successful action trace before using normal AI decision execution.
     */
    val replayWithFallback: Boolean = false,
)
