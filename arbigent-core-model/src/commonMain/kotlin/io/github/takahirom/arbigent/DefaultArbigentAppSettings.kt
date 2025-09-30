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

  /**
   * Gets the PATH environment variable.
   *
   * @return Always returns null as this is a default implementation.
   */
  override val path: String? = null

  /**
   * Gets the variables map for goal substitution.
   *
   * @return Always returns null as this is a default implementation.
   */
  override val variables: Map<String, String>? = null

  /**
   * Gets the MCP tool environment variables.
   *
   * @return Always returns null as this is a default implementation.
   */
  override val mcpEnvironmentVariables: Map<String, String>? = null
}
