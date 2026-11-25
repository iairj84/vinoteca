package com.wineverdict.app.data

private data class TasteSection(val heading: String, val body: String)

/**
 * How much of `mytaste.md` reaches the model, and which parts.
 *
 * Two different budgets for two different requests. Ask sends a question once and can carry the
 * whole file; a scan frame or a queued shelf read shares a few thousand tokens with a 2,048-token
 * image, so it gets only the sections that describe taste.
 *
 * The important rule is that neither path chops the file blindly at a character count without
 * saying so: a real file opens with scoring machinery, so a head cut would send the rules and none
 * of the preferences — and the assistant would then invent the verdicts those rules were meant to
 * prevent.
 */
object TasteText {

    /** About 3,000 tokens: a chat question can carry this on a free tier. */
    const val FULL_MAX_CHARS = 12_000

    /** What a scan frame or a label read can afford to carry: about 750 tokens. */
    const val BRIEF_MAX_CHARS = 3_000

    /** The whole file, or as much of it as fits, with the cut stated out loud. */
    fun snapshot(text: String, maxChars: Int = FULL_MAX_CHARS): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars).trimEnd() +
            "\n\n[… my taste file continues past this point and was cut to fit the request …]"
    }

    /**
     * Whole sections, most useful first, until the budget is spent: what I enjoy, what I avoid,
     * grapes and styles, house rules, then gates and history.
     */
    fun brief(text: String, maxChars: Int = BRIEF_MAX_CHARS): String {
        if (text.length <= maxChars) return text
        val sections = sectionsOf(text)
        if (sections.isEmpty()) return snapshot(text, maxChars)

        val ordered = sections.withIndex()
            .sortedWith(compareBy({ sectionRank(it.value.heading) }, { it.index }))
            .map { it.value }

        val out = StringBuilder()
        for (section in ordered) {
            val block = buildString {
                if (section.heading.isNotBlank()) append("## ").append(section.heading).append('\n')
                append(section.body)
            }.trim()
            if (block.isEmpty()) continue
            if (out.length + block.length + 2 > maxChars) continue
            out.append(block).append("\n\n")
            if (out.length >= maxChars * 9 / 10) break
        }
        return out.toString().trim().ifEmpty { snapshot(text, maxChars) }
    }

    private fun sectionsOf(text: String): List<TasteSection> {
        val sections = mutableListOf<TasteSection>()
        var heading = ""
        val body = StringBuilder()

        fun flush() {
            if (heading.isNotBlank() || body.isNotBlank()) {
                sections += TasteSection(heading, body.toString().trim())
            }
            body.clear()
        }

        text.lines().forEach { line ->
            if (line.startsWith("## ")) {
                flush()
                heading = line.removePrefix("## ").trim()
            } else {
                body.appendLine(line)
            }
        }
        flush()
        return sections
    }

    /** Lower ranks first: what is most useful to a request that only has to judge a bottle. */
    private fun sectionRank(heading: String): Int {
        val h = heading.lowercase()
        if (h.isBlank()) return 0
        val groups = listOf(
            listOf("enjoy", "like", "love", "taste", "prefer", "profile"),
            listOf("avoid", "dislike", "never", "hate"),
            listOf("grape", "style", "word", "oak", "body", "acid"),
            listOf("house rule", "price", "band", "budget", "shop", "money"),
            listOf("gate", "score", "point", "verdict"),
            listOf("history", "bottle i", "worked example", "confirmed"),
            listOf("serving", "keeping", "temperature")
        )
        groups.forEachIndexed { index, keys ->
            if (keys.any { h.contains(it) }) return index + 1
        }
        return groups.size + 1
    }
}