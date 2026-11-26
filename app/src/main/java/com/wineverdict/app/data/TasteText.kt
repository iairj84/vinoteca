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

    /** The heading the seeded file uses for the assistant's standing instructions. */
    const val ASSISTANT_NOTES = "## Notes for the assistant"

    /**
     * Every `Notes…` section of a file, heading and body, in file order.
     *
     * Two kinds exist: the seeded `## Notes for the assistant`, and `## Notes from my previous file
     * (kept)`, which is where a hand-edited or imported file survives a regeneration. Both are the
     * user's own words and neither can be rendered from the profile, so both are what a rewrite has
     * to carry and what the brief has to send — the same list serves both, which is what keeps them
     * from disagreeing.
     *
     * A body that opens with its own `# title` drops that title: the carried block already has the
     * heading above it, and a second H1 reads like a pasted document.
     */
    fun noteSections(text: String): List<String> =
        sectionsOf(text)
            .filter { it.heading.lowercase().startsWith("note") }
            .mapNotNull { section ->
                val body = withoutTitle(section.body)
                if (body.isBlank()) null else "## ${section.heading}\n$body"
            }

    private fun withoutTitle(body: String): String {
        val lines = body.trim().lines()
        val rest = if (lines.firstOrNull()?.trimStart()?.startsWith("# ") == true) {
            lines.drop(1)
        } else {
            lines
        }
        return rest.joinToString("\n").trim()
    }

    /**
     * The regenerated file, with the old one's notes carried over.
     *
     * A quick start replaces the file it wrote, which is right for the answers — they are rendered
     * again from the profile — but it also dropped the seeded "prefer bottles I already own" lines
     * and any hand-written text kept below them. Neither is collected by a question and both are
     * sent in the brief, so losing them silently changed what every request read. Carried only when
     * the old file actually has them: a file whose notes were deleted stays deleted.
     */
    fun keepNoteSections(body: String, existing: String): String {
        val missing = noteSections(existing).filterNot { section ->
            body.contains(section.substringBefore('\n'), ignoreCase = true)
        }
        if (missing.isEmpty()) return body
        return body.trimEnd() + "\n\n" + missing.joinToString("\n\n") + "\n"
    }

    /**
     * The sections the user wrote themselves, for the brief's `## Notes` tail.
     *
     * The generated half of `mytaste.md` is already rendered from the profile, so sending it again
     * would say everything twice. What has no other route into a request is the text a person typed:
     * a `## Notes` section, the assistant notes, and whatever a hand-edited or imported file kept
     * under "Notes from my previous file". That is the part the brief used to drop entirely.
     */
    fun userNotes(text: String, maxChars: Int = 900): String =
        noteSections(text).joinToString("\n\n").take(maxChars).trim()

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