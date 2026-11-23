package com.techloungegr.homelabmanagement.model

import kotlinx.serialization.json.*

val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; prettyPrint = false }

fun parseJson(text: String?): JsonElement? =
    try { if (text.isNullOrBlank()) null else jsonParser.parseToJsonElement(text.trim()) } catch (_: Exception) { null }

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray? get() = this as? JsonArray

/** A JSON value as a list of objects, whether it is an array, a single object, or an object wrapping `rows`. */
fun JsonElement?.rows(): List<JsonObject> = when (this) {
    is JsonArray -> this.mapNotNull { it as? JsonObject }
    is JsonObject -> (this["rows"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOf(this)
    else -> emptyList()
}

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeIf { it !is JsonNull }

/** TrueNAS wraps many values as {"parsed": x, "rawvalue": ...}; unwrap those transparently. */
private fun unwrap(e: JsonElement?): JsonPrimitive? = when (e) { is JsonPrimitive -> e; is JsonObject -> e["parsed"] as? JsonPrimitive; else -> null }

fun JsonElement?.str(key: String): String? {
    val p = unwrap(this[key]) ?: return null
    return if (p is JsonNull) null else p.content
}
fun JsonElement?.str(vararg keys: String): String? { for (k in keys) str(k)?.let { return it }; return null }

fun JsonElement?.num(key: String): Double? {
    val p = unwrap(this[key]) ?: return null
    val d = p.doubleOrNull ?: p.content.trim().toDoubleOrNull() ?: return null
    return if (d.isNaN() || d.isInfinite()) null else d
}
fun JsonElement?.num(vararg keys: String): Double? { for (k in keys) num(k)?.let { return it }; return null }

fun JsonElement?.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.booleanOrNull ?: when (p.content.lowercase()) { "true", "yes", "1" -> true; "false", "no", "0" -> false; else -> null }
}

fun JsonElement?.list(key: String): List<JsonElement> = (this[key] as? JsonArray)?.toList() ?: emptyList()
fun JsonElement?.strList(key: String): List<String> = list(key).mapNotNull { (it as? JsonPrimitive)?.content }

/** Section parser for the `##NAME` marked script output used by the Windows/Linux/macOS collectors. */
fun sections(text: String): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    var cur: MutableList<String>? = null
    for (line in text.split("\n")) {
        val l = line.trimEnd('\r')
        if (l.startsWith("##")) { cur = mutableListOf(); out[l.substring(2).trim()] = cur; continue }
        cur?.add(l)
    }
    return out
}
