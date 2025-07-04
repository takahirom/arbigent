package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.option
import com.charleskorn.kaml.*
import java.io.File

/**
 * Extension function to automatically add helpTags for settings file values.
 * This function wraps the standard option() function and automatically detects
 * if the option value is already provided by a settings file.
 */
fun ParameterHolder.defaultOption(
    vararg names: String,
    help: String = "",
    metavar: String? = null,
    hidden: Boolean = false,
    envvar: String? = null,
    completionCandidates: CompletionCandidates? = null,
    valueSourceKey: String? = null,
    eager: Boolean = false,
): OptionWithValues<String?, String, String> {
    val optionKey = names.firstOrNull()?.removePrefix("--") ?: ""
    
    // Try to get the command name from the ParameterHolder (CliktCommand)
    val commandName = when (this) {
        is CliktCommand -> this.commandName
        else -> null
    }
    
    val settingsInfo = getSettingsInfoForOption(optionKey, commandName)
    
    // Append settings info to help text if available
    val enhancedHelp = if (settingsInfo != null) {
        val sourceDescription = when {
            settingsInfo.source.contains(".") -> settingsInfo.source
            settingsInfo.source == "global settings" -> "global settings"
            else -> settingsInfo.source
        }
        "$help (currently: '${settingsInfo.value}' from $sourceDescription)"
    } else {
        help
    }
    
    return option(
        *names,
        help = enhancedHelp,
        metavar = metavar,
        hidden = hidden,
        envvar = envvar,
        helpTags = emptyMap(), // Clear help tags since we're using help text
        completionCandidates = completionCandidates,
        valueSourceKey = valueSourceKey,
        eager = eager
    )
}

/**
 * Data class to hold settings information
 */
private data class SettingsInfo(
    val value: String,
    val source: String
)

/**
 * Checks if an option is provided by the settings file and returns settings info.
 * 
 * @param optionKey The option key to check (without -- prefix)
 * @param commandName The name of the current command (e.g., "run", "scenarios")
 * @return SettingsInfo if the option is provided by settings file, null otherwise
 */
private fun getSettingsInfoForOption(optionKey: String, commandName: String?): SettingsInfo? {
    val settingsFile = File(".arbigent/settings.local.yml")
    
    if (settingsFile.exists()) {
        try {
            val content = settingsFile.readText()
            val yaml = Yaml.default
            val root = yaml.parseToYamlNode(content)
            val settings = flattenYamlToMap(root)
            
            // First check command-specific settings if we know the command
            if (commandName != null) {
                val commandSpecificKey = "$commandName.$optionKey"
                if (settings.containsKey(commandSpecificKey)) {
                    return SettingsInfo(
                        value = settings[commandSpecificKey] ?: "",
                        source = commandSpecificKey
                    )
                }
            }
            
            // Then check global setting
            if (settings.containsKey(optionKey)) {
                val value = settings[optionKey] ?: ""
                return SettingsInfo(
                    value = value,
                    source = "global settings"
                )
            }
        } catch (e: Exception) {
            // Ignore parsing errors for help text generation
        }
    }
    return null
}

/**
 * Flatten a YAML node into a map of string keys to string values.
 */
private fun flattenYamlToMap(node: YamlNode, prefix: String = ""): Map<String, String> {
    val result = mutableMapOf<String, String>()
    
    when (node) {
        is YamlMap -> {
            // Iterate over map entries
            for ((key, value) in node.entries) {
                val keyStr = when (key) {
                    is YamlScalar -> key.content
                    else -> key.toString()
                }
                val fullKey = if (prefix.isEmpty()) keyStr else "$prefix.$keyStr"
                result.putAll(flattenYamlToMap(value, fullKey))
            }
        }
        is YamlList -> {
            // Convert list to comma-separated string
            val listValues = node.items.map { item ->
                when (item) {
                    is YamlScalar -> item.content
                    else -> item.toString()
                }
            }
            result[prefix] = listValues.joinToString(",")
        }
        is YamlScalar -> {
            result[prefix] = node.content
        }
        is YamlNull -> {
            result[prefix] = ""
        }
        is YamlTaggedNode -> {
            // Recursively process the inner node
            result.putAll(flattenYamlToMap(node.innerNode, prefix))
        }
    }
    
    return result
}