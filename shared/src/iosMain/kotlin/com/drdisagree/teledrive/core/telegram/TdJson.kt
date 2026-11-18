package com.drdisagree.teledrive.core.telegram

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Minimal helpers over TDLib's JSON encoding: objects carry "@type", int64 values arrive as strings,
 * bytes as base64, and omitted optional fields take TDLib's defaults (so requests name only what
 * they set, which also keeps them valid across schema additions).
 */
internal object TdJson {

    fun obj(type: String, vararg fields: Pair<String, Any?>): JsonObject {
        val map = LinkedHashMap<String, JsonElement>(fields.size + 1)
        map["@type"] = JsonPrimitive(type)
        for ((key, value) in fields) {
            if (value != null) map[key] = element(value)
        }
        return JsonObject(map)
    }

    fun formattedText(text: String): JsonObject =
        obj("formattedText", "text" to text, "entities" to emptyList<Any>())

    private fun element(value: Any): JsonElement = when (value) {
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is ByteArray -> JsonPrimitive(Base64.encode(value))
        is LongArray -> JsonArray(value.map { JsonPrimitive(it) })
        is Iterable<*> -> JsonArray(value.filterNotNull().map { element(it) })
        else -> error("Unsupported TDLib JSON value: ${value::class.simpleName}")
    }
}

internal val JsonObject.type: String? get() = str("@type")

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let {
    it.intOrNull ?: it.contentOrNull?.toIntOrNull()
}

/** int53 arrives as a number, int64 as a string; both are accepted. */
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let {
    it.longOrNull ?: it.contentOrNull?.toLongOrNull() ?: it.doubleOrNull?.toLong()
}

internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.let {
    it.booleanOrNull ?: (it.contentOrNull == "true")
} ?: false

internal fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

internal fun JsonObject.objects(key: String): List<JsonObject> =
    arr(key).filterIsInstance<JsonObject>()

internal fun JsonObject.longs(key: String): List<Long> = arr(key).mapNotNull { element ->
    (element as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
}

internal fun JsonObject.strings(key: String): List<String> = arr(key).mapNotNull { element ->
    (element as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}

internal fun JsonObject.bytes(key: String): ByteArray? =
    str(key)?.takeIf { it.isNotEmpty() }?.let { runCatching { Base64.decode(it) }.getOrNull() }
