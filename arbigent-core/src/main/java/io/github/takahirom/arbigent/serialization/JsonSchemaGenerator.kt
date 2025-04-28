package io.github.takahirom.arbigent.serialization

import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

/**
 * Generates a JSON Schema (Draft 7) from a Kotlin serialization descriptor.
 *
 * This function takes a [SerialDescriptor] as input and produces a [JsonObject] representing
 * the JSON Schema that corresponds to the structure defined by the descriptor.
 *
 * @param descriptor The [SerialDescriptor] to convert to JSON Schema
 * @return A [JsonObject] containing the JSON Schema
 */
public fun generateRootJsonSchema(descriptor: SerialDescriptor): JsonObject {
    val definitions = mutableMapOf<String, JsonObject>()
    val mainSchema = generateJsonSchema(descriptor, definitions)

    return buildJsonObject {
        put("\$schema", JsonPrimitive("http://json-schema.org/draft-07/schema#"))
        // Copy all properties from mainSchema
        for (entry in mainSchema.entries) {
            put(entry.key, entry.value)
        }
        if (definitions.isNotEmpty()) {
            put("\$defs", JsonObject(definitions))
        }
    }
}

/**
 * Helper function that generates a JSON Schema object from a [SerialDescriptor].
 *
 * This function handles the recursive traversal of the descriptor structure and
 * maps different Kotlin types to their JSON Schema equivalents.
 *
 * @param descriptor The [SerialDescriptor] to convert
 * @param definitions A mutable map to store reusable schema definitions
 * @return A [JsonObject] representing the JSON Schema for the descriptor
 */
public fun generateJsonSchema(
    descriptor: SerialDescriptor,
    definitions: MutableMap<String, JsonObject> = mutableMapOf()
): JsonObject {
    val serialName = descriptor.serialName

    // Check if we've already processed this descriptor to handle recursive types
    if (definitions.containsKey(serialName)) {
        return buildJsonObject { 
            put("\$ref", JsonPrimitive("#/\$defs/$serialName")) 
        }
    }

    // Add a placeholder to prevent infinite recursion for recursive types
    if (descriptor.kind is StructureKind || descriptor.kind == SerialKind.ENUM) {
        definitions[serialName] = buildJsonObject {}
    }

    val schema = buildJsonObject {
        put("title", JsonPrimitive(serialName))

        when (descriptor.kind) {
            // Handle primitive types
            is PrimitiveKind -> {
                val type = when (descriptor.kind) {
                    PrimitiveKind.BOOLEAN -> "boolean"
                    PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
                    PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
                    PrimitiveKind.CHAR, PrimitiveKind.STRING -> "string"
                    else -> "string" // Default fallback
                }

                if (descriptor.isNullable) {
                    putJsonArray("type") { 
                        add(JsonPrimitive(type))
                        add(JsonPrimitive("null"))
                    }
                } else {
                    put("type", JsonPrimitive(type))
                }
            }

            // Handle class and object structures
            StructureKind.CLASS, StructureKind.OBJECT -> {
                // Handle nullability
                if (descriptor.isNullable) {
                    putJsonArray("type") {
                        add(JsonPrimitive("object"))
                        add(JsonPrimitive("null"))
                    }
                } else {
                    put("type", JsonPrimitive("object"))
                }

                val properties = buildJsonObject {
                    for (i in 0 until descriptor.elementsCount) {
                        val elementName = descriptor.getElementName(i)
                        val elementDescriptor = descriptor.getElementDescriptor(i)
                        put(elementName, generateJsonSchema(elementDescriptor, definitions))
                    }
                }
                put("properties", properties)

                val requiredProperties = buildJsonArray {
                    for (i in 0 until descriptor.elementsCount) {
                        if (!descriptor.getElementDescriptor(i).isNullable) {
                            add(JsonPrimitive(descriptor.getElementName(i)))
                        }
                    }
                }

                if (requiredProperties.size > 0) {
                    put("required", requiredProperties)
                }

                put("additionalProperties", JsonPrimitive(false))
            }

            // Handle lists and arrays
            StructureKind.LIST -> {
                // Handle nullability
                if (descriptor.isNullable) {
                    putJsonArray("type") {
                        add(JsonPrimitive("array"))
                        add(JsonPrimitive("null"))
                    }
                } else {
                    put("type", JsonPrimitive("array"))
                }

                if (descriptor.elementsCount > 0) {
                    val itemsSchema = generateJsonSchema(descriptor.getElementDescriptor(0), definitions)
                    put("items", itemsSchema)
                }
            }

            // Handle maps
            StructureKind.MAP -> {
                // Handle nullability
                if (descriptor.isNullable) {
                    putJsonArray("type") {
                        add(JsonPrimitive("object"))
                        add(JsonPrimitive("null"))
                    }
                } else {
                    put("type", JsonPrimitive("object"))
                }

                // For maps, we assume string keys for simplicity in JSON Schema
                if (descriptor.elementsCount > 1) {
                    // Element at index 1 is the value type
                    val valueSchema = generateJsonSchema(descriptor.getElementDescriptor(1), definitions)
                    put("additionalProperties", valueSchema)
                }
            }

            // Handle enums
            SerialKind.ENUM -> {
                // Handle nullability
                if (descriptor.isNullable) {
                    putJsonArray("type") {
                        add(JsonPrimitive("string"))
                        add(JsonPrimitive("null"))
                    }
                } else {
                    put("type", JsonPrimitive("string"))
                }

                putJsonArray("enum") {
                    for (i in 0 until descriptor.elementsCount) {
                        add(JsonPrimitive(descriptor.getElementName(i)))
                    }
                }
            }

            // Handle contextual serialization (placeholder implementation)
            SerialKind.CONTEXTUAL -> {
                // Handle nullability
                if (descriptor.isNullable) {
                    putJsonArray("type") {
                        add(JsonPrimitive("object"))
                        add(JsonPrimitive("null"))
                    }
                } else {
                    put("type", JsonPrimitive("object"))
                }
                put("description", JsonPrimitive("Contextual type: ${descriptor.serialName}"))
            }

            // Handle polymorphic types (placeholder implementation)
            is PolymorphicKind -> {
                // Handle nullability
                if (descriptor.isNullable) {
                    putJsonArray("type") {
                        add(JsonPrimitive("object"))
                        add(JsonPrimitive("null"))
                    }
                } else {
                    put("type", JsonPrimitive("object"))
                }
                put("description", JsonPrimitive("Polymorphic type: ${descriptor.serialName}"))
            }

            // Default fallback for unsupported kinds
            else -> {
                // Handle nullability
                if (descriptor.isNullable) {
                    putJsonArray("type") {
                        add(JsonPrimitive("object"))
                        add(JsonPrimitive("null"))
                    }
                } else {
                    put("type", JsonPrimitive("object"))
                }
                put("description", JsonPrimitive("Unsupported SerialKind: ${descriptor.kind}"))
            }
        }

        // Nullability for non-primitive types is handled in each case of the when expression
    }

    // Store the final schema in definitions if it's a potentially reusable structure
    if (descriptor.kind is StructureKind || descriptor.kind == SerialKind.ENUM) {
        definitions[serialName] = schema
    }

    return schema
}
