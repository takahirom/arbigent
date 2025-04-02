package io.github.takahirom.arbigent

/**
 * Default implementation of [ArbigentAppSettings] that returns null for all settings.
 * This is used as a default when no specific settings are provided.
 */
public object DefaultArbigentAppSettings : ArbigentAppSettings {
  /**
   * Gets the working directory path.
   *
   * @return Always returns null as this is a default implementation.
   */
  override val workingDirectory: String? = null
}