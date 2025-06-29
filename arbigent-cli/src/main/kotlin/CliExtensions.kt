package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.option
import java.io.File
import java.util.Properties

/**
 * Extension function to automatically add helpTags for property file values.
 * This function wraps the standard option() function and automatically detects
 * if the option value is already provided by a property file.
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
 * Checks if an option is provided by the property file and returns appropriate help tags.
 * 
 * @param optionKey The option key to check (without -- prefix)
 * @return Map with help tags if the option is provided by property file, empty map otherwise
 */
private fun getHelpTagsForOption(optionKey: String): Map<String, String> {
    val propertiesFile = File("arbigent.properties")
    if (propertiesFile.exists()) {
        val properties = Properties()
        propertiesFile.inputStream().use { properties.load(it) }
        
        // Check both with and without run prefix (for subcommand-specific options)
        val hasValue = properties.containsKey(optionKey) || 
                      properties.containsKey("run.$optionKey")
        
        if (hasValue) {
            return mapOf("source" to "already provided by property file")
        }
    }
    return emptyMap()
}