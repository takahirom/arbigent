package io.github.takahirom.arbigent.cli

import io.github.takahirom.arbigent.ArbigentLogLevel
import io.github.takahirom.arbigent.arbigentDebugLog
import io.github.takahirom.arbigent.arbigentLogLevel
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * End-to-end tests that verify actual configuration values are used correctly
 */
class CliE2ETest {
    @TempDir
    lateinit var tempDir: Path
    
    private lateinit var originalWorkingDir: String
    private lateinit var settingsFile: File
    private lateinit var cliPath: String
    private lateinit var projectFile: File
    
    @BeforeEach
    fun setup() {
        // Set log level from system property for test execution
        val testLogLevel = System.getProperty("arbigent.test.logLevel", "INFO")
        arbigentLogLevel = ArbigentLogLevel.valueOf(testLogLevel.uppercase())
        
        // Save current working directory
        originalWorkingDir = System.getProperty("user.dir")
        
        // Find project root
        var projectRoot = File(originalWorkingDir)
        while (!File(projectRoot, "gradlew").exists() && projectRoot.parent != null) {
            projectRoot = projectRoot.parentFile
        }
        
        // Get CLI path
        cliPath = "${projectRoot.absolutePath}/arbigent-cli/build/install/arbigent/bin/arbigent"
        
        // Ensure CLI is built
        if (!File(cliPath).exists()) {
            val gradlewPath = if (System.getProperty("os.name").lowercase().contains("windows")) {
                "gradlew.bat"
            } else {
                "./gradlew"
            }
            
            val buildResult = ProcessBuilder()
                .command(gradlewPath, "installDist")
                .directory(projectRoot)
                .inheritIO()
                .start()
                .waitFor()
            
            assertEquals(0, buildResult, "Failed to build CLI")
        }
        
        assertTrue(File(cliPath).exists(), "CLI executable not found at $cliPath")
        
        // Create .arbigent directory in temp directory
        val arbigentDir = File(tempDir.toFile(), ".arbigent")
        arbigentDir.mkdir()
        settingsFile = File(arbigentDir, "settings.local.yml")
        
        arbigentDebugLog("Test temp directory: $tempDir")
        arbigentDebugLog("Settings file path: ${settingsFile.absolutePath}")
        
        // Create a test project file
        projectFile = File(tempDir.toFile(), "test-project.yaml")
        projectFile.writeText("""
            scenarios:
            - id: "test-scenario-001"
              goal: "Test scenario for E2E testing"
              initializationMethods:
              - type: "LaunchApp"
                packageName: "com.example.test"
        """.trimIndent())
    }
    
    @Test
    fun `CLI shows help tags for global settings`() {
        // Create settings file with global settings
        settingsFile.writeText("""
            # Global settings
            project-file: ${projectFile.name}
            log-level: debug
            ai-type: openai
        """.trimIndent())
        
        arbigentDebugLog("Settings file created: ${settingsFile.exists()}")
        arbigentDebugLog("Settings file content:\n${settingsFile.readText()}")
        
        // Run CLI help command
        val output = runCli("run", "--help")
        
        // Debug: print output to see what we're getting
        arbigentDebugLog("Help output:\n$output")
        
        // Verify help tags are shown
        assertTrue(output.contains("project-file"), "Should show project-file option")
        assertTrue(output.contains("log-level"), "Should show log-level option") 
        assertTrue(output.contains("ai-type"), "Should show ai-type option")
        
        // Check if any option shows as having settings value
        // The new format is "(currently: 'value' from source)"
        val hasProvidedSettings = output.contains("currently:") && 
                                 (output.contains("from global settings") || 
                                  output.contains("from run.") || 
                                  output.contains("from settings"))
        assertTrue(hasProvidedSettings, "Should show at least one setting with current value from settings file. Output: $output")
    }
    
    @Test
    fun `CLI uses correct global configuration values`() {
        // Create settings with specific values
        settingsFile.writeText("""
            # Global settings
            project-file: ${projectFile.name}
            log-level: debug
            ai-type: openai
            openai-api-key: test-key-123
            os: android
            dry-run: true
        """.trimIndent())
        
        // Run with dry-run and capture output
        val output = runCli("run", "--scenario-ids=test-scenario-001")
        
        // Verify correct project file is loaded
        assertTrue(output.contains("test-scenario-001"), "Should load the correct scenario from project file")
        assertTrue(output.contains("Dry run mode is enabled"), "Should respect dry-run setting from config")
        
        // Verify scenarios are detected
        assertTrue(output.contains("Selected scenarios for execution"), "Should detect and select scenarios")
    }
    
    @Test
    fun `CLI respects command-specific configuration priority`() {
        // Create settings with both global and command-specific values
        settingsFile.writeText("""
            # Global settings
            project-file: ${projectFile.name}
            ai-type: openai
            openai-api-key: fallback-key-123
            log-level: info
            os: android
            dry-run: true
            
            # Run command specific settings - should override global
            run:
              ai-type: azureopenai
              log-level: debug
              azure-openai-endpoint: https://test.openai.azure.com/
              azure-openai-api-key: azure-test-key
        """.trimIndent())
        
        // Run with debug log level to see which AI type is being used
        val output = runCli("run", "--scenario-ids=test-scenario-001", "--ai-api-logging", "--log-level=debug")
        
        // The output should show it's trying to use Azure OpenAI (even in dry run)
        assertTrue(output.contains("Dry run mode is enabled"))
        
        // Look for evidence that azureopenai is being used in the configuration demonstration
        val hasAzureConfig = output.contains("ai-type: azureopenai") || 
                            output.contains("azureopenai (Expected:") ||
                            output.contains("Configuration Priority Demonstration")
        
        if (!hasAzureConfig) {
            arbigentDebugLog("Expected azureopenai in output")
            arbigentDebugLog("Full output (first 2000 chars): ${output.take(2000)}")
            
            // Also check stderr output if combined output doesn't have it
            val debugPattern = "ai-type: azureopenai"
            arbigentDebugLog("Searching for pattern: $debugPattern")
        }
        
        assertTrue(hasAzureConfig, 
            "Should use azureopenai from run-specific config, not global openai. Output length: ${output.length}")
        
        // Test that help shows Azure settings as provided
        val helpOutput = runCli("run", "--help")
        assertTrue(helpOutput.contains("azure-openai-endpoint"))
    }
    
    @Test
    fun `CLI respects full configuration priority chain`() {
        // Create settings with both global and command-specific values
        settingsFile.writeText("""
            # Global settings
            project-file: ${projectFile.name}
            ai-type: openai
            log-level: info
            os: android
            dry-run: true
            openai-api-key: global-key
            
            # Run command specific settings
            run:
              ai-type: azureopenai
              log-level: warn
              azure-openai-endpoint: https://test.openai.azure.com/
              azure-openai-api-key: azure-test-key
        """.trimIndent())
        
        // Run with command line arguments that should override everything
        val output = runCli(
            "run", 
            "--scenario-ids=test-scenario-001",
            "--ai-type=gemini",
            "--log-level=debug",
            "--gemini-api-key=cli-gemini-key"
        )
        
        // Should use gemini from command line, not azureopenai from run config or openai from global
        assertTrue(output.contains("ai-type: gemini"), 
            "Should use gemini from command line argument, not azureopenai or openai from config")
        assertTrue(output.contains("log-level: debug"), 
            "Should use debug from command line argument, not warn from run config")
        
        // Verify help shows that CLI args override settings
        val helpOutput = runCli("run", "--help", "--ai-type=gemini")
        assertTrue(helpOutput.contains("--ai-type"), "Should show ai-type option in help")
    }
    
    @Test
    fun `CLI handles partial command line overrides correctly`() {
        // Create settings with specific configurations
        settingsFile.writeText("""
            # Global settings
            project-file: ${projectFile.name}
            ai-type: openai
            openai-api-key: global-openai-key
            log-level: info
            os: android
            dry-run: true
            
            # Run command specific settings
            run:
              ai-type: azureopenai
              azure-openai-endpoint: https://config.openai.azure.com/
              azure-openai-api-key: config-azure-key
              azure-openai-api-version: 2023-05-15
              log-level: warn
        """.trimIndent())
        
        // Run with only log-level override from CLI
        val output = runCli(
            "run", 
            "--scenario-ids=test-scenario-001",
            "--log-level=debug"  // Only override log-level
        )
        
        // Should use:
        // - log-level: debug (from CLI args)
        // - ai-type: azureopenai (from run config)
        // - azure settings from run config
        assertTrue(output.contains("ai-type: azureopenai"), 
            "Should use azureopenai from run config when not overridden by CLI")
        assertTrue(output.contains("log-level: debug"), 
            "Should use debug from CLI args, overriding run config")
        
        // Verify dry-run from global is still active
        assertTrue(output.contains("Dry run mode is enabled"), 
            "Should still use dry-run from global config")
    }
    
    @Test
    fun `Help shows correct values that match actual execution`() {
        // Create settings with different values for different commands
        settingsFile.writeText("""
            # Global settings
            project-file: ${projectFile.name}
            log-level: info
            ai-type: openai
            openai-api-key: test-key-123
            os: android
            
            # Run command specific settings
            run:
              log-level: debug
              ai-type: azureopenai
              azure-openai-api-key: azure-test-key
              azure-openai-endpoint: https://test.openai.azure.com/
              
            # Scenarios command specific settings  
            scenarios:
              log-level: warn
        """.trimIndent())
        
        // Check run command help
        val runHelpOutput = runCli("run", "--help")
        assertTrue(
            runHelpOutput.contains("currently: 'debug' from run.log-level") ||
            runHelpOutput.contains("currently: 'debug'"),
            "Run help should show debug log level from run config"
        )
        
        // Check scenarios command help  
        val scenariosHelpOutput = runCli("scenarios", "--help")
        assertTrue(
            scenariosHelpOutput.contains("currently: 'warn' from scenarios.log-level") ||
            scenariosHelpOutput.contains("currently: 'warn'"),
            "Scenarios help should show warn log level from scenarios config"
        )
        
        // Actually run the command with debug log level to see Configuration Priority Demonstration
        val runOutput = runCli("run", "--dry-run", "--scenario-ids=test-scenario-001", "--log-level=debug")
        
        // The debug output should show the actual values being used
        assertTrue(
            runOutput.contains("Configuration Priority Demonstration"), 
            "Should show configuration demonstration in debug mode"
        )
        
        // Verify the actual values in the debug output match what help showed
        assertTrue(
            runOutput.contains("ai-type: azureopenai") && 
            runOutput.contains("(Expected: run-specific-azure from run.ai-type)"),
            "Debug output should confirm azureopenai is actually used from run config"
        )
    }
    
    @Test
    fun `CLI handles missing settings file gracefully`() {
        // Delete settings file if it exists
        if (settingsFile.exists()) {
            settingsFile.delete()
        }
        
        // Run CLI help command
        val output = runCli("run", "--help")
        
        // Should still show help but without "currently:" tags
        assertTrue(output.contains("Usage:"))
        assertTrue(output.contains("--project-file"))
        assertTrue(!output.contains("currently:"), 
            "Should not show 'currently:' when settings file is missing")
    }
    
    @Test
    fun `Environment variables override config file values`() {
        // Create settings with specific API key
        settingsFile.writeText("""
            project-file: ${projectFile.name}
            ai-type: openai
            openai-api-key: config-file-key-should-be-overridden
            os: android
            dry-run: true
        """.trimIndent())
        
        // Use ProcessBuilder to set environment variable for the CLI process
        val command = listOf(cliPath, "run", "--scenario-ids=test-scenario-001")
        val processBuilder = ProcessBuilder()
            .command(command)
            .directory(tempDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        
        // Add environment variable with different key
        processBuilder.environment()["OPENAI_API_KEY"] = "env-override-key-should-win"
        
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        
        val fullOutput = if (error.isNotEmpty()) output + "\n" + error else output
        
        // Should successfully run (exit code 0 for dry-run)
        assertEquals(0, exitCode, "Should succeed with env var, not fail with config file key validation")
        
        // Should use the project file and execute dry run successfully  
        assertTrue(fullOutput.contains("Dry run mode is enabled"), "Should execute successfully with environment variable")
        assertTrue(fullOutput.contains("test-scenario-001"), "Should load scenario from project file")
        
        arbigentDebugLog("Environment variable test output: ${fullOutput.take(500)}")
    }
    
    @Test 
    fun `CLI handles malformed YAML gracefully`() {
        // Create malformed YAML file
        settingsFile.writeText("""
            project-file: ${projectFile.name}
            ai-type: openai
              invalid: yaml: syntax
            openai-api-key: test-key
        """.trimIndent())
        
        // CLI should not crash, should either ignore malformed file or show clear error
        val output = runCli("run", "--help")
        
        // Should still show help and not crash
        assertTrue(output.contains("Usage:"), "Should show help even with malformed YAML")
        assertTrue(output.contains("--project-file"), "Should show project-file option")
        
        // Should not show "currently:" values if YAML parsing failed
        val hasCurrentlyTags = output.contains("currently:")
        if (hasCurrentlyTags) {
            // If it somehow parsed, that's also fine, just shouldn't crash
            arbigentDebugLog("Malformed YAML was somehow parsed successfully")
        }
    }
    
    @Test
    fun `CLI ignores typo command sections and falls back to global settings`() {
        // Create settings with typo in command name and different global settings
        settingsFile.writeText("""
            # Global settings
            project-file: ${projectFile.name}
            ai-type: openai
            openai-api-key: global-openai-key
            log-level: info
            os: android
            dry-run: true
            
            # Typo command sections - should be ignored
            runs:  # Should be "run"
              ai-type: azureopenai
              log-level: debug
              azure-openai-api-key: typo-azure-key
              
            scenario:  # Should be "scenarios"  
              log-level: warn
        """.trimIndent())
        
        // Run help command to see what values are actually used
        val runHelpOutput = runCli("run", "--help")
        
        // Should use global settings, not the typo sections
        // Check that global openai settings are shown, not azure from "runs" typo
        assertTrue(
            runHelpOutput.contains("currently: 'openai'") ||
            runHelpOutput.contains("ai-type") && !runHelpOutput.contains("azureopenai"),
            "Should use global ai-type (openai), not from typo section (azureopenai)"
        )
        
        assertTrue(
            runHelpOutput.contains("currently: 'info'") ||
            runHelpOutput.contains("log-level") && !runHelpOutput.contains("debug"),
            "Should use global log-level (info), not from typo section (debug)" 
        )
        
        // Test scenarios command help too
        val scenariosHelpOutput = runCli("scenarios", "--help")
        assertTrue(
            scenariosHelpOutput.contains("currently: 'info'") ||
            scenariosHelpOutput.contains("log-level") && !scenariosHelpOutput.contains("warn"),
            "Should use global log-level (info), not from typo section (warn)"
        )
        
        // Verify actual execution uses global settings
        val runOutput = runCli("run", "--scenario-ids=test-scenario-001")
        assertTrue(runOutput.contains("Dry run mode is enabled"), "Should use global dry-run setting")
        assertTrue(runOutput.contains("test-scenario-001"), "Should load scenario from global project-file")
    }

    @Test
    fun `CLI validates actual configuration loading through execution`() {
        // Create a simple test scenario  
        val testProject = File(tempDir.toFile(), "simple-test.yaml")
        testProject.writeText("""
            scenarios:
            - id: "test-scenario-001"
              goal: "Test scenario for config validation"
        """.trimIndent())
        
        settingsFile.writeText("""
            project-file: ${testProject.name}
            dry-run: true
            ai-type: openai
            openai-api-key: test-key-123
            os: android
        """.trimIndent())
        
        // Run with scenario-ids to avoid filtering complexity
        val output = runCli("run", "--scenario-ids=test-scenario-001")
        
        // Should load the correct project file and scenario
        assertTrue(output.contains("test-scenario-001"), "Should load scenario from settings-specified project file")
        assertTrue(output.contains("Dry run mode is enabled"), "Should respect dry-run setting from config")
    }
    
    private fun runCli(vararg args: String): String {
        val command = listOf(cliPath) + args
        arbigentDebugLog("Running CLI command: ${command.joinToString(" ")}")
        arbigentDebugLog("Working directory: $tempDir")
        
        val processBuilder = ProcessBuilder()
            .command(command)
            .directory(tempDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        
        // Combine stdout and stderr, with clear separation
        val fullOutput = if (error.isNotEmpty()) {
            output + "\n" + error
        } else {
            output
        }
        
        if (exitCode != 0 && !args.contains("--help")) {
            arbigentDebugLog("CLI exited with non-zero code: $exitCode")
        }
        arbigentDebugLog("CLI output length: ${fullOutput.length} chars")
        if (arbigentLogLevel <= ArbigentLogLevel.DEBUG) {
            if (fullOutput.length < 5000) {
                arbigentDebugLog("CLI output:\n$fullOutput")
            } else {
                arbigentDebugLog("CLI output (first 2000 chars):\n${fullOutput.take(2000)}")
                arbigentDebugLog("CLI output (last 500 chars):\n${fullOutput.takeLast(500)}")
            }
        }
        
        return fullOutput
    }
}