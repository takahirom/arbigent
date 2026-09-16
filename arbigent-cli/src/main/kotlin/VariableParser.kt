package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktError
import io.github.takahirom.arbigent.isArbigentVariableName

/**
 * Parses variable input string into a map of key-value pairs.
 * Supports formats: key=value,key2=value2
 * Quoted values: key="value with spaces",key2='value,with,comma'
 */
internal fun parseVariables(input: String): Map<String, String> {
    if (input.isBlank()) return emptyMap()
    
    val pairs = mutableListOf<String>()
    var current = StringBuilder()
    var inQuotes = false
    var quoteChar: Char? = null
    
    // Split by comma, respecting quotes
    for (char in input) {
        when {
            (char == '"' || char == '\'') && !inQuotes -> {
                inQuotes = true
                quoteChar = char
                current.append(char)
            }
            char == quoteChar && inQuotes -> {
                inQuotes = false
                quoteChar = null
                current.append(char)
            }
            char == ',' && !inQuotes -> {
                if (current.isNotEmpty()) {
                    pairs.add(current.toString())
                    current = StringBuilder()
                }
            }
            else -> current.append(char)
        }
    }
    if (current.isNotEmpty()) {
        pairs.add(current.toString())
    }
    
    return pairs.filter { it.isNotBlank() }
        .associate { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) {
                throw CliktError("Invalid variable format: '$pair'. Expected 'key=value'")
            }
            
            val key = parts[0].trim()
            var value = parts[1].trim()
            
            // Handle quoted values
            if (value.length >= 2) {
                val firstChar = value.first()
                val lastChar = value.last()
                if ((firstChar == '"' && lastChar == '"') || (firstChar == '\'' && lastChar == '\'')) {
                    value = value.substring(1, value.length - 1)
                } else if (firstChar == '"' || firstChar == '\'') {
                    throw CliktError("Unmatched quote in value for key '$key'")
                }
            }
            
            if (!isValidVariableName(key)) {
                throw CliktError("Invalid variable name: '$key'. Variable names must contain only letters, numbers, underscores, dashes, dots, and spaces.")
            }
            
            key to value
        }
}

// The same names a `{{...}}` reference can carry, so a variable the project YAML can define is a
// variable --variables can supply.
internal fun isValidVariableName(name: String): Boolean = isArbigentVariableName(name)