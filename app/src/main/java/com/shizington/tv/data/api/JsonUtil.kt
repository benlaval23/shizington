package com.shizington.tv.data.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

/**
 * Xtream panels are loose with JSON types (numbers as strings, arrays where objects are expected,
 * `[]` for empty objects). Everything here is parsed defensively.
 */
object JsonUtil {

    /**
     * Streams a top-level JSON array of objects, flattening each object into string fields.
     * Arrays of primitives keep their first element; nested objects are skipped. This keeps memory
     * low for catalogues with tens of thousands of entries.
     */
    fun <T> readObjectArray(reader: Reader, mapper: (Map<String, String>) -> T?): List<T> {
        val json = JsonReader(reader).apply { setStrictness(Strictness.LENIENT) }
        if (json.peek() != JsonToken.BEGIN_ARRAY) {
            json.skipValue()
            return emptyList()
        }
        val out = ArrayList<T>()
        val fields = HashMap<String, String>(32)
        json.beginArray()
        while (json.hasNext()) {
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue(); continue
            }
            fields.clear()
            json.beginObject()
            while (json.hasNext()) {
                val name = json.nextName()
                readFlat(json)?.let { fields[name] = it }
            }
            json.endObject()
            mapper(fields)?.let(out::add)
        }
        json.endArray()
        return out
    }

    private fun readFlat(json: JsonReader): String? = when (json.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> json.nextString()
        JsonToken.BOOLEAN -> json.nextBoolean().toString()
        JsonToken.NULL -> { json.nextNull(); null }
        JsonToken.BEGIN_ARRAY -> {
            var first: String? = null
            json.beginArray()
            while (json.hasNext()) {
                val t = json.peek()
                if (first == null && (t == JsonToken.STRING || t == JsonToken.NUMBER)) first = json.nextString()
                else json.skipValue()
            }
            json.endArray()
            first
        }
        else -> { json.skipValue(); null }
    }
}

fun JsonElement?.asObj(): JsonObject? = if (this != null && isJsonObject) asJsonObject else null
fun JsonElement?.asArr(): JsonArray? = if (this != null && isJsonArray) asJsonArray else null

/** First non-blank string among [keys]; arrays yield their first primitive element. */
fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val e = get(k) ?: continue
        val s = when {
            e.isJsonPrimitive -> e.asString
            e.isJsonArray -> e.asJsonArray.firstOrNull { it.isJsonPrimitive }?.asString
            else -> null
        }
        if (!s.isNullOrBlank()) return s.trim()
    }
    return null
}

fun String.toLongId(): Long? = toLongOrNull() ?: toDoubleOrNull()?.toLong()
fun String?.nullIfBlank(): String? = if (isNullOrBlank()) null else this
