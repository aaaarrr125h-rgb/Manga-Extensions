package app.shura.source.host.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Small readers for the repository index.
 *
 * The index is hand published, and the same logical field shows up as a JSON string in one
 * place and a JSON number in another (`versionCode` is `int64` in the schema but quoted in the
 * published file). These helpers accept either, and a missing field is `null` rather than a
 * crash: a field the host does not understand must not take the whole index down.
 */

fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

fun JsonObject.longOrNull(name: String): Long? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    primitive.content.toLongOrNull()?.let { return it }
    return primitive.content.toDoubleOrNull()?.toLong()
}

fun JsonObject.boolOrNull(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

fun JsonObject.objectOrNull(name: String): JsonObject? = this[name].asJsonObject

val JsonElement?.asJsonObject: JsonObject? get() = this as? JsonObject

val JsonElement?.asJsonArray: JsonArray? get() = this as? JsonArray
