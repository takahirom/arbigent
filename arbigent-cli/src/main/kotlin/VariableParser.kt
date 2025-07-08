package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktError

/**
 * Parses variable input string into a map of key-value pairs.
 * Supports three formats:
 * 1. JSON: {"key":"value", "key2":"value2"}
 * 2. Quoted: key="value", key2='value2'  
 * 3. Simple: key=value,key2=value2
 */
internal fun parseVariables(input: String): Map<String, String> {
    // Try to parse as JSON first
    if (input.trim().startsWith("{") && input.trim().endsWith("}")) {
        return parseJsonVariables(input)
    }
    
    // Parse as key=value pairs
    return parseKeyValueVariables(input)
}

private fun parseJsonVariables(input: String): Map<String, String> {
    try {
        val jsonMap = mutableMapOf<String, String>()
        val cleanJson = input.trim().removeSurrounding("{", "}")
        var i = 0
        while (i < cleanJson.length) {
            // Skip whitespace
            while (i < cleanJson.length && cleanJson[i].isWhitespace()) i++
            if (i >= cleanJson.length) break
            
            // Parse key
            if (cleanJson[i] != '"') throw IllegalArgumentException("Expected quote at position $i")
            i++ // Skip opening quote
            val keyStart = i
            while (i < cleanJson.length && cleanJson[i] != '"') {
                if (cleanJson[i] == '\\' && i + 1 < cleanJson.length) i++ // Skip escaped chars
                i++
            }
            if (i >= cleanJson.length) throw IllegalArgumentException("Unterminated string")
            val key = cleanJson.substring(keyStart, i).replace("\\\"", "\"").replace("\\\\", "\\")
            i++ // Skip closing quote
            
            // Skip whitespace and colon
            while (i < cleanJson.length && cleanJson[i].isWhitespace()) i++
            if (i >= cleanJson.length || cleanJson[i] != ':') throw IllegalArgumentException("Expected colon after key")
            i++ // Skip colon
            while (i < cleanJson.length && cleanJson[i].isWhitespace()) i++
            
            // Parse value
            if (i >= cleanJson.length) throw IllegalArgumentException("Missing value")
            val valueStart = i
            if (cleanJson[i] == '"') {
                // String value
                i++ // Skip opening quote
                val strStart = i
                while (i < cleanJson.length && cleanJson[i] != '"') {
                    if (cleanJson[i] == '\\' && i + 1 < cleanJson.length) i++ // Skip escaped chars
                    i++
                }
                if (i >= cleanJson.length) throw IllegalArgumentException("Unterminated string value")
                val value = cleanJson.substring(strStart, i).replace("\\\"", "\"").replace("\\\\", "\\")
                jsonMap[key] = value
                i++ // Skip closing quote
            } else {
                // Non-string value (number, boolean, object, array)
                var depth = 0
                while (i < cleanJson.length) {
                    when (cleanJson[i]) {
                        '{', '[' -> depth++
                        '}', ']' -> depth--
                        ',' -> if (depth == 0) break
                    }
                    i++
                }
                jsonMap[key] = cleanJson.substring(valueStart, i).trim()
            }
            
            // Skip whitespace and comma
            while (i < cleanJson.length && cleanJson[i].isWhitespace()) i++
            if (i < cleanJson.length && cleanJson[i] == ',') {
                i++ // Skip comma
            }
        }
        
        // Validate variable names
        jsonMap.forEach { (name, _) ->
            if (!isValidVariableName(name)) {
                throw CliktError("Invalid variable name: '$name'. Variable names must start with a letter or underscore and contain only letters, numbers, and underscores.")
            }
        }
        
        return jsonMap
    } catch (e: Exception) {
        throw CliktError("Invalid JSON format for variables: ${e.message}")
    }
}

private fun parseKeyValueVariables(input: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var i = 0
    while (i < input.length) {
        // Skip whitespace
        while (i < input.length && input[i].isWhitespace()) i++
        if (i >= input.length) break
        
        // Parse key
        val keyStart = i
        while (i < input.length && input[i] != '=' && !input[i].isWhitespace()) i++
        val key = input.substring(keyStart, i).trim()
        
        // Skip whitespace before equals
        while (i < input.length && input[i].isWhitespace()) i++
        
        // Expect equals sign
        if (i >= input.length || input[i] != '=') {
            throw CliktError("Invalid variable format at position $i. Expected '=' after key '$key'")
        }
        i++ // Skip equals
        
        // Skip whitespace after equals
        while (i < input.length && input[i].isWhitespace()) i++
        
        // Parse value
        val value: String
        if (i < input.length && (input[i] == '"' || input[i] == '\'')) {
            // Quoted value
            val quote = input[i]
            i++ // Skip opening quote
            val valueStart = i
            while (i < input.length && input[i] != quote) {
                if (input[i] == '\\' && i + 1 < input.length) {
                    i++ // Skip escaped character
                }
                i++
            }
            if (i >= input.length) {
                throw CliktError("Unterminated quoted value for key '$key'")
            }
            value = input.substring(valueStart, i).replace("\\$quote", "$quote").replace("\\\\", "\\")
            i++ // Skip closing quote
        } else {
            // Unquoted value (read until comma or end)
            val valueStart = i
            while (i < input.length && input[i] != ',') i++
            value = input.substring(valueStart, i).trim()
        }
        
        // Validate variable name
        if (!isValidVariableName(key)) {
            throw CliktError("Invalid variable name: '$key'. Variable names must start with a letter or underscore and contain only letters, numbers, and underscores.")
        }
        
        result[key] = value
        
        // Skip comma if present
        while (i < input.length && input[i].isWhitespace()) i++
        if (i < input.length && input[i] == ',') {
            i++ // Skip comma
        }
    }
    
    return result
}

internal fun isValidVariableName(name: String): Boolean {
    if (name.isEmpty()) return false
    if (!name[0].isLetter() && name[0] != '_') return false
    return name.all { it.isLetterOrDigit() || it == '_' }
}