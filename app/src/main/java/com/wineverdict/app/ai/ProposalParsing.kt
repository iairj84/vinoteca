package com.wineverdict.app.ai

import com.wineverdict.app.model.BottleUpdate
import com.wineverdict.app.model.MoveRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An answer as it is shown, and the one proposal it asked for.
 *
 * At most one of [move] and [update] is set: a marker line is a question about bottles, and the app
 * shows one card per answer. A marker is a marker, not prose: it is stripped before the answer is
 * stored, so the transcript never carries the JSON and the history the next question sends does not
 * teach the model to write it twice.
 */
data class ParsedProposal(val text: String, val move: MoveRequest?, val update: BottleUpdate?)

/**
 * Reads the one machine-readable line a chat answer may end with.
 *
 * The chat is prose on purpose — it is the one request that wants the model to reason — so a proposal
 * is a trailing `MOVE {...}` or `UPDATE {...}` line rather than a whole JSON reply. Both are parsed
 * tolerantly because a free-text reply will not be perfectly formed: key spelling, a wrapped fence or
 * a quoted number must not cost the user a request they can see.
 */
object ProposalParsing {

    /** `MOVE {` / `UPDATE {` at the start of a line. A sentence beginning "Move…" has no brace. */
    private val moveMarker = Regex("^\\s*MOVE\\s*:?\\s*\\{", RegexOption.IGNORE_CASE)
    private val updateMarker = Regex("^\\s*UPDATE\\s*:?\\s*\\{", RegexOption.IGNORE_CASE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun split(answer: String): ParsedProposal {
        val lines = answer.trimEnd().lines().toMutableList()
        val marked = lines.filter { isMarker(it) }
        if (marked.isEmpty()) return ParsedProposal(answer.trim(), null, null)
        // The last line is the one that counts; earlier ones are stripped rather than shown, since a
        // marker on screen is noise whatever it says.
        val last = marked.last()
        val move = if (moveMarker.containsMatchIn(last)) parseMove(last) else null
        val update = if (move == null && updateMarker.containsMatchIn(last)) parseUpdate(last) else null
        lines.removeAll { isMarker(it) }
        // A model that fenced the line leaves two fence lines of its own behind.
        while (lines.isNotEmpty() && lines.last().trim().let { it.isEmpty() || it.startsWith("```") }) {
            lines.removeAt(lines.size - 1)
        }
        return ParsedProposal(lines.joinToString("\n").trim(), move, update)
    }

    private fun isMarker(line: String): Boolean =
        moveMarker.containsMatchIn(line) || updateMarker.containsMatchIn(line)

    private fun objectOf(line: String): JsonObject? {
        val start = line.indexOf('{')
        val end = line.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(line.substring(start, end + 1)) as JsonObject }
            .getOrNull()
    }

    private fun parseMove(line: String): MoveRequest? =
        objectOf(line)?.let { body ->
            MoveRequest(
                location = body.words("location", "place", "to"),
                slot = body.words("slot", "shelf"),
                fromLocation = body.words("fromLocation", "from_location", "from", "currentlyIn", "current"),
                grape = body.words("grape", "grapes"),
                type = body.words("type"),
                producer = body.words("producer"),
                name = body.words("name", "cuvee", "cuvée", "bottle"),
                region = body.words("region"),
                country = body.words("country"),
                appellation = body.words("appellation"),
                vintageFrom = body.integer("vintageFrom", "vintage_from"),
                vintageTo = body.integer("vintageTo", "vintage_to")
            ).takeIf { it.location.isNotBlank() }
        }

    private fun parseUpdate(line: String): BottleUpdate? =
        objectOf(line)?.let { body ->
            BottleUpdate(
                grape = body.words("grape", "grapes"),
                type = body.words("type"),
                producer = body.words("producer"),
                name = body.words("name", "cuvee", "cuvée", "bottle"),
                region = body.words("region"),
                country = body.words("country"),
                appellation = body.words("appellation"),
                fromLocation = body.words("fromLocation", "from_location", "from", "currentlyIn", "current"),
                vintageFrom = body.integer("vintageFrom", "vintage_from"),
                vintageTo = body.integer("vintageTo", "vintage_to"),
                // Present on purpose: an empty slot clears the shelf label, which is a different
                // instruction from not mentioning the slot at all.
                slot = body.present("slot", "shelf")?.trim(),
                price = body.decimal("price", "paid"),
                note = body.present("note", "myNote", "my_note")?.trim()?.takeIf { it.isNotBlank() },
                quantity = body.integer("quantity", "count", "bottles"),
                vintage = body.integer("vintage", "year")
            ).takeIf { it.hasChange }
        }

    /** A string, or the first string of a list, under any of the names the model might choose. */
    private fun JsonObject.words(vararg names: String): String =
        names.firstNotNullOfOrNull { name -> text(this[name]) }.orEmpty()

    /** The value under any of the names, but only when the key is there — blank counts as present. */
    private fun JsonObject.present(vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> if (!containsKey(name)) null else text(this[name]) ?: "" }

    private fun text(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.content.trim().takeIf { it.isNotBlank() && it != "null" }
        is JsonArray -> element.mapNotNull { text(it) }.joinToString(" ").ifBlank { null }
        else -> null
    }

    /** Counts and years arrive as numbers or as quoted strings. */
    private fun JsonObject.integer(vararg names: String): Int? =
        names.firstNotNullOfOrNull { name -> text(this[name])?.toIntOrNull() }

    /** A price arrives as a number, as "48", or as the "USD 48.00" the cellar digest prints. */
    private fun JsonObject.decimal(vararg names: String): Double? =
        names.firstNotNullOfOrNull { name ->
            text(this[name])?.replace(",", "")?.filter { it.isDigit() || it == '.' || it == '-' }
                ?.toDoubleOrNull()
        }
}