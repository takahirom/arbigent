package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.Option
import com.github.ajalt.clikt.sources.MapValueSource
import com.github.ajalt.clikt.sources.ValueSource
import com.charleskorn.kaml.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * A [ValueSource] that reads from YAML files.
 */
object YamlValueSource {
    /**
     * Parse a YAML [file] into a value source.
     *
     * If the [file] does not exist, an empty value source will be returned.
     *
     * @param file The file to read from.
     * @param requireValid If true, an [InvalidFileFormat] will be thrown if the file doesn't parse correctly.
     * @param getKey A function that will return the property key for a given option. You can use
     *   [ValueSource.getKey] for most use cases.
     */
    fun from(
        file: Path,
        requireValid: Boolean = false,
        getKey: (Context, Option) -> String = ValueSource.getKey(joinSubcommands = "."),
    ): ValueSource {
        val yamlData = mutableMapOf<String, String>()
        
        if (Files.isRegularFile(file)) {
            try {
                val content = Files.readString(file)
                // Parse YAML using KAML library
                val yaml = Yaml.default
                val root = yaml.parseToYamlNode(content)
                yamlData.putAll(flattenYamlNode(root))
            } catch (e: Throwable) {
                if (requireValid) throw InvalidFileFormat(
                    file.toString(),
                    e.message ?: "could not read file"
                )
            }
        }

        return MapValueSource(yamlData, getKey)
    }

    /**
     * Parse a YAML [file] into a value source.
     *
     * If the [file] does not exist, an empty value source will be returned.
     *
     * @param file The file to read from.
     * @param requireValid If true, an [InvalidFileFormat] will be thrown if the file doesn't parse correctly.
     * @param getKey A function that will return the property key for a given option. You can use
     *   [ValueSource.getKey] for most use cases.
     */
    fun from(
        file: File,
        requireValid: Boolean = false,
        getKey: (Context, Option) -> String = ValueSource.getKey(joinSubcommands = "."),
    ): ValueSource {
        return from(file.toPath(), requireValid, getKey)
    }

    /**
     * Parse a YAML [file] into a value source.
     *
     * If the [file] does not exist, an empty value source will be returned.
     *
     * @param file The file to read from.
     * @param requireValid If true, an [InvalidFileFormat] will be thrown if the file doesn't parse correctly.
     * @param getKey A function that will return the property key for a given option. You can use
     *   [ValueSource.getKey] for most use cases.
     */
    fun from(
        file: String,
        requireValid: Boolean = false,
        getKey: (Context, Option) -> String = ValueSource.getKey(joinSubcommands = "."),
    ): ValueSource = from(File(file), requireValid, getKey)

    private fun flattenYamlNode(node: YamlNode, prefix: String = ""): Map<String, String> {
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
                    result.putAll(flattenYamlNode(value, fullKey))
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
                result.putAll(flattenYamlNode(node.innerNode, prefix))
            }
        }
        
        return result
    }
}

/**
 * Exception thrown when a file cannot be parsed.
 */
class InvalidFileFormat(val filename: String, message: String) : Exception("Invalid file format in $filename: $message")