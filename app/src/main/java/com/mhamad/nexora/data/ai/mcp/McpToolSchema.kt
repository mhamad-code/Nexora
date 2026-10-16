package com.mhamad.nexora.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema

private const val REF = "\$ref"

/**
 *
 */
internal fun ToolSchema.toSchema(): InputSchema {
    val properties = properties ?: JsonObject(emptyMap())
    val root = JsonObject(buildMap {
        put("type", type ?: JsonPrimitive("object"))
        put("properties", properties)
        defs?.let { put("\$defs", it) }
    })
    return InputSchema.Obj(
        properties = inlineSchemaMap(properties, root, emptySet()),
        required = required,
    )
}

private val SCHEMA_KEYWORDS = setOf(
    "items", "prefixItems", "additionalItems", "unevaluatedItems", "contains",
    "additionalProperties", "unevaluatedProperties", "propertyNames",
    "allOf", "anyOf", "oneOf", "not", "if", "then", "else", "contentSchema",
)

private val SCHEMA_MAP_KEYWORDS = setOf(
    "properties", "patternProperties", "dependentSchemas", "\$defs", "definitions",
)

private fun inlineSchemaMap(schemas: JsonObject, root: JsonObject, resolving: Set<String>): JsonObject =
    JsonObject(schemas.mapValues { inlineSchema(it.value, root, resolving) })

/**
 */
private fun inlineSchema(schema: JsonElement, root: JsonObject, resolving: Set<String>): JsonElement {
    if (schema !is JsonObject) return schema
    val ref = (schema[REF] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val inlined = (if (ref != null) schema - REF else schema).mapValues { (key, value) ->
        when {
            key in SCHEMA_KEYWORDS && value is JsonArray ->
                JsonArray(value.map { inlineSchema(it, root, resolving) })

            key in SCHEMA_KEYWORDS -> inlineSchema(value, root, resolving)
            key in SCHEMA_MAP_KEYWORDS && value is JsonObject -> inlineSchemaMap(value, root, resolving)
            else -> value
        }
    }
    if (ref == null) return JsonObject(inlined)

    val target = root.resolvePointer(ref) as? JsonObject
    val expanded = when {
        target == null -> emptyMap()
        ref in resolving -> target.filterKeys { it == "type" }
        else -> inlineSchema(target, root, resolving + ref) as JsonObject
    }
    return JsonObject(expanded + inlined)
}

private fun JsonObject.resolvePointer(ref: String): JsonElement? {
    if (!ref.startsWith("#")) return null
    val tokens = ref.removePrefix("#").split("/").drop(1)
        .map { it.replace("~1", "/").replace("~0", "~") }
    return tokens.fold<String, JsonElement?>(this) { current, token ->
        when (current) {
            is JsonObject -> current[token]
            is JsonArray -> token.toIntOrNull()?.let(current::getOrNull)
            else -> null
        }
    }
}
