package com.vinoteca.app.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Models occasionally wrap JSON in fences or add a sentence; dig the object out. */
object AiJson {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun objectFrom(raw: String): JsonObject {
        val trimmed = stripFences(raw)
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        val candidate = if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
        runCatching { json.parseToJsonElement(candidate).jsonObject }.getOrNull()?.let { return it }
        // A long answer over a big wine list is the case that gets cut off at the token limit, and
        // throwing there loses every wine that did arrive. Salvage the complete objects instead.
        salvageBottles(trimmed)?.let { return it }
        throw AiException(
            "The model's answer was not readable JSON. If the photo held a long wine list it was " +
                "probably cut off before it finished — shoot one column at a time, or try again."
        )
    }

    private fun stripFences(raw: String): String = raw.trim()
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    /**
     * Rebuilds a reply that stops mid-object: every complete `{...}` inside "bottles" is kept and the
     * array is closed by hand. A partial list is worth more than an error message, and the missing
     * tail is visible in "notes" so nobody mistakes it for a complete read.
     */
    private fun salvageBottles(text: String): JsonObject? {
        val bottlesAt = text.indexOf("\"bottles\"")
        // A bare array is also worth salvaging: some models drop the wrapper under pressure.
        val arrayStart = if (bottlesAt >= 0) text.indexOf('[', bottlesAt) else text.indexOf('[')
        if (arrayStart < 0) return null
        val objects = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escaped = false
        var objectStart = -1
        var i = arrayStart + 1
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> {
                        if (depth == 0) objectStart = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && objectStart >= 0) {
                            objects += text.substring(objectStart, i + 1)
                            objectStart = -1
                        }
                    }
                    ']' -> if (depth == 0) i = text.length
                }
            }
            i++
        }
        if (objects.isEmpty()) return null
        val declared = Regex("\"scene\"\\s*:\\s*\"([a-zA-Z]+)\"").find(text)?.groupValues?.getOrNull(1)
        val scene = declared ?: "list"
        val rebuilt = "{\"scene\":\"$scene\",\"bottles\":[${objects.joinToString(",")}]," +
            "\"notes\":\"The answer was cut off; only the wines it finished are shown.\"}"
        return runCatching { json.parseToJsonElement(rebuilt).jsonObject }.getOrNull()
    }
}

private fun JsonObject.primitive(key: String): JsonPrimitive? =
    when (val element = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> element
        else -> null
    }

fun JsonObject.str(key: String): String =
    primitive(key)?.contentOrNull?.trim().orEmpty()

fun JsonObject.intOrNull(key: String): Int? =
    primitive(key)?.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()

fun JsonObject.doubleOrNull(key: String): Double? =
    primitive(key)?.contentOrNull?.trim()
        ?.replace(Regex("[^0-9.\\-]"), "")
        ?.toDoubleOrNull()

fun JsonObject.boolOrNull(key: String): Boolean? =
    when (primitive(key)?.contentOrNull?.trim()?.lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> null
    }

fun JsonObject.stringList(key: String): List<String> {
    return when (val element: JsonElement? = this[key]) {
        null, JsonNull -> emptyList()
        is JsonArray -> element.mapNotNull { item ->
            (item as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }
        is JsonPrimitive -> element.contentOrNull.orEmpty()
            .split(',', ';', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        else -> emptyList()
    }
}

fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject