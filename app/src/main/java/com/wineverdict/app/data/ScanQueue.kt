package com.wineverdict.app.data

import android.content.Context
import android.util.Base64
import com.wineverdict.app.ai.AiClient
import com.wineverdict.app.ai.AiException
import com.wineverdict.app.ai.AiProvider
import com.wineverdict.app.ai.Prompts
import com.wineverdict.app.ai.ScanParsing
import com.wineverdict.app.ai.ScannedLabel
import com.wineverdict.app.ai.Thinking
import com.wineverdict.app.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.ceil

/**
 * One queued photo. Usually a *frame*: three to five bottles standing together, which is the
 * whole point — Groq bills an image at a fixed 2048 tokens, so one frame of four bottles costs a
 * quarter of what four separate photos do.
 */
@Serializable
data class PendingScan(
    val id: String = UUID.randomUUID().toString(),
    val file: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = QUEUED,
    val attempts: Int = 0,
    val raw: String = "",
    val error: String = "",
    val model: String = "",
    val added: Boolean = false,
    val frame: Boolean = false,
    val bottles: Int = 0
) {
    companion object {
        const val QUEUED = "queued"
        const val READING = "reading"
        const val DONE = "done"
        const val FAILED = "failed"
    }
}

/**
 * Reads queued photos one at a time in the background, pacing itself from the free-tier budget.
 *
 * Throughput comes from three things, in order of size:
 *  1. several bottles per request (a frame), which divides the 2048-token image cost;
 *  2. spreading frames across every vision model listed in Settings, because Groq meters each
 *     model separately;
 *  3. not paying for text twice: the frame prompt asks for short identifying lines, not a full
 *     transcription per bottle.
 */
class ScanQueue(
    private val context: Context,
    private val settings: SettingsStore,
    private val taste: TasteStore,
    private val cellar: CellarRepository,
    private val ai: AiClient,
    private val scope: CoroutineScope
) {

    private val dir = File(context.filesDir, "scan-queue")
    private val indexFile = File(context.filesDir, "scan-queue.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<PendingScan>>(emptyList())
    val items: StateFlow<List<PendingScan>> = _items.asStateFlow()
    private val _waitingUntil = MutableStateFlow(0L)
    val waitingUntil: StateFlow<Long> = _waitingUntil.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null
    private var modelCursor = 0

    fun load() {
        dir.mkdirs()
        val stored = if (indexFile.exists()) {
            runCatching { json.decodeFromString<List<PendingScan>>(indexFile.readText()) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        _items.value = stored.mapNotNull { item ->
            when {
                item.file.isBlank() -> null
                item.status == PendingScan.DONE -> item
                File(item.file).exists() -> item.copy(status = PendingScan.QUEUED, error = "")
                else -> null
            }
        }
        persist()
    }

    suspend fun add(bytes: ByteArray, frame: Boolean = true): PendingScan =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val id = UUID.randomUUID().toString()
            val file = File(dir, "$id.jpg")
            file.writeBytes(bytes)
            val item = PendingScan(id = id, file = file.absolutePath, frame = frame)
            mutex.withLock {
                _items.value = _items.value + item
                persistLocked()
            }
            start()
            item
        }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun pause() {
        job?.cancel()
        job = null
        _running.value = false
        _waitingUntil.value = 0L
    }

    fun retryFailed() {
        scope.launch {
            mutex.withLock {
                _items.value = _items.value.map {
                    if (it.status == PendingScan.FAILED) {
                        it.copy(status = PendingScan.QUEUED, attempts = 0, error = "")
                    } else {
                        it
                    }
                }
                persistLocked()
            }
            start()
        }
    }

    fun clearFinished() {
        scope.launch {
            val removed = mutex.withLock {
                val gone = _items.value.filter {
                    it.status == PendingScan.DONE || it.status == PendingScan.FAILED
                }
                _items.value = _items.value - gone.toSet()
                persistLocked()
                gone
            }
            removed.forEach { item -> runCatching { File(item.file).delete() } }
        }
    }

    /** Every bottle the queue has read, newest frame first. */
    fun readBottles(): List<QueuedBottle> =
        _items.value
            .filter { it.status == PendingScan.DONE && it.raw.isNotBlank() }
            .reversed()
            .flatMap { item ->
                parseAll(item).map { label -> QueuedBottle(label, item.added) }
            }

    fun parseAll(item: PendingScan): List<ScannedLabel> {
        if (item.raw.isBlank()) return emptyList()
        return runCatching {
            if (item.frame) {
                ScanParsing.parseLabelFrame(item.raw, settings.currency)
            } else {
                listOfNotNull(ScanParsing.parseLabel(item.raw, settings.currency).takeIf { it.usable })
            }
        }.getOrDefault(emptyList())
    }

    /**
     * One tap at the end of a shelf. Bottles go in through the same merge path as a CSV import, so
     * a bottle caught in two overlapping frames — or already in the cellar — is matched rather than
     * duplicated.
     */
    suspend fun addAllToCellar(): Int {
        val ready = _items.value.filter { it.status == PendingScan.DONE && !it.added }
        val rows = mutableListOf<ImportRow>()
        val contributing = mutableListOf<String>()
        ready.forEach { item ->
            val labels = parseAll(item).filter { it.usable }
            if (labels.isNotEmpty()) {
                labels.forEach { label -> rows += ImportRow(label.wine) }
                contributing += item.id
            }
        }
        if (rows.isEmpty()) return 0
        val outcome = cellar.mergeRows(rows)
        contributing.forEach { id -> update(id) { it.copy(added = true) } }
        return outcome.added + outcome.updated
    }

    private suspend fun loop() {
        _running.value = true
        try {
            while (true) {
                val next = _items.value.firstOrNull { it.status == PendingScan.QUEUED } ?: break
                update(next.id) { it.copy(status = PendingScan.READING) }
                val result = runCatching { read(next) }
                val failure = result.exceptionOrNull()
                when {
                    failure == null -> {
                        val raw = result.getOrDefault("")
                        val count = if (next.frame) {
                            runCatching {
                                ScanParsing.parseLabelFrame(raw, settings.currency).size
                            }.getOrDefault(0)
                        } else {
                            runCatching {
                                if (ScanParsing.parseLabel(raw, settings.currency).usable) 1 else 0
                            }.getOrDefault(0)
                        }
                        update(next.id) {
                            it.copy(status = PendingScan.DONE, raw = raw, error = "", bottles = count)
                        }
                    }
                    failure is AiException && failure.retryable && next.attempts < MAX_ATTEMPTS -> {
                        update(next.id) {
                            it.copy(
                                status = PendingScan.QUEUED,
                                attempts = it.attempts + 1,
                                error = failure.message.orEmpty()
                            )
                        }
                        delay(2_000L * (next.attempts + 1))
                    }
                    else -> update(next.id) {
                        it.copy(
                            status = PendingScan.FAILED,
                            error = failure?.message ?: "Reading failed."
                        )
                    }
                }
            }
        } finally {
            _running.value = false
            _waitingUntil.value = 0L
        }
    }

    private suspend fun read(item: PendingScan): String {
        val file = File(item.file)
        if (!file.exists()) {
            throw AiException("The photo for this frame is missing. Remove it and shoot again.")
        }
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val slot = nextSlot() ?: throw AiException(
            "Add an API key in Settings. Groq and Google Gemini both have free tiers."
        )
        val known = cellar.data.value.wines.map { it.title }.distinct().take(KNOWN_BOTTLES)
        val system = if (item.frame) {
            Prompts.labelFrame(
                tasteMd = taste.brief(3_000),
                currency = settings.currency,
                knownBottles = known
            )
        } else {
            // The same reader the Scan screen uses: one frame of one bottle, no scene chip.
            Prompts.scan(
                tasteMd = taste.brief(3_000),
                currency = settings.currency,
                knownBottles = known
            )
        }
        val raw = ai.complete(
            system = system,
            turns = listOf(ChatMessage(role = "user", text = FRAME_TURN)),
            imagesBase64 = listOf(Base64.encodeToString(bytes, Base64.NO_WRAP)),
            vision = true,
            jsonMode = true,
            thinking = Thinking.LOW,
            temperature = 0.1,
            maxTokens = 2_200,
            provider = slot.provider,
            model = slot.model,
            onBudgetWait = { waitMs ->
                _waitingUntil.value = if (waitMs > 0) System.currentTimeMillis() + waitMs else 0L
            }
        )
        _waitingUntil.value = 0L
        update(item.id) { it.copy(model = "${slot.provider.shortLabel} · ${slot.model}") }
        return raw
    }

    private data class Slot(val provider: AiProvider, val model: String)

    /**
     * Every model of every configured provider, rotated one frame at a time. Providers meter
     * separately, so two of them carry twice the frames — and a provider that just 429'd is skipped
     * by the next turn of the rotation.
     */
    private fun slots(): List<Slot> = settings.configuredProviders.flatMap { provider ->
        settings.visionModels(provider).map { model -> Slot(provider, model) }
    }

    private fun nextSlot(): Slot? {
        val list = slots()
        if (list.isEmpty()) return null
        val slot = list[modelCursor % list.size]
        modelCursor++
        return slot
    }

    private suspend fun update(id: String, block: (PendingScan) -> PendingScan) {
        mutex.withLock {
            _items.value = _items.value.map { if (it.id == id) block(it) else it }
            persistLocked()
        }
    }

    private fun persist() {
        runCatching { indexFile.writeText(json.encodeToString(_items.value)) }
    }

    private fun persistLocked() {
        runCatching { indexFile.writeText(json.encodeToString(_items.value)) }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val KNOWN_BOTTLES = 20
        private const val FRAME_TURN =
            "Identify every readable bottle in this photo, left to right, then return the JSON."

        /**
         * Cost of one frame: a fixed 2048-token image, a ~1.9k-character prompt and the answer for
         * three or four bottles.
         */
        const val TOKENS_PER_FRAME = 3_600

        /** Groq's free-tier tokens-per-minute, per model. */
        const val TOKENS_PER_MINUTE = 8_000

        /** What a normal shelf frame holds, used only for the on-screen estimate. */
        const val BOTTLES_PER_FRAME = 4

        fun estimateMinutes(pendingFrames: Int, framesPerMinute: Double): Int {
            if (pendingFrames <= 0) return 0
            return ceil(pendingFrames / framesPerMinute.coerceAtLeast(0.2)).toInt().coerceAtLeast(1)
        }

        /** Bottles a minute the queue can read, for the screen's explanation. */
        fun bottlesPerMinute(framesPerMinute: Double): Int =
            (framesPerMinute * BOTTLES_PER_FRAME).toInt().coerceAtLeast(1)
    }
}

data class QueuedBottle(val label: ScannedLabel, val added: Boolean)