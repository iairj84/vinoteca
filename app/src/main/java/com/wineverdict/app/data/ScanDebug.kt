package com.wineverdict.app.data

/**
 * What the last scan actually did, for the debug panel.
 *
 * Kept in memory only: nothing here is written to disk, backed up or exported. It exists so a scan
 * that comes back wrong can be diagnosed on the phone — which request answered, with which model,
 * how long the answer was, and whether the cards came from the model's ranking or from the price
 * fallback — instead of inferring all of that from the cards.
 */
data class TraceRequest(
    /** "judge", "read the page", "structured read", "small print", "on-device OCR". */
    val step: String,
    /** Who answered. Blank when nothing did; a hedged read names every provider that was asked. */
    val provider: String,
    val model: String,
    val chars: Int,
    val ms: Long,
    val ok: Boolean,
    val error: String = ""
) {
    val summary: String
        get() = buildString {
            append(step).append(" — ").append(provider.ifBlank { "no provider" })
            if (model.isNotBlank()) append(" · ").append(model)
            append(" · ").append(chars).append(" chars · ").append(ms).append(" ms")
            if (!ok) {
                append(" · failed")
                if (error.isNotBlank()) append(": ").append(error.take(160))
            }
        }
}

/** A finished scan. Immutable, so the panel cannot change under a recomposition. */
data class ScanTrace(
    val scene: String,
    val wineCount: Int,
    val lines: List<String>,
    val requests: List<TraceRequest>,
    val answer: String,
    val answerStep: String
) {
    val answerChars: Int get() = answer.length
}

/** Filled while a scan runs; [finish] freezes it for the panel. */
class TraceBuilder {
    private val requests = mutableListOf<TraceRequest>()
    var scene: String = ""
    var wineCount: Int = 0
    var lines: List<String> = emptyList()
    var answer: String = ""
    var answerStep: String = ""

    fun request(
        step: String,
        provider: String,
        model: String,
        chars: Int,
        ms: Long,
        ok: Boolean,
        error: String = ""
    ) {
        requests += TraceRequest(step, provider, model, chars, ms, ok, error)
    }

    fun finish(): ScanTrace = ScanTrace(
        scene = scene,
        wineCount = wineCount,
        lines = lines,
        requests = requests.toList(),
        answer = answer,
        answerStep = answerStep
    )
}

object ScanDebug {
    /** The last finished scan, in memory only. Gone when the process dies. */
    @Volatile
    var last: ScanTrace? = null
}
