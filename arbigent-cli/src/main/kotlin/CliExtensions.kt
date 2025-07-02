package io.github.takahirom.arbigent.cli

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
            val content = settingsFile.readText()
            val yaml = Yaml.default
            val root = yaml.parseToYamlNode(content)
            val settings = flattenYamlToMap(root)
            
            // Check if the key exists in the flattened map
            val hasValue = settings.containsKey(optionKey) || 
                          settings.containsKey("run.$optionKey")
            
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