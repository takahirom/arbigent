package io.github.takahirom.arbigent.serialization

import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

public sealed interface GenerateJsonSchemaApiType{
    public object OpenAI: GenerateJsonSchemaApiType
    public object Gemini: GenerateJsonSchemaApiType
}

public fun generateRootJsonSchema(
    descriptor: SerialDescriptor,
    apiType: GenerateJsonSchemaApiType,
): JsonObject {
    val definitions = mutableMapOf<String, JsonObject>()
    val mainSchema = generateJsonSchema(
        descriptor = descriptor,
        isOptional = false,
        apiType = apiType,
    )

    return buildJsonObject {
//        put("\$schema", JsonPrimitive("http://json-schema.org/draft-07/schema#"))
        // Copy all properties from mainSchema
        put("schema", mainSchema!!)
        put("name", JsonPrimitive(descriptor.serialName.substringAfterLast(".")))
        if (definitions.isNotEmpty()) {
//            put("\$defs", JsonObject(definitions))
        }
    }
}

public fun generateJsonSchema(
    name: String = "root",
    descriptor: SerialDescriptor,
    isOptional: Boolean,
    definitions: MutableMap<String, JsonObject> = mutableMapOf(),
    apiType: GenerateJsonSchemaApiType,
): JsonObject? {
    if (descriptor.kind is PolymorphicKind) {
        // OpenAI json schema doesn't support allOf so we need to ignore it for now
        return null
    }
    if( descriptor.kind is StructureKind.LIST && descriptor.getElementDescriptor(0).kind is PolymorphicKind) {
        // Contextual types are not supported in this implementation
        return null
    }
    val serialName = name

    // Check if we've already processed this descriptor to handle recursive types
    if (definitions.containsKey(serialName)) {
        return buildJsonObject {
            put("\$ref", JsonPrimitive("#/\$defs/$serialName"))
        }
    }

    // Add a placeholder to prevent infinite recursion for recursive types
    // Only add placeholder if it's a structure that might be referenced
    if (descriptor.kind is StructureKind || descriptor.kind == SerialKind.ENUM) {
        definitions[serialName] = buildJsonObject {} // Placeholder
    }

    val schema = buildJsonObject {
        put("name", JsonPrimitive(serialName.substringAfterLast(".")))

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
                putNullableIfNeeded(descriptor.isNullable, apiType)

                if (descriptor.isNullable || isOptional) {
                    putType(type, apiType)
                } else {
                    put("type", JsonPrimitive(type))
                }
                put("description", JsonPrimitive("Schema for $serialName"))
            }

            // Handle class and object structures
            StructureKind.CLASS, StructureKind.OBJECT -> {
                if (descriptor.isNullable || isOptional) {
                    putType("object", apiType)
                } else {
                    put("type", JsonPrimitive("object"))
                }

                val properties = buildJsonObject {
                    for (i in 0 until descriptor.elementsCount) {
                        val elementName = descriptor.getElementName(i)
                        val elementDescriptor = descriptor.getElementDescriptor(i)
                        val schema = generateJsonSchema(
                            name = name +"_" +elementName,
                            elementDescriptor,
                            descriptor.isElementOptional(i),
                            definitions,
                            apiType,
                        )
                        schema?.let {
                            put(elementName, schema)
                        }
                    }
                }
                if (properties.isNotEmpty()) {
                    put("properties", properties)
                } else {
                    // Handle empty objects if necessary, e.g., Any or custom serializers
                    // put("properties", buildJsonObject {}) // Or omit properties entirely
                }


                val requiredProperties = buildJsonArray {
                    for (i in 0 until descriptor.elementsCount) {
                        // According to OpenAI docs, all properties must be required for strict mode
                        when (apiType) {
                            GenerateJsonSchemaApiType.OpenAI -> {
                                add(JsonPrimitive(descriptor.getElementName(i)))
                            }

                            GenerateJsonSchemaApiType.Gemini -> {
                                // Gemini might have different requirements, adjust as needed
                                if (!descriptor.isElementOptional(i)) {
                                    add(JsonPrimitive(descriptor.getElementName(i)))
                                }
                            }
                        }
                    }
                }

                if (requiredProperties.size > 0) {
                    put("required", requiredProperties)
                }

                // According to OpenAI docs, additionalProperties: false is required
                put("additionalProperties", JsonPrimitive(false))
            }

            // Handle lists and arrays
            StructureKind.LIST -> {
                if (descriptor.isNullable || isOptional) {
                    putType("array", apiType)
                } else {
                    put("type", JsonPrimitive("array"))
                }

                if (descriptor.elementsCount > 0) {
                    val itemsSchema = generateJsonSchema(
                        name = name +"_" + descriptor.getElementName(0),
                        descriptor.getElementDescriptor(0),
                        descriptor.isElementOptional(0),
                        definitions,
                        apiType,
                    )
                    itemsSchema?.let {
                        put("items", itemsSchema)
                    }
                } else {
                    // Handle empty list case if needed, though typically items is defined
                    put("items", buildJsonObject {}) // Represent items as any type (empty schema) or specific default
                }
            }

            // Handle maps
            StructureKind.MAP -> {
                if (descriptor.isNullable || isOptional) {
                    putType("object", apiType)
                } else {
                    put("type", JsonPrimitive("object"))
                }

                // JSON Schema typically uses additionalProperties for map values
                // Assumes String keys, element at index 1 is the value type
                if (descriptor.elementsCount > 1) {
                    val valueSchema = generateJsonSchema(
                        name = name +"_" + descriptor.getElementName(1),
                        descriptor.getElementDescriptor(1),
                        descriptor.isElementOptional(1),
                        definitions,
                        apiType,
                    )
                    valueSchema?.let {
                        put("additionalProperties", valueSchema)
                    }
                } else {
                    // Handle map with non-string keys or unknown value types if necessary
                    // Defaulting to allowing any additional properties if value type is unknown
                    put("additionalProperties", JsonPrimitive(true)) // Or an empty schema {}
                }
                // Note: OpenAI requires additionalProperties: false for objects unless it represents a map's value type.
                // The above logic sets additionalProperties to the value schema, which is correct for map-like structures.
                // If the root type is MAP, this should be fine. If a property *within* an object is a map,
                // the parent object still needs additionalProperties: false.
            }

            // Handle enums
            SerialKind.ENUM -> {
                if (descriptor.isNullable || isOptional) {
                    putType("string", apiType)
                } else {
                    put("type", JsonPrimitive("string"))
                }

                putJsonArray("enum") {
                    for (i in 0 until descriptor.elementsCount) {
                        add(JsonPrimitive(descriptor.getElementName(i)))
                    }
                }
            }

            // Handle contextual serialization
            SerialKind.CONTEXTUAL -> {
                // Contextual can resolve to anything, often treated as any type or requires specific handling
                if (descriptor.isNullable || isOptional) {
                    // Representing as potentially null and any type (object allows flexibility)
                    putType("object", apiType)
                } else {
                    put("type", JsonPrimitive("object")) // Fallback
                }
                put("description", JsonPrimitive("Contextual type: ${descriptor.serialName} (actual schema depends on context)"))
                // OpenAI might require a more concrete schema here.
                // Consider replacing this with a reference if the contextual type resolves to a defined schema.
            }

            // Handle polymorphic types
            is PolymorphicKind -> {
//                // Polymorphic types are complex. A common JSON schema pattern is oneOf/anyOf
//                // This basic implementation just marks it as an object.
//                if (descriptor.isNullable) {
//                    putJsonArray("type") {
//                        add(JsonPrimitive("object"))
//                        add(JsonPrimitive("null"))
//                    }
//                } else {
//                    put("type", JsonPrimitive("object"))
//                }
//                fun getDescriptors(descriptor: SerialDescriptor, module: SerializersModule):List<SerialDescriptor> = when (descriptor.kind) {
//                    is PolymorphicKind.OPEN -> module.getPolymorphicDescriptors(descriptor)
//                    is PolymorphicKind.SEALED -> module.getPolymorphicDescriptors(descriptor)
//                    is SerialKind.CONTEXTUAL -> listOfNotNull(module.getContextualDescriptor(descriptor))
//                    else -> throw IllegalArgumentException("Unsupported kind: ${descriptor.kind}")
//                }
//                val descriptors = getDescriptors(descriptor, SerializersModule {  })
//                for (subDescriptor in descriptors) {
//                    val subSchema = generateJsonSchema(subDescriptor, definitions, serializersModule)
//                    put("oneOf", buildJsonArray {
//                        add(subSchema)
//                    })
//                }
            }

            // Default fallback for unsupported kinds
            else -> {
                if (descriptor.isNullable || isOptional) {
                    putType("object", apiType)
                } else {
                    put("type", JsonPrimitive("object")) // Fallback
                }
                put("description", JsonPrimitive("Unsupported SerialKind: ${descriptor.kind}. Represented as object."))
                // Consider adding additionalProperties: false if strictness is needed
                put("additionalProperties", JsonPrimitive(false))
            }
        }
    }

    // Replace placeholder with the actual schema if it's a structure/enum
    if (definitions.containsKey(serialName)) {
        // Only update if it was a placeholder for a structure/enum
        if (descriptor.kind is StructureKind || descriptor.kind == SerialKind.ENUM) {
            definitions[serialName] = schema
        } else {
            // If it wasn't a structure/enum, it shouldn't have been added as a placeholder initially.
            // Or remove the key if it's a primitive/simple type not meant for $defs.
            // For safety, let's assume primitives don't get added to definitions this way.
        }
    }


    // If the schema itself is a reference, return the reference directly.
    // This happens if we encounter the same type again during recursion.
    if (schema.containsKey("\$ref")) {
        return schema
    }


    // If the current descriptor is a structure or enum kind, and not already just a reference,
    // ensure it's properly stored in definitions for potential future reference.
    // This check seems redundant given the placeholder logic, but ensures correctness.
    if ((descriptor.kind is StructureKind || descriptor.kind == SerialKind.ENUM) && !definitions.containsKey(serialName)) {
        definitions[serialName] = schema
    } else if (definitions.containsKey(serialName) && definitions[serialName]?.isEmpty() == true) {
        // Update placeholder if it was empty
        definitions[serialName] = schema
    }


    return schema
}

private fun JsonObjectBuilder.putNullableIfNeeded(
    nullable: Boolean,
    apiType: io.github.takahirom.arbigent.serialization.GenerateJsonSchemaApiType
) {
    if (apiType is GenerateJsonSchemaApiType.Gemini && nullable) {
        put("nullable", true)
    }
}

private fun JsonObjectBuilder.putType(type: String, apiType: GenerateJsonSchemaApiType) {
    when(apiType) {
        GenerateJsonSchemaApiType.OpenAI -> putJsonArray("type") {
            add(JsonPrimitive(type))
            add(JsonPrimitive("null"))
        }

        GenerateJsonSchemaApiType.Gemini -> put("type", JsonPrimitive(type))
    }
}


// Helper extension function for putting JsonArray concisely
private fun JsonObjectBuilder.putJsonArray(key: String, builderAction: JsonArrayBuilder.() -> Unit) {
    put(key, buildJsonArray(builderAction))
}
