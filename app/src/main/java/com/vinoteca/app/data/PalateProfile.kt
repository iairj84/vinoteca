package com.vinoteca.app.data

import kotlinx.serialization.Serializable

/**
 * What the quick start collected, and nothing it did not.
 *
 * Answers are stored by question id so the file builder, the rules and the summary all read the same
 * source. An unanswered question is absent from [answers] and listed in [skipped] only when the user
 * actually passed over it, which is what lets the generated file say "not known yet" instead of
 * guessing.
 */
@Serializable
data class PalateProfile(
    val answers: Map<String, List<String>> = emptyMap(),
    val skipped: List<String> = emptyList(),
    val completedAt: Long = 0L,
    val version: Int = VERSION
) {
    fun choice(questionId: String): String? = answers[questionId]?.firstOrNull()

    fun choices(questionId: String): List<String> = answers[questionId].orEmpty()

    fun answered(questionId: String): Boolean = !answers[questionId].isNullOrEmpty()

    val isComplete: Boolean get() = completedAt > 0L

    val answeredCount: Int get() = answers.count { it.value.isNotEmpty() }

    /** How much of the ten-tap baseline is filled in, for the "knows" line on the Taste screen. */
    val knowledgePercent: Int
        get() = answeredCount * 100 / QuickStart.questions.size.coerceAtLeast(1)

    fun withAnswer(questionId: String, values: List<String>): PalateProfile =
        copy(answers = answers + (questionId to values), skipped = skipped - questionId)

    fun skip(questionId: String): PalateProfile =
        copy(answers = answers - questionId, skipped = (skipped + questionId).distinct())

    fun completed(at: Long = System.currentTimeMillis()): PalateProfile = copy(completedAt = at)

    companion object {
        const val VERSION = 1
        val EMPTY = PalateProfile()
    }
}