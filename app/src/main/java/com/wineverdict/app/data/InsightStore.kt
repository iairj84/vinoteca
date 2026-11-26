package com.wineverdict.app.data

import android.content.Context
import com.wineverdict.app.ai.AiClient
import com.wineverdict.app.ai.AiJob
import com.wineverdict.app.ai.InsightParsing
import com.wineverdict.app.ai.Prompts
import com.wineverdict.app.ai.ScannedLabel
import com.wineverdict.app.ai.Thinking
import com.wineverdict.app.ai.WineInsight
import com.wineverdict.app.model.ChatMessage
import com.wineverdict.app.model.Wine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Normalizer

/** One stored answer, with the model that wrote it so the page can say who did. */
@Serializable
data class StoredInsight(
    val json: String = "",
    val model: String = "",
    val at: Long = 0
)

/**
 * "Why it tastes this way", asked for once per wine and kept.
 *
 * The insight describes a wine, not a bottle in a cellar, so it is cached on the normalized
 * `producer|name|vintage` rather than on the record's id: the same wine scanned twice, or met again
 * on a shelf or a list, is answered from the cache with no request. The answer is also written onto
 * the [Wine] record, which is what the bottle page reads and what a backup carries.
 *
 * It is asked for after the tap that confirms the bottle — a scan the user abandons costs a vision
 * read already, and should not cost a second, longer request on top.
 */
class InsightStore(
    context: Context,
    private val ai: AiClient,
    private val cellar: CellarRepository,
    private val brief: () -> String
) {

    private val file = File(context.filesDir, "insights.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Volatile
    private var cache: Map<String, StoredInsight> = emptyMap()

    fun load() {
        cache = if (file.exists()) {
            runCatching { json.decodeFromString<Map<String, StoredInsight>>(file.readText()) }
                .getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
    }

    /** The stored answer for this wine, whether or not it is in the cellar. */
    fun cached(wine: Wine): StoredInsight? = cache[key(wine)]

    /**
     * The insight for [wine], from its own record, from the cache, or from the model.
     * Returns the updated record, or null when the model's answer was not usable.
     */
    suspend fun explain(wine: Wine, label: ScannedLabel? = null): Wine? {
        // Only an answer that reads back counts. A record whose JSON the parser cannot use (an
        // answer written before the schema keys were pinned, a reply cut off mid-object) would
        // otherwise suppress every future attempt and leave the page permanently blank.
        if (InsightParsing.parse(wine.insightJson) != null) return wine
        cached(wine)?.let { return stamp(wine, it) }

        var answered = ""
        val raw = ai.complete(
            system = Prompts.bottleInsight(wine = wine, label = label, brief = brief()),
            turns = listOf(ChatMessage(role = "user", text = "Why does this bottle taste the way it does?")),
            vision = false,
            job = AiJob.ADVICE,
            jsonMode = true,
            thinking = Thinking.MEDIUM,
            temperature = 0.5,
            maxTokens = 1_600,
            onAnswer = { _, model -> answered = model }
        )
        val insight: WineInsight = InsightParsing.parse(raw) ?: return null
        val record = StoredInsight(
            json = json.encodeToString(WineInsight.serializer(), insight),
            model = answered,
            at = System.currentTimeMillis()
        )
        withContext(Dispatchers.IO) {
            cache = (cache + (key(wine) to record))
                .entries
                .sortedByDescending { it.value.at }
                .take(MAX_CACHED)
                .associate { it.key to it.value }
            runCatching { file.writeText(json.encodeToString(cache)) }
        }
        return stamp(wine, record)
    }

    /**
     * Stamps an answer that already exists onto a bottle without asking for one: a shelf or list
     * card added to the cellar reuses the explanation the wine already has instead of paying for it
     * again. A wine nobody has explained yet is left alone — no request, no placeholder.
     */
    suspend fun stampCached(wine: Wine): Wine? {
        if (!wine.insightJson.isNullOrBlank()) return wine
        val hit = cached(wine) ?: return null
        return stamp(wine, hit)
    }

    private suspend fun stamp(wine: Wine, record: StoredInsight): Wine {
        // The record may have been edited while the answer was in flight; the stored copy wins for
        // everything but the insight, which is what this call is for.
        val current = cellar.data.value.wines.firstOrNull { it.id == wine.id } ?: wine
        val updated = current.copy(
            insightJson = record.json,
            insightModel = record.model,
            insightDate = record.at
        )
        cellar.upsert(updated)
        return updated
    }

    companion object {
        /** Old answers are cheap to re-ask for; an unbounded file is not. */
        private const val MAX_CACHED = 300

        fun key(wine: Wine): String = key(wine.producer, wine.name, wine.vintage)

        /**
         * Case, accents and punctuation are the model's, not the wine's: "Viña Olabarri" and
         * "VINA OLABARRI" are the same bottle and must hit the same cache entry. The vintage is part
         * of the key because it changes the answer; a missing one keys on its own.
         */
        fun key(producer: String, name: String, vintage: Int?): String =
            listOf(producer, name, vintage?.toString().orEmpty())
                .joinToString("|") { normalize(it) }

        private fun normalize(raw: String): String {
            val folded = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            return folded.replace(Regex("[^a-z0-9]+"), " ").trim()
        }
    }
}
