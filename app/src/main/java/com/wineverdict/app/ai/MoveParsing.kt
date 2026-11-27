package com.wineverdict.app.ai

import com.wineverdict.app.model.MoveRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An answer as it is shown, and the move it asked for.
 *
 * The MOVE line is a marker, not prose: it is stripped before the answer is stored, so the transcript
 * never carries the JSON and the history the next question sends does not teach the model to write it
 * twice.
 */
data class ParsedMove(val text: String, val request: MoveRequest?)

/**
 * Reads the one machine-readable line a chat answer may end with.
 *
 * The chat is prose on purpose — it is the one request that wants the model to reason — so the move
 * is a trailing `MOVE {...}` line rather than a whole JSON reply. The line is parsed tolerantly
 * because a free-text reply will not be perfectly formed: key spelling, a wrapped fence or a quoted
 * year must not cost the user a request they can see.
 */
object MoveParsing {

    /** `MOVE {` at the start of a line. A sentence that happens to begin "Move…" has no brace. */
    private val marker = Regex("^\\s*MOVE\\s*:?\\s*\\{", RegexOption.IGNORE_CASE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun split(answer: String): ParsedMove {
        val lines = answer.trimEnd().lines().toMutableList()
        val marked = lines.filter { marker.containsMatchIn(it) }
        if (marked.isEmpty()) return ParsedMove(answer.trim(), null)
        // The last line is the one that counts; any earlier one is stripped rather than shown, since
        // a marker on screen is noise whatever it says.
        val request = parseLine(marked.last())
        lines.removeAll { marker.containsMatchIn(it) }
        // A model that fenced the line leaves two fence lines of its own behind.
        while (lines.isNotEmpty() && lines.last().trim().let { it.isEmpty() || it.startsWith("```") }) {
            lines.removeAt(lines.size - 1)
        }
        return ParsedMove(lines.joinToString("\n").trim(), request)
    }

    private fun parseLine(line: String): MoveRequest? {
        val start = line.indexOf('{')
        val end = line.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val body = runCatching {
            json.parseToJsonElement(line.substring(start, end + 1)) as JsonObject
        }.getOrNull() ?: return null
        return body.toRequest().takeIf { it.location.isNotBlank() }
    }

    private fun JsonObject.toRequest(): MoveRequest = MoveRequest(
        location = words("location", "place"),
        slot = words("slot", "shelf"),
        grape = words("grape", "grapes"),
        type = words("type"),
        producer = words("producer"),
        name = words("name", "cuvee", "cuvée", "bottle"),
        region = words("region"),
        country = words("country"),
        appellation = words("appellation"),
        vintageFrom = year("vintageFrom", "vintage_from", "from"),
        vintageTo = year("vintageTo", "vintage_to", "to")
    )

    /** A string, or the first string of a list, under any of the names the model might choose. */
    private fun JsonObject.words(vararg names: String): String =
        names.firstNotNullOfOrNull { name -> text(this[name]) }.orEmpty()

    private fun text(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.content.trim().takeIf { it.isNotBlank() && it != "null" }
        is JsonArray -> element.mapNotNull { text(it) }.joinToString(" ").ifBlank { null }
        else -> null
    }

    /** Years arrive as numbers, as quoted strings, or not at all. */
    private fun JsonObject.year(vararg names: String): Int? =
        names.firstNotNullOfOrNull { name -> text(this[name])?.trim()?.toIntOrNull() }
}
