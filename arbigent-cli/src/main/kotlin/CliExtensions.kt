package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.option
import org.yaml.snakeyaml.Yaml
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
    val helpTags = getHelpTagsForOption(optionKey)
    
    return option(
        *names,
        help = help,
        metavar = metavar,
        hidden = hidden,
        envvar = envvar,
        helpTags = helpTags,
        completionCandidates = completionCandidates,
        valueSourceKey = valueSourceKey,
        eager = eager
    )
}

/**
 * Checks if an option is provided by the settings file and returns appropriate help tags.
 * 
 * @param optionKey The option key to check (without -- prefix)
 * @return Map with help tags if the option is provided by settings file, empty map otherwise
 */
private fun getHelpTagsForOption(optionKey: String): Map<String, String> {
    val settingsFile = File(".arbigent/settings.local.yml")
    if (settingsFile.exists()) {
        try {
            val yaml = Yaml()
            val settings = settingsFile.inputStream().use { 
                yaml.load<Map<String, Any>>(it) ?: emptyMap()
            }
            
            // Check both with and without run prefix (for subcommand-specific options)
            val hasValue = containsKey(settings, optionKey) || 
                          containsKey(settings, "run.$optionKey")
            
            if (hasValue) {
                return mapOf("source" to "already provided by settings file")
            }
        } catch (e: Exception) {
            // Ignore parsing errors for help text generation
        }
    }
    return emptyMap()
}

/**
 * Check if a key exists in a nested map structure.
 */
private fun containsKey(map: Map<String, Any>, key: String): Boolean {
    val keys = key.split(".")
    var current: Any? = map
    
    for (keyPart in keys) {
        if (current is Map<*, *>) {
            current = current[keyPart]
        } else {
            return false
        }
    }
    
    return current != null
}