package io.github.takahirom.arbigent

/** Application settings interface. */
public interface ArbigentAppSettings {
    /** Working directory path. */
    public val workingDirectory: String?
    
    /** PATH environment variable. */
    public val path: String?
    
    /**
     * Variables for substitution in goals using {{variable_name}} format.
     * Escaped variables (\{{variable_name}}) are preserved.
     */
    public val variables: Map<String, String>?
    
    /**
     * MCP tool environment variables to be passed to MCP processes.
     */
    public val mcpEnvironmentVariables: Map<String, String>?
}
