package com.wineverdict.app.data

import android.content.Context
import com.wineverdict.app.model.Wine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The `mytaste.md` file lives in the app's private files directory.
 *
 * It has two halves:
 *  - the part the user writes by hand (flavour preferences, avoid list, pairing notes)
 *  - a generated "Bottles I love" block, rebuilt from rated / favourited wines
 *
 * The hand-written half is never touched by [rebuildLovedSection]. How much of it reaches the model
 * is [TasteText]'s job.
 */
class TasteStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    fun load() {
        val existing = if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
        if (existing.isBlank()) {
            write(DEFAULT_TEMPLATE)
        } else {
            _text.value = existing
        }
    }

    fun write(text: String) {
        runCatching { file.writeText(text) }
        _text.value = text
    }

    /** The whole file, as sent with a question in Ask. */
    fun snapshot(maxChars: Int = TasteText.FULL_MAX_CHARS): String =
        TasteText.snapshot(_text.value, maxChars)

    /**
     * What every model request reads as taste: the short brief generated from the stored profile
     * and the cellar. [briefSource] is wired by AppGraph, because the profile, the rules and the
     * cellar live in their own stores. Without it the file's own taste sections are the fallback.
     *
     * The file itself is unchanged and remains the thing the user reads and edits.
     */
    var briefSource: (() -> String)? = null

    fun brief(): String =
        briefSource?.invoke()?.takeIf { it.isNotBlank() }
            ?: TasteText.brief(_text.value, TasteText.BRIEF_MAX_CHARS)

    fun lovedBottles(wines: List<Wine>): List<Wine> =
        wines.filter { it.favorite || (it.rating ?: 0) >= 4 }
            .sortedWith(
                compareByDescending<Wine> { (it.rating ?: 0) }
                    .thenByDescending { it.favorite }
                    .thenByDescending { it.updatedAt }
            )

    fun lovedSectionMarkdown(wines: List<Wine>): String {
        val loved = lovedBottles(wines)
        if (loved.isEmpty()) {
            return "_No loved bottles yet. Favourite a wine or rate it 4★ or more, then rebuild this section._"
        }
        return loved.take(30).joinToString("\n") { wine ->
            val origin = listOf(wine.region, wine.country)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(", ")
            val meta = listOf(wine.type.label, origin, wine.grapes.joinToString(", "))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            val rating = wine.rating?.let { "$it★" } ?: "favourite"
            buildString {
                append("- **${wine.title}** — $meta · $rating")
                if (wine.flavorTags.isNotEmpty()) {
                    append("\n  - flavours: ${wine.flavorTags.joinToString(", ")}")
                }
                if (wine.myNotes.isNotBlank()) {
                    append("\n  - my note: ${wine.myNotes.replace("\n", " ").take(220)}")
                }
                if (wine.expertNotes.isNotBlank()) {
                    append("\n  - reference: ${wine.expertNotes.replace("\n", " ").take(160)}")
                }
            }
        }
    }

    /** Rewrites only the block between the AUTO markers. */
    fun rebuildLovedSection(wines: List<Wine>): String {
        val block = lovedSectionMarkdown(wines)
        val current = _text.value.ifBlank { DEFAULT_TEMPLATE }
        val start = current.indexOf(START_MARKER)
        val end = current.indexOf(END_MARKER)
        val updated = if (start >= 0 && end > start) {
            current.substring(0, start + START_MARKER.length) +
                "\n" + block + "\n" +
                current.substring(end)
        } else {
            current.trimEnd() + "\n\n" + START_MARKER + "\n" + block + "\n" + END_MARKER + "\n"
        }
        write(updated)
        return updated
    }

    /**
     * Writes the file the quick start generated. A file the user wrote or imported is kept below the
     * new one; a file an earlier quick start wrote is replaced, because retaking it means exactly
     * that — except for its assistant notes, which no question collects and the brief sends.
     */
    fun writeFromQuickStart(body: String, keepExisting: Boolean) {
        val existing = _text.value
        val keep = keepExisting && existing.isNotBlank() && existing.trim() != DEFAULT_TEMPLATE.trim()
        write(
            if (keep) mergeKept(body, existing)
            else TasteText.keepNoteSections(body, existing)
        )
    }

    /** Appends a dated line to the "Notes for the assistant" area. */
    fun appendNote(note: String) {
        val line = "- ${note.trim()}"
        if (line.isBlank()) return
        val current = _text.value.ifBlank { DEFAULT_TEMPLATE }
        write(current.trimEnd() + "\n" + line + "\n")
    }

    companion object {
        const val FILE_NAME = "mytaste.md"
        const val START_MARKER = PalateFile.LOVED_START
        const val END_MARKER = PalateFile.LOVED_END

        /** Pure, so the keep-or-replace rule can be checked without Android. */
        fun mergeKept(body: String, existing: String): String =
            body.trimEnd() + "\n\n## Notes from my previous file (kept)\n\n" + existing.trim() + "\n"

        val DEFAULT_TEMPLATE = """
# My taste

## What I enjoy
- **Body:** medium to full
- **Acidity:** medium or more — I like freshness in a wine
- **Tannin:** fine, ripe tannin; not harsh
- **Sweetness:** dry
- **Fruit:** dark fruit, red cherry, plum, blackcurrant
- **Oak:** restrained — vanilla and toast ok, not a plank
- **Earth / savoury:** leather, tobacco, graphite, forest floor
- **Styles I reach for:** Nebbiolo, Sangiovese, Syrah, good Bordeaux blends, structured Chardonnay, dry Riesling
- **What I enjoy with food:** tomato-based dishes, roast chicken, mushrooms, hard cheese

## What I avoid
- Heavily oaked jammy reds, high-residual-sugar whites, anything that tastes flabby
- Very high alcohol (over 15%) unless it is a fortified wine

## House rules
- Under ${'$'}25: everyday bottle, no ceremony needed
- ${'$'}25–60: weekend or guests
- Over ${'$'}60: hold for an occasion, check the drinking window first

## Bottles I love
<!-- AUTO:LOVED:START -->
_No loved bottles yet. Favourite a wine or rate it 4★ or more, then rebuild this section._
<!-- AUTO:LOVED:END -->

## Notes for the assistant
- Prefer bottles I already own when suggesting something for dinner.
- If a bottle in my cellar is past its window, say so plainly.
""".trimIndent()
    }
}