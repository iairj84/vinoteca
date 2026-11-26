package com.wineverdict.app.data

import kotlinx.serialization.Serializable

/**
 * What the quick start collected, and nothing it did not.
 *
 * Answers are stored by question id so the file builder, the rules and the summary all read the same
 * source. An unanswered question is absent from [answers] and listed in [skipped] only when the user
 * actually passed over it, which is what lets the generated file say "not known yet" instead of
 * guessing.
 *
 * Two things live outside the questions: [alcoholCeiling], because it is a number the user typed
 * rather than a chip they tapped, and the free-text answers, because a tap cannot say why. Neither
 * becomes a rule the assistant then defends.
 */
@Serializable
data class PalateProfile(
    val answers: Map<String, List<String>> = emptyMap(),
    val skipped: List<String> = emptyList(),
    val completedAt: Long = 0L,
    val version: Int = VERSION,
    /** Percent alcohol by volume. Null means no ceiling was given — nothing is invented. */
    val alcoholCeiling: Double? = null,
    /** "Name 1–3 wines you loved and what you liked about them." Kept verbatim, capped. */
    val lovedNote: String = "",
    /** "Name a wine you didn't enjoy and why." */
    val dislikedNote: String = "",
    /** "Anything you're curious to try?" */
    val curiousNote: String = "",
    /** The user's own sentence about what their loved bottles have in common. */
    val links: String = ""
) {
    fun choice(questionId: String): String? = answers[questionId]?.firstOrNull()

    fun choices(questionId: String): List<String> = answers[questionId].orEmpty()

    fun answered(questionId: String): Boolean = !answers[questionId].isNullOrEmpty()

    val isComplete: Boolean get() = completedAt > 0L

    val answeredCount: Int get() = answers.count { it.value.isNotEmpty() }

    /** The ten-tap baseline only: the optional interview must not lower "how much I know". */
    val baselineAnswered: Int get() = QuickStart.baseline.count { answered(it.id) }

    /** How many of the optional interview questions have an answer. */
    val extrasAnswered: Int get() = QuickStart.extras.count { answered(it.id) }

    /** How much of the ten-tap baseline is filled in, for the "knows" line on the Taste screen. */
    val knowledgePercent: Int
        get() = baselineAnswered * 100 / QuickStart.baseline.size.coerceAtLeast(1)

    /** True when the user gave one of the optional answers, so a screen can say so. */
    val hasNotes: Boolean
        get() = lovedNote.isNotBlank() || dislikedNote.isNotBlank() || curiousNote.isNotBlank()

    fun withAnswer(questionId: String, values: List<String>): PalateProfile =
        copy(answers = answers + (questionId to values), skipped = skipped - questionId)

    fun skip(questionId: String): PalateProfile =
        copy(answers = answers - questionId, skipped = (skipped + questionId).distinct())

    fun completed(at: Long = System.currentTimeMillis()): PalateProfile = copy(completedAt = at)

    /**
     * Capped here rather than at every call site: the brief has a hard budget of its own.
     *
     * Nothing is trimmed on the way in. This runs on every keystroke, and a trim ate the space
     * the instant it was pressed — the field is redrawn from what is stored, so the space vanished
     * and "2019 Ultreia" could only ever be typed as "2019Ultreia". Both places the text is used
     * (`PalateFile.build` and `brief`) trim it themselves, so the file and the brief still read
     * clean while the field keeps what was actually typed.
     */
    fun withNotes(
        loved: String = lovedNote,
        disliked: String = dislikedNote,
        curious: String = curiousNote
    ): PalateProfile = copy(
        lovedNote = loved.take(NOTE_MAX_CHARS),
        dislikedNote = disliked.take(NOTE_MAX_CHARS),
        curiousNote = curious.take(NOTE_MAX_CHARS)
    )

    fun withLinks(text: String): PalateProfile = copy(links = text.take(NOTE_MAX_CHARS))

    fun withAlcoholCeiling(value: Double?): PalateProfile =
        copy(alcoholCeiling = value?.takeIf { it in ALCOHOL_RANGE })

    companion object {
        const val VERSION = 1

        /** Each answer rides in the taste brief, so it is capped like one. */
        const val NOTE_MAX_CHARS = 200

        /** Where a wine's ABV lives. Anything outside is a typo, not a limit. */
        val ALCOHOL_RANGE = 5.0..25.0

        val EMPTY = PalateProfile()
    }
}