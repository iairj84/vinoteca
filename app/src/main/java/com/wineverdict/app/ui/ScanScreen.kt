package com.wineverdict.app.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.wineverdict.app.AppGraph
import com.wineverdict.app.BuildConfig
import com.wineverdict.app.ScanHandoff
import com.wineverdict.app.ai.AiException
import com.wineverdict.app.ai.AiJob
import com.wineverdict.app.ai.ListAdvice
import com.wineverdict.app.ai.Prompts
import com.wineverdict.app.ai.ScanParsing
import com.wineverdict.app.ai.ScannedLabel
import com.wineverdict.app.ai.Scene
import com.wineverdict.app.ai.SceneScan
import com.wineverdict.app.ai.Thinking
import com.wineverdict.app.ai.digest
import com.wineverdict.app.ai.withFinePrint
import com.wineverdict.app.data.BuyAdvice
import com.wineverdict.app.data.DebugScan
import com.wineverdict.app.data.ImportRow
import com.wineverdict.app.data.InsightStore
import com.wineverdict.app.data.priorFor
import com.wineverdict.app.data.ListIdentity
import com.wineverdict.app.data.ListPicks
import com.wineverdict.app.data.PairingTable
import com.wineverdict.app.data.QuickStart
import com.wineverdict.app.data.Reminders
import com.wineverdict.app.data.Scored
import com.wineverdict.app.data.Service
import com.wineverdict.app.data.assertUniformUnit
import com.wineverdict.app.data.effectiveService
import com.wineverdict.app.data.orderButtonLabel
import com.wineverdict.app.data.rankForService
import com.wineverdict.app.data.strip
import com.wineverdict.app.data.toServiceRow
import com.wineverdict.app.data.unitLine
import com.wineverdict.app.data.unsurePourLine
import com.wineverdict.app.data.validatedText
import com.wineverdict.app.data.toCatalogRow
import com.wineverdict.app.data.PendingScan
import com.wineverdict.app.data.ScanQueue
import com.wineverdict.app.data.ScanDebug
import com.wineverdict.app.data.ScanTrace
import com.wineverdict.app.data.TraceBuilder
import com.wineverdict.app.model.ChatMessage
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.formatMoney
import com.wineverdict.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.wineverdict.app.ai.LocalListRead
import com.wineverdict.app.ai.LocalOcr

/**
 * One shutter, no mode switch. The model names the scene in the same reply that reads the bottles,
 * because the answer is in the photo — and a wrongly chosen chip used to change how prices were
 * judged without telling anyone.
 */
private const val SCAN_HINT =
    "One photo of whatever is in front of you — a bottle's label, a shop shelf, or a restaurant wine " +
        "list. Every wine is read out of it and gets a buy-or-leave verdict from your own limits, the " +
        "price in front of you, and what your cellar is missing."

/**
 * How long one read may take before the screen gives up and says so. A free-tier vision request
 * that has not answered in twenty-five seconds is not about to: the earlier behaviour was a
 * spinner for two minutes and then an error nobody could act on.
 */
// A photographed page of twenty wines is about two thousand output tokens, and a free tier
// queues the request before it starts. 25s cut off reads that were still arriving.
private const val READ_TIMEOUT_MS = 35_000L

/** The smaller retry gets less time, because it carries less to upload. */
private const val RETRY_TIMEOUT_MS = 20_000L

/**
 * The judgement is the whole answer for a list — nothing else runs after it — so it gets longer
 * than a retry. A free tier queues a text request for tens of seconds; cutting it at twenty was
 * what left a page of wines ranked on price alone.
 */
/**
 * The advice call thinks before it answers, so it needs longer than a read: a few explained picks
 * with reasoning on is not a 45-second request.
 */
private const val ADVICE_TIMEOUT_MS = 60_000L

/** The per-wine judgement, kept as the fallback for one release. */
private const val JUDGE_TIMEOUT_MS = 75_000L

/** The whole scan never outlives this: first byte to last card. No scan takes minutes. */
private const val SCAN_DEADLINE_MS = 95_000L

/**
 * A list or a shelf gets longer than a label. The one call after the read — the advice — is asked
 * for 4,000 tokens and has to think first; clipping it at the 95-second scan budget is what threw
 * the answer away. Measured with `openai/gpt-oss-120b`: 3,979 completion tokens, 2,777 reasoning.
 */
private const val ADVICE_DEADLINE_MS = 130_000L

/** Wait this long for the primary provider, then ask the second one too and take whichever wins. */
// A healthy list read is done well before this; past it, asking the second key too is
// cheaper than waiting out the first provider's full timeout.
private const val HEDGE_DELAY_MS = 8_000L

private fun abvLabel(value: Double): String =
    String.format(java.util.Locale.US, "%.1f%%", value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onReview: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val currency = AppGraph.settings.currency
    // Whose key and whose model actually answer the scan. The footer used to name Groq on every
    // install, which is wrong the moment a Gemini key is the one doing the reading.
    val providerName = AppGraph.settings.configuredProviders.firstOrNull()?.label
        ?: "your provider"

    var imageUri by remember { mutableStateOf<Uri?>(null) }
    // The kept menu for the page being read, so a wine the user actually orders can be marked on it.
    var keptListId by remember { mutableStateOf<String?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scan by remember { mutableStateOf<SceneScan?>(null) }
    // What the last scan did: in memory only, for the debug panel below the cards.
    var debugTrace by remember { mutableStateOf<ScanTrace?>(null) }
    var reReading by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    // What the read is doing, how long it has been doing it, and any wait the free tier has
    // imposed. A spinner with nothing next to it is indistinguishable from a hung app.
    var stage by remember { mutableStateOf("") }
    var waitedFor by remember { mutableStateOf(0L) }
    var elapsed by remember { mutableStateOf(0) }
    // What the phone itself could read in ~1s, shown before any provider answers. Provisional:
    // the AI ranking replaces it.
            var instantLines by remember { mutableStateOf<List<String>?>(null) }
    // True while the cards on screen are the phone's own reading and no model has confirmed them.
    var localOnly by remember { mutableStateOf(false) }
    var work by remember { mutableStateOf<Job?>(null) }
    // Debug builds only: a finished list scan read from app storage, so the advice block and the
    // read cards can be looked at on a machine with no provider key. Release builds compile the
    // branch out, and the two files do not exist on a phone.
    if (BuildConfig.DEBUG) {
        LaunchedEffect(Unit) {
            val fixture = withContext(Dispatchers.IO) { DebugScan.load(context, currency) }
            if (fixture != null && scan == null) scan = fixture
        }
    }
    // The one nudge the app sends. Asked for here rather than at first launch: a permission
    // dialog that arrives with a list still on the table has a reason behind it, and one at
    // start-up has none. The reminder itself outlives the screen, which is why it is work and not
    // a timer in this composable.
    val askToNotify = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(scan) {
        val read = scan ?: return@LaunchedEffect
        if (read.scene != Scene.LIST || read.bottles.isEmpty()) return@LaunchedEffect
        // Kept first, then reminded: the nudge carries the list's id, so the tap lands on that
        // evening's wines instead of on the cellar. Keeping is idempotent for one day, so the
        // second read of the same page (the model's answer on top of the phone's) is the same
        // record rather than a duplicate.
        // The page itself, kept beside its wines. A list is a photograph of small print, and the
        // wines are the app's reading of it — the recourse when a line was missed is the menu.
        // Read at the size a menu is photographed at, not the 1400px the model is sent.
        val page = imageUri?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    ImageUtils.jpegBytes(context, uri, maxDim = 2400, quality = 85)
                }.getOrNull()
            }
        }
        val kept = AppGraph.lists.keep(read, page)
        keptListId = kept?.id
        Reminders.scheduleListReview(context, kept?.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Reminders.canNotify(context)
        ) {
            askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(loading) {
        if (!loading) return@LaunchedEffect
        elapsed = 0
        while (loading) {
            delay(1_000L)
            elapsed += 1
        }
    }
    /**
     * The label photograph, saved beside the record it belongs to.
     *
     * Only for a single-bottle label scan: a shelf or a menu frame is one photograph of several
     * bottles, and naming that same picture on every one of them would claim the frame is each
     * bottle's label. Best effort — a photo that cannot be written leaves the bottle as it was read,
     * because the reading is the thing worth keeping.
     */
    suspend fun keepLabelPhoto(wineId: String): String {
        val uri = imageUri ?: return ""
        val read = scan ?: return ""
        if (read.scene != Scene.BOTTLE || read.bottles.count { it.usable } != 1) return ""
        return withContext(Dispatchers.IO) {
            ImageUtils.jpegBytes(context, uri, maxDim = 1600, quality = 82)
                .let { bytes -> AppGraph.cellar.saveLabelPhoto(wineId, bytes) }
        }
    }

    // The reader's own history, read once for the screen: "have I had this?" is matched against
    // the cellar and the drink log for every wine on the page.
    val cellarNow by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val drankNow by AppGraph.drinks.drinks.collectAsStateWithLifecycle()
    val queueItems by AppGraph.scanQueue.items.collectAsStateWithLifecycle()
    val queueWaitingUntil by AppGraph.scanQueue.waitingUntil.collectAsStateWithLifecycle()
    val queueRunning by AppGraph.scanQueue.running.collectAsStateWithLifecycle()
    val queueAdvice by AppGraph.scanQueue.shelfAdvice.collectAsStateWithLifecycle()
    var advisingShelf by remember { mutableStateOf(false) }

    // Declared before the launcher so the batch callback can reopen the camera for the next bottle.
    var takePhoto: () -> Unit = {}

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val captured = pendingCaptureUri
        pendingCaptureUri = null
        when {
            !success || captured == null -> Unit
            batch -> {
                // A shelf trip: keep the photo, queue it, and reopen the camera for the next bottle.
                scope.launch {
                    try {
                        // The whole frame: several bottles per photo is what makes a shelf trip quick.
                        val bytes = withContext(Dispatchers.IO) {
                            ImageUtils.jpegBytes(context, captured, maxDim = 2000)
                        }
                        AppGraph.scanQueue.add(bytes, frame = true)
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(e.message ?: "Could not queue that photo.")
                    }
                }
                takePhoto()
            }
            else -> {
                imageUri = captured
                scan = null
                error = null
                instantLines = null
            }
        }
    }

    takePhoto = {
        val uri = ImageUtils.newCaptureUri(context)
        pendingCaptureUri = uri
        cameraLauncher.launch(uri)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            imageUri = uri
            scan = null
            error = null
            instantLines = null
        }
    }

    fun analyse() {
        val uri = imageUri ?: return
        // A new read is a new page: the menu the last one kept is not this one.
        keptListId = null
        if (!AppGraph.settings.hasAnyKey) {
            error = "Add an API key in Settings first — Groq and Google Gemini both have free tiers."
            return
        }
        loading = true
        error = null
        stage = "Reading the photo"
        waitedFor = 0L
        elapsed = 0
        instantLines = null
        localOnly = false
        work?.cancel()
        work = scope.launch {
            val scanStart = System.currentTimeMillis()
            val trace = TraceBuilder()
            // On-device text first: ~1s, no network. Shown immediately so a stalled provider
            // never means a blank screen.
            // On-device text starts at once and runs beside the upload encoding: the network
            // request used to wait for it. The prompt takes whatever is ready after a short
            // grace; a late result still updates the on-screen preview.
            stage = "Reading the words on your phone"
            val ocrJob = async(Dispatchers.Default) {
                runCatching { LocalOcr.previewLines(context, uri) }.getOrNull()
            }
            launch {
                val late = runCatching { ocrJob.await() }.getOrNull()?.takeIf { it.isNotEmpty() }
                if (late != null && instantLines == null) instantLines = late
            }
            // Remaining budget for the network reads; every step below spends from it. A page the
            // phone read as a wine list moves the deadline out: it still has the judgement to pay
            // for, and that call is the expensive one.
            var deadlineMs = SCAN_DEADLINE_MS
            fun remainingMs(): Long =
                (deadlineMs - (System.currentTimeMillis() - scanStart)).coerceAtLeast(0L)

            /**
             * A timeout to hand a coroutine. `withTimeout` throws on a non-positive value instead of
             * waiting, so a scan that has already spent its budget would fail here with an
             * argument error rather than the timeout the user can understand.
             */
            fun budgetMs(cap: Long): Long = minOf(cap, remainingMs()).coerceAtLeast(1_000L)
            try {
                val taste = AppGraph.taste.brief()
                val known = AppGraph.cellar.data.value.wines.map { it.title }.distinct()
                val primary = AppGraph.settings.configuredProviders.firstOrNull()
                val who = primary
                    ?.let { "${it.shortLabel} · ${AppGraph.settings.visionModel(it)}" }
                    .orEmpty()
                // One image per request. The free tier bills an image at a fixed 2,048 tokens, and
                // two of them left too little room for a long answer: that is what kept cutting the
                // JSON off. The centre crop is a label-only tool and is sent later, on its own.
                // 1800px at 85 is legible for a printed page and a label, and roughly a third of
                // the bytes of the 2200px frame this used to send — the upload is part of the wait.
                val full = withContext(Dispatchers.IO) {
                    Base64.encodeToString(
                        ImageUtils.jpegBytes(context, uri, maxDim = 1800, quality = 85),
                        Base64.NO_WRAP
                    )
                }
                // The label crop encodes beside the first request: the phone is idle on the
                // network anyway, and the bottle path used to pay a second full decode after it.
                val cropDeferred = async(Dispatchers.IO) {
                    runCatching {
                        Base64.encodeToString(
                            ImageUtils.labelCropJpeg(context, uri, maxDim = 1800),
                            Base64.NO_WRAP
                        )
                    }.getOrNull()
                }
                instantLines = withTimeoutOrNull(1_500) { ocrJob.await() }
                    ?.takeIf { it.isNotEmpty() }
                trace.lines = instantLines.orEmpty()
                trace.request(
                    "on-device OCR",
                    "ML Kit (on the phone)",
                    "",
                    instantLines.orEmpty().sumOf { it.length },
                    0L,
                    instantLines != null
                )
                // The phone's own reading of a printed list, shown as real cards while the model
                // works: names, prices and the order down the page are all on the photo already,
                // and a provider that times out must not take them down with it.
                val onPhone = instantLines?.let { LocalListRead.wines(it, currency) }
                if (onPhone != null) deadlineMs = ADVICE_DEADLINE_MS
                if (onPhone != null && scan == null) {
                    scan = onPhone
                    localOnly = true
                }
                val photoKb = (full.length * 3 / 4) / 1024
                // One read, one deadline. Cancelling here cancels the HTTP call as well.
                suspend fun <T> answer(block: suspend () -> T): T =
                    withTimeoutOrNull(READ_TIMEOUT_MS) { block() } ?: throw AiException(
                        "$who did not answer within ${READ_TIMEOUT_MS / 1000} seconds. The photo goes " +
                            "up at $photoKb KB, so this is the provider or the connection rather than " +
                            "the reading: try again, or add the other provider's key in Settings and " +
                            "it will answer instead."
                    )

                var lastProblem: String? = null
                // A wait the limiter imposes is shown with its length: a mute spinner for a minute
                // reads as a hung app, and a free-tier window is usually what the minute is.
                val hold: (Long) -> Unit = { ms -> waitedFor = if (ms > 0) ms else 0L }

                // The structured reader: richer per bottle (notes, window, fit), and the only path
                // that gets those. Any failure here is survivable now.
                var readWho = ""
                var readModel = ""
                var readStart = 0L
                val readJson: suspend () -> SceneScan? = {
                    readStart = System.currentTimeMillis()
                    runCatching {
                        val raw = answer {
                            AppGraph.ai.complete(
                            system = Prompts.scan(taste, currency, known),
                            turns = listOf(
                                ChatMessage(
                                    role = "user",
                                    text = "Read this photo slowly and carefully: say what it shows, then list every wine in it."
                                )
                            ),
                            imagesBase64 = listOf(full),
                            vision = true,
                            jsonMode = true,
                            thinking = Thinking.LOW,
                            temperature = 0.1,
                            maxTokens = 3600,
                            onBudgetWait = hold,
                            // The target as the request goes out: it names the row whether or not
                            // anyone answers, and on success it is the target that answered.
                            onTry = { p, m -> readWho = p.shortLabel; readModel = m }
                        )
                        }
                        waitedFor = 0L
                        trace.request(
                            "structured read", readWho, readModel, raw.length,
                            System.currentTimeMillis() - readStart, true
                        )
                        ScanParsing.parseScene(raw, currency)
                    }.onFailure {
                        lastProblem = it.message
                        // A failed structured read used to leave no row at all.
                        trace.request(
                            "structured read", readWho, readModel, 0,
                            System.currentTimeMillis() - readStart, false, it.message.orEmpty()
                        )
                    }.getOrNull()
                }

                // The small print on the label's centre crop: ABV, vintage, volume, appellation.
                // A dozen short fields, so it is the cheapest request in the scan — and the one
                // that decides the alcohol rule and the drinking window.
                suspend fun readFinePrint(crop: String): ScannedLabel? {
                    val fineStart = System.currentTimeMillis()
                    var fineWho = ""
                    var fineModel = ""
                    val fineRaw = runCatching {
                        withTimeoutOrNull(budgetMs(RETRY_TIMEOUT_MS)) {
                            AppGraph.ai.complete(
                                system = Prompts.finePrint(currency),
                                turns = listOf(
                                    ChatMessage(
                                        role = "user",
                                        text = "Read the small print: ABV, vintage, volume, appellation, grapes, barcode."
                                    )
                                ),
                                imagesBase64 = listOf(crop),
                                vision = true,
                                jsonMode = true,
                                thinking = Thinking.OFF,
                                temperature = 0.1,
                                maxTokens = 400,
                                onTry = { p, m -> fineWho = p.shortLabel; fineModel = m }
                            )
                        }
                    }.getOrNull()
                    trace.request(
                        "small print", fineWho, fineModel, fineRaw?.length ?: 0,
                        System.currentTimeMillis() - fineStart, fineRaw != null
                    )
                    return fineRaw?.let { runCatching { ScanParsing.parseLabel(it, currency) }.getOrNull() }
                }

                // The advice on a list: text in, a few explained picks out, no image. This replaced
                // a rank for every line, which a small model answered by keeping the page's own
                // order — the first three sparkling lines came back as "best first". The per-wine
                // judgement is kept below for one release, and is only reached for a list the phone
                // read, where a failed answer would otherwise leave no ranking at all.
                //
                // A shelf asks the same question with the money taken literally (a shop price is
                // retail, not a markup), so both go through the two helpers below. Everything that
                // differs is in the prompt; everything that is expensive is written once.

                // Set when an attempt ran out of time, so the caller knows a second try cannot fit.
                var adviceTimedOut = false

                // Thinking.MEDIUM here and not OFF: this is the one call where the model's own
                // knowledge is the product, so it is allowed to reason about it.

                // One attempt, with its own row in the panel: a retried call has to show as two
                // requests, or the panel reports a length that came from neither of them.
                suspend fun adviceAttempt(label: String, system: String, turn: String): String {
                    // The advice is the expensive call, so it is also the one that moves the scan's
                    // deadline out: a page that had to be read first must not fail on the budget the
                    // read was given, and the per-wine ranking that may follow it needs the room too.
                    deadlineMs = maxOf(deadlineMs, ADVICE_DEADLINE_MS)
                    val start = System.currentTimeMillis()
                    var who = ""
                    var model = ""
                    val answer = runCatching {
                        withTimeoutOrNull(budgetMs(ADVICE_TIMEOUT_MS)) {
                            AppGraph.ai.complete(
                                system = system,
                                turns = listOf(ChatMessage(role = "user", text = turn)),
                                vision = false,
                                // Advice, so it goes to the advice model and Groq leads.
                                job = AiJob.ADVICE,
                                jsonMode = false,
                                thinking = Thinking.MEDIUM,
                                temperature = 0.4,
                                maxTokens = 4_000,
                                onBudgetWait = hold,
                                // A failed row still names who was asked: the panel used to say
                                // "no provider" for every 429, which hid the one fact it had.
                                onTry = { p, m -> who = p.shortLabel; model = m }
                            )
                        } ?: run {
                            adviceTimedOut = true
                            throw AiException("The advice did not arrive in time.")
                        }
                    }
                    answer.getOrNull()?.let { raw ->
                        waitedFor = 0L
                        trace.answer = raw
                        trace.answerStep = label
                        trace.request(
                            label, who, model, raw.length,
                            System.currentTimeMillis() - start, true
                        )
                        return raw
                    }
                    val failure = answer.exceptionOrNull()
                        ?: AiException("The advice did not arrive.")
                    lastProblem = failure.message
                    trace.request(
                        label, who, model, 0,
                        System.currentTimeMillis() - start, false, failure.message.orEmpty()
                    )
                    throw failure
                }

                suspend fun adviceAnswer(label: String, system: String, turn: String): Result<String> =
                    runCatching { adviceAttempt(label, system, turn) }.recoverCatching { failure ->
                        // Gemini answers this request with a 503 often enough (one attempt in three,
                        // measured) that a second try is worth more than the fallback, which is a
                        // different answer rather than a worse one. Only a failure that waiting can
                        // fix is retried, and only while the scan still has budget to spend.
                        val retryable = (failure as? AiException)?.let {
                            it.retryable || it.retryAfterMs != null
                        } == true
                        if (!retryable || adviceTimedOut || remainingMs() < 5_000L) throw failure
                        stage = "Asking for the advice again…"
                        adviceAttempt("$label (retry)", system, turn)
                    }

                /**
                 * The list answer, for a list the phone read and for one the model read: the OCR
                 * module is missing on some devices, and a legible list must not go unanswered
                 * because a different component was.
                 *
                 * [rankIfUnanswered] is the per-wine ranking call. The phone path has no ranking of
                 * its own, so a failed answer leaves it with nothing and the call earns its 4,000
                 * tokens. A model-read list already carries its own order, so there it would buy a
                 * ranking that is already on screen.
                 */
                suspend fun judgeList(read: SceneScan, rankIfUnanswered: Boolean): SceneScan? {
                    var judgeWho = ""
                    var judgeModel = ""
                    val owned = if (known.isEmpty()) "" else known.take(25).joinToString("; ")
                    adviceTimedOut = false
                    val advice = adviceAnswer(
                        label = "advice",
                        system = Prompts.listAdvice(taste, owned, currency, read.bottles),
                        turn = "What should I order from this list?"
                    )
                    advice.getOrNull()?.let { raw ->
                        // The panel shows what was thrown away and why: a pick the page
                        // contradicts is a fact about the answer, not something to hide.
                        val parsed = ScanParsing.parseAdvice(raw, read)
                        trace.dropped = parsed?.dropped.orEmpty()
                        parsed?.let { return ScanParsing.applyAdvice(read, it) }
                    }
                    advice.exceptionOrNull()?.let { failure ->
                        // A second 4,000-token call in the same minute cannot fit: the advice call
                        // alone is most of the free tier's 8,000 TPM. A timeout or a rate limit is
                        // the one failure a fallback would repeat rather than fix, so the scan goes
                        // to the page read instead of spending the budget twice.
                        val budget =
                            adviceTimedOut || (failure as? AiException)?.let {
                                it.retryable || it.retryAfterMs != null
                            } == true
                        if (budget || !rankIfUnanswered) return null
                    }

                    val fallbackStart = System.currentTimeMillis()
                    judgeWho = ""
                    judgeModel = ""
                    return runCatching {
                        val raw = withTimeoutOrNull(budgetMs(JUDGE_TIMEOUT_MS)) {
                            AppGraph.ai.complete(
                                system = Prompts.listJudge(taste, currency, read.bottles),
                                turns = listOf(
                                    ChatMessage(
                                        role = "user",
                                        text = "Rank these wines against my taste and explain each place."
                                    )
                                ),
                                vision = false,
                                jsonMode = false,
                                thinking = Thinking.OFF,
                                temperature = 0.3,
                                // One line per wine, five fields, two of them prose: at 800 tokens
                                // the answer was cut off and nothing was placed at all. 4,000 is a
                                // measured floor for 26 wines (3,979 used), plus the reserve the
                                // payload adds so reasoning cannot eat it.
                                maxTokens = 4_000,
                                onBudgetWait = hold,
                                onTry = { p, m -> judgeWho = p.shortLabel; judgeModel = m }
                            )
                        } ?: throw AiException("The ranking did not arrive in time.")
                        waitedFor = 0L
                        // The whole point of the panel: whether this answer was cut off.
                        trace.answer = raw
                        trace.answerStep = "judgement (fallback)"
                        trace.request(
                            "judge (fallback)", judgeWho, judgeModel, raw.length,
                            System.currentTimeMillis() - fallbackStart, true
                        )
                        ScanParsing.applyJudgement(read, raw)
                    }.onFailure {
                        trace.request(
                            "judge (fallback)", judgeWho, judgeModel, 0,
                            System.currentTimeMillis() - fallbackStart, false, it.message.orEmpty()
                        )
                        lastProblem = it.message
                    }.getOrNull()
                }

                /**
                 * The shelf answer: which bottles to buy, and whether the price is any good.
                 *
                 * A shop is photographed as several frames, so the numbers run across every bottle
                 * the read produced — three picks per shelf, not three per photo. No fallback: the
                 * cards are already on screen with their chips, so a failed call costs the
                 * explanation and nothing else.
                 */
                suspend fun judgeShelf(read: SceneScan): SceneScan? {
                    val owned = if (known.isEmpty()) "" else known.take(25).joinToString("; ")
                    adviceTimedOut = false
                    val answer = adviceAnswer(
                        label = "shelf advice",
                        system = Prompts.shelfAdvice(taste, owned, currency, read.bottles),
                        turn = "What should I buy from this shelf?"
                    )
                    val raw = answer.getOrNull() ?: return null
                    val parsed = ScanParsing.parseAdvice(raw, read) ?: return null
                    trace.dropped = parsed.dropped
                    return ScanParsing.applyAdvice(read, parsed)
                }

                // Plain lines, and the first request: no braces, no schema, nothing that can arrive
                // half-written, and the scene and the order come back in the same answer. That is
                // one round trip for a list instead of a cheap question followed by a real one.
                // Hedged when two keys exist: after 12s with no answer the second provider is
                // asked too and whichever answers first wins — one stalled provider no longer
                // costs its full timeout before the other is tried.
                suspend fun readPage(): String {
                    val pageStart = System.currentTimeMillis()
                    // Who was asked and with which model, taken from the client as each request goes
                    // out: a read that failed has no answering provider to report, and the panel
                    // should still name the one it tried rather than "no provider".
                    var pageWho = ""
                    var pageModel = ""
                    val ocrHint = instantLines
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString("\n") { "- $it" }
                        ?.let { "\n\nThe phone itself read these lines (may have errors, use the photo):\n$it" }
                        .orEmpty()
                    val ask = listOf(
                        ChatMessage(
                            role = "user",
                            text = "Read this photo: give me the scene line, then one line per wine." + ocrHint
                        )
                    )
                    val system = Prompts.listLines(taste, currency)
                    val primary = AppGraph.settings.configuredProviders.firstOrNull()
                    val second = AppGraph.settings.configuredProviders.drop(1).firstOrNull()
                    val hedged: String? = coroutineScope {
                        // runCatching inside: a failure is a null answer, never an exception the
                        // polling loop below would choke on while the other request is still in flight.
                        val first = async {
                            withTimeoutOrNull(READ_TIMEOUT_MS) {
                                runCatching {
                                    AppGraph.ai.complete(
                                        system = system,
                                        turns = ask,
                                        imagesBase64 = listOf(full),
                                        vision = true,
                                        jsonMode = false,
                                        thinking = Thinking.OFF,
                                        temperature = 0.1,
                                        maxTokens = 2_600,
                                        provider = primary,
                                        onBudgetWait = hold,
                                        onTry = { p, m -> pageWho = p.shortLabel; pageModel = m }
                                    )
                                }.getOrNull()
                            }
                        }
                        if (second == null) return@coroutineScope first.await()
                        val late = async {
                            // A fast failure of the first request completes it with null: the second
                            // provider starts at once instead of waiting out the full hedge delay
                            // for an answer that is already dead.
                            val failedFast = withTimeoutOrNull(HEDGE_DELAY_MS) {
                                first.join()
                                first.getCompleted() == null
                            }
                            if (failedFast == false && first.isCompleted) return@async null
                            stage = "Still waiting — asking " +
                                "${second.shortLabel} too and taking whichever answers"
                            withTimeoutOrNull(RETRY_TIMEOUT_MS) {
                                runCatching {
                                    AppGraph.ai.complete(
                                        system = system,
                                        turns = ask,
                                        imagesBase64 = listOf(full),
                                        vision = true,
                                        jsonMode = false,
                                        thinking = Thinking.OFF,
                                        temperature = 0.1,
                                        maxTokens = 2_600,
                                        provider = second,
                                        onBudgetWait = hold,
                                        onTry = { p, m -> pageWho = p.shortLabel; pageModel = m }
                                    )
                                }.getOrNull()
                            }
                        }
                        val winner = withTimeoutOrNull(READ_TIMEOUT_MS + HEDGE_DELAY_MS) {
                            var answer: String? = null
                            while (answer == null && (first.isActive || late.isActive)) {
                                delay(200L)
                                if (first.isCompleted) answer = first.getCompleted()
                                else if (late.isCompleted) answer = late.getCompleted()
                            }
                            answer
                        }
                        first.cancel()
                        late.cancel()
                        winner
                    }
                    if (hedged != null) {
                        trace.answer = hedged
                        trace.answerStep = "page read"
                        trace.request(
                            "read the page",
                            if (second != null) "${primary?.shortLabel}/${second.shortLabel} (hedged)"
                            else pageWho,
                            // Only with one provider is the model unambiguous: a hedge row names
                            // both providers and whoever answered is not reported back.
                            if (second == null) pageModel else "",
                            hedged.length,
                            System.currentTimeMillis() - pageStart,
                            true
                        )
                        return hedged
                    }
                    waitedFor = 0L
                    // Neither answered in time: one smaller retry on whatever budget is left.
                    val left = remainingMs()
                    if (left < 8_000L) {
                        // Nothing is retried, so this failure used to leave no row either.
                        trace.request(
                            "read the page", pageWho, pageModel, 0,
                            System.currentTimeMillis() - pageStart, false,
                            "No time left in the scan for a retry."
                        )
                        throw AiException(
                            "$who did not answer in time ($photoKb KB up). Nothing was saved — " +
                                "try again on Wi-Fi, or add the other provider's key in Settings."
                        )
                    }
                    stage = "Retrying with a smaller photo" +
                        (second?.let { " on ${it.shortLabel}" } ?: " on $who")
                    val small = withContext(Dispatchers.IO) {
                        Base64.encodeToString(
                            ImageUtils.jpegBytes(context, uri, maxDim = 1_200, quality = 75),
                            Base64.NO_WRAP
                        )
                    }
                    val retried = withTimeoutOrNull(budgetMs(RETRY_TIMEOUT_MS)) {
                        AppGraph.ai.complete(
                            system = system,
                            turns = ask,
                            imagesBase64 = listOf(small),
                            vision = true,
                            jsonMode = false,
                            thinking = Thinking.OFF,
                            temperature = 0.1,
                                                            maxTokens = 1_600,
                                provider = second,
                                onBudgetWait = hold,
                                onTry = { p, m -> pageWho = p.shortLabel; pageModel = m }
                            )
                    }
                    trace.request(
                        "read the page (smaller photo)",
                        pageWho,
                        pageModel,
                        retried?.length ?: 0,
                        System.currentTimeMillis() - pageStart,
                        retried != null
                    )
                    return retried ?: throw AiException(
                        "$who did not answer in ${READ_TIMEOUT_MS / 1000}s on the full photo, " +
                            "nor the backup in ${RETRY_TIMEOUT_MS / 1000}s on a smaller one " +
                            "($photoKb KB, then a third of that). Nothing was saved. Try again on " +
                            "Wi-Fi; if it still stalls, add the other provider's key in Settings " +
                            "and it will answer instead."
                    )
                }

                var scene: Scene? = null
                var parsed: SceneScan? = null
                if (onPhone != null) {
                    // The phone read this list itself, so no image goes up: the model only ranks
                    // and explains from the text, which is the shape a free tier answers fastest.
                    // A bottle the phone could not read never arrives here — it takes the vision
                    // path below, unchanged.
                    stage = "Thinking about the list…"
                    parsed = judgeList(onPhone, rankIfUnanswered = true)
                    if (parsed != null) {
                        scan = parsed
                        localOnly = false
                    }
                }
                // The phone read the page but the judgement did not come back: read it from the
                // photo instead of leaving the price order on screen. A list with no ranking is
                // the one result this screen must not settle for.
                if (parsed == null) {
                    stage = "Reading the page with $who"
                    parsed = runCatching {
                        val raw = readPage()
                        waitedFor = 0L
                        scene = ScanParsing.sceneLine(raw)?.takeIf { it != Scene.OTHER }
                        val labels = ScanParsing.parseListLines(raw, currency)
                        if (labels.isEmpty()) null
                        else SceneScan(
                            scene = scene ?: if (labels.size == 1) Scene.BOTTLE else Scene.SHELF,
                            bottles = labels,
                            notes = "",
                            // A page only this reader could follow is still a named evening: the
                            // letterhead is what keeps it from being saved under its date.
                            venue = ScanParsing.venueLine(raw)
                        )
                    }.onFailure { lastProblem = it.message }.getOrNull()
                }
                waitedFor = 0L

                // A bottle is worth the richer read on top: notes, window and the model's own fit.
                // Skipped when the budget is nearly spent — the line read already has the bottle.
                val bottle = scene == Scene.BOTTLE ||
                    (scene == null && parsed?.bottles?.size == 1)
                var finePrinted = false
                if (bottle && parsed != null && remainingMs() > 12_000L) {
                    stage = "Reading the label with $who"
                    val crop = cropDeferred.await()
                    // The rich read and the small-print pass are two independent reads of the same
                    // label, so they go out together: in series they were most of the minute a
                    // bottle scan used to take. Whichever is slower sets the wait, not their sum.
                    finePrinted = true
                    val (rich, fine) = coroutineScope {
                        val richJob = async { readJson() }
                        val fineJob = async { crop?.let { readFinePrint(it) } }
                        richJob.await() to fineJob.await()
                    }
                    if (rich != null && rich.bottles.isNotEmpty()) parsed = rich
                    val single = parsed?.bottles?.singleOrNull()
                    if (single != null && fine != null) {
                        parsed = parsed?.copy(bottles = listOf(single.withFinePrint(fine)))
                    }
                }
                // Nothing came out of the line read: try the structured one on the same photo.
                // Skipped when the phone already read the page — its cards are on screen, and a
                // second, slower vision read would only repeat them.
                if (parsed == null && onPhone == null) {
                    stage = "Trying a closer read with $who"
                    parsed = readJson()?.takeIf { it.bottles.isNotEmpty() }
                }

                // A shelf or a list is worth one question more than a read: what to buy or order,
                // and whether the printed price is any good. Asked after every path, so a page the
                // model read gets the same answer one the phone read does; skipped with no time left
                // to hear it.
                val shop = parsed
                if (shop != null && shop.bottles.size > 1 && remainingMs() > 15_000L) {
                    when (shop.scene) {
                        Scene.SHELF -> {
                            stage = "Picking from the shelf…"
                            judgeShelf(shop)?.let { parsed = it }
                        }
                        // `onPhone` is also the record of whether the list answer was asked: that
                        // branch always asks it first, so a page that fell through to the model's
                        // read has already spent both its attempts. Its read order is on screen.
                        Scene.LIST -> if (onPhone == null) {
                            stage = "Thinking about the list…"
                            judgeList(shop, rankIfUnanswered = false)?.let { parsed = it }
                        }
                        else -> Unit
                    }
                }

                if (parsed == null) {
                    // The phone read the page and the model did not. The wines and prices are
                    // already on screen, so the failure is a note, not a blank result.
                    if (scan?.bottles?.isNotEmpty() == true) {
                        localOnly = true
                        error = null
                    } else {
                        error = lastProblem?.takeIf { it.isNotBlank() }
                            ?: "Nothing readable came back from that photo. Try again, or shoot the page in two halves."
                    }
                }

                // A bottle that only came out of the fallback read still gets the small print.
                // Skipped when the parallel pass above already asked for it, or when the scan
                // budget is nearly spent: no scan takes minutes.
                val current = parsed
                val only = current?.bottles?.singleOrNull()
                if (!finePrinted && current != null && only != null &&
                    (only.wine.alcohol == null || only.wine.vintage == null) &&
                    remainingMs() > 10_000L
                ) {
                    val fine = cropDeferred.await()?.let { readFinePrint(it) }
                    if (fine != null) {
                        parsed = current.copy(bottles = listOf(only.withFinePrint(fine)))
                    }
                }

                if (parsed != null) {
                    scan = parsed
                    localOnly = false
                }
                // Freeze what this scan did for the debug panel. The ranking basis is added by
                // the panel itself, because the ranking is computed where the cards are drawn.
                val shown = parsed ?: scan
                trace.scene = shown?.scene?.name.orEmpty()
                trace.wineCount = shown?.bottles?.size ?: 0
                if (trace.lines.isEmpty()) trace.lines = instantLines.orEmpty()
                ScanDebug.last = trace.finish()
                debugTrace = ScanDebug.last
            } catch (e: Exception) {
                if (scan?.bottles?.isNotEmpty() == true) localOnly = true
                else error = e.message ?: "Scan failed. Try again."
            } finally {
                loading = false
            }
        }
    }

    /** Slower second pass over the same photo, for when the first read looks shaky. */
    fun reRead() {
        val uri = imageUri ?: return
        val first = scan?.bottles?.firstOrNull() ?: return
        if (!AppGraph.settings.hasAnyKey) {
            error = "Add an API key in Settings first — Groq and Google Gemini both have free tiers."
            return
        }
        reReading = true
        error = null
        scope.launch {
            try {
                val crop = withContext(Dispatchers.IO) {
                    Base64.encodeToString(ImageUtils.labelCropJpeg(context, uri), Base64.NO_WRAP)
                }
                val full = withContext(Dispatchers.IO) {
                    Base64.encodeToString(
                        ImageUtils.jpegBytes(context, uri, maxDim = 1600),
                        Base64.NO_WRAP
                    )
                }
                val raw = AppGraph.ai.complete(
                    system = Prompts.labelRecheck(AppGraph.taste.brief(), currency, first.digest()),
                    turns = listOf(
                        ChatMessage(role = "user", text = "Take a second, slower look at this label.")
                    ),
                    imagesBase64 = listOf(crop, full),
                    vision = true,
                    jsonMode = true,
                    thinking = Thinking.MEDIUM,
                    temperature = 0.3,
                    maxTokens = 1400
                )
                scan = SceneScan(
                    scene = Scene.BOTTLE,
                    bottles = listOf(ScanParsing.parseLabel(raw, currency)),
                    notes = scan?.notes.orEmpty()
                )
            } catch (e: Exception) {
                error = e.message ?: "The second look failed. Try again."
            } finally {
                reReading = false
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Scan") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = SCAN_HINT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (batch) {
                Banner("Batch capture is on: fit three to five bottles in each photo. The camera reopens after every shot and the queue reads them in the background.")
                OutlinedButton(onClick = { batch = false }) { Text("Done shooting") }
            }

            if (queueItems.isNotEmpty()) {
                BatchQueueCard(
                    items = queueItems,
                    running = queueRunning,
                    waitingUntil = queueWaitingUntil,
                    providers = AppGraph.settings.configuredProviders.size,
                    framesPerMinute = AppGraph.settings.poolFramesPerMinute,
                    advice = queueAdvice,
                    advising = advisingShelf,
                    onAdvise = {
                        if (!advisingShelf) {
                            advisingShelf = true
                            scope.launch {
                                runCatching { AppGraph.scanQueue.adviseShelf() }
                                    .onFailure { failure ->
                                        snackbarHostState.showSnackbar(
                                            failure.message
                                                ?: "The shelf advice did not come back."
                                        )
                                    }
                                advisingShelf = false
                            }
                        }
                    },
                    onStart = { AppGraph.scanQueue.start() },
                    onPause = { AppGraph.scanQueue.pause() },
                    onAddAll = {
                        scope.launch {
                            val added = AppGraph.scanQueue.addAllToCellar()
                            snackbarHostState.showSnackbar(
                                if (added > 0) "Added $added bottles to the cellar" else "Nothing finished yet"
                            )
                        }
                    },
                    onClear = { AppGraph.scanQueue.clearFinished() },
                    onRetryFailed = { AppGraph.scanQueue.retryFailed() },
                    onAdd = { wine ->
                        scope.launch {
                            AppGraph.cellar.mergeRows(listOf(ImportRow(wine)))
                            snackbarHostState.showSnackbar("Added ${wine.title}")
                        }
                    },
                    onReview = { wine ->
                        ScanHandoff.put(wine)
                        onReview()
                    }
                )
            }

            if (imageUri == null) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("No photo yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Take a photo in good light, with the label filling the frame.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                val uri = ImageUtils.newCaptureUri(context)
                                pendingCaptureUri = uri
                                cameraLauncher.launch(uri)
                            }
                        ) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                            Text("  Camera")
                        }
                        OutlinedButton(
                            onClick = {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        ) {
                            Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                            Text("  Choose a photo")
                        }
                        TextButton(
                            onClick = {
                                batch = true
                                val uri = ImageUtils.newCaptureUri(context)
                                pendingCaptureUri = uri
                                cameraLauncher.launch(uri)
                            }
                        ) {
                            Text("Shoot a shelf")
                        }
                    }
                }
            } else {
                UriImage(
                    uri = imageUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val uri = ImageUtils.newCaptureUri(context)
                            pendingCaptureUri = uri
                            cameraLauncher.launch(uri)
                        }
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                        Text("  Retake")
                    }
                    OutlinedButton(
                        onClick = {
                            imageUri = null
                            scan = null
                            error = null
                            instantLines = null
                        }
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                        Text("  Clear")
                    }
                }
                Button(
                    onClick = { analyse() },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loading) "Reading carefully — a few extra seconds…" else "Analyse photo")
                }
            }

            if (!AppGraph.settings.hasAnyKey) {
                Banner("No API key set yet. Add a Groq or Google Gemini key in Settings — both free tiers are enough for this app.")
            }

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (waitedFor > 0L) {
                                "Waiting ${(waitedFor + 999) / 1000}s for $providerName's " +
                                    "per-minute free-tier limit"
                            } else {
                                stage.ifBlank { "Reading the photo" }
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "${elapsed}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            work?.cancel()
                            work = null
                            waitedFor = 0L
                            loading = false
                        }
                    ) { Text("Stop") }
                }
            }

            // On-device preview: what the phone itself read, before any provider answers.
            // Provisional — the ranked cards replace it — but a stalled network never leaves
            // a blank screen.
            if (scan == null && instantLines != null) {
                SectionCard("On your phone already — ranking follows") {
                    instantLines!!.take(12).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                    if (instantLines!!.size > 12) {
                        Text(
                            "+${instantLines!!.size - 12} more lines",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            error?.let { message ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("That did not work", style = MaterialTheme.typography.titleSmall)
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { analyse() }) { Text("Retry") }
                    }
                }
            }

            scan?.let { result ->
                if (localOnly && !loading && result.bottles.isNotEmpty()) {
                    Banner(
                        "These wines and prices are the phone's own reading of the page, and the " +
                            "ranking did not come back — so all this page can tell you is which " +
                            "costs more. Tap Analyse again to have the wines judged."
                    )
                }
                if (result.bottles.isEmpty()) {
                    Banner(
                        when (result.scene) {
                            Scene.LIST ->
                                "The photo was read as a wine list but no wines came out of it. Shoot one " +
                                    "column at a time, filling the frame with the text, and keep the page " +
                                    "flat — a two-column list is the usual reason a line gets cut off."
                            Scene.BOTTLE ->
                                "That looks like a bottle, but nothing on the label was legible. Fill the " +
                                    "frame with the front label, avoid glare, and try again."
                            else ->
                                "No readable bottles in that photo. Move closer, avoid glare, and try again."
                        }
                    )
                }
                // A list is not a shop: its prices carry a markup, so the verdict is about the wine.
                val restaurant = result.scene == Scene.LIST
                val rules = AppGraph.palate.rules
                // A list is ranked against itself. Nine cards all saying the same word is not an
                // answer to "which one do I order"; the model read a fit for every line off the
                // taste file and the user's limits rule some of them out, so the page can be sorted.
                // The advice answer, when it came back, is the result: two or three bottles
                // explained. The ranking below it is the fallback, and the rest of the page is the
                // list of what was read, without ranks.
                val advice = result.advice
                val ranking = if (restaurant && result.bottles.size > 1 && advice == null) {
                    ListPicks.rank(
                        candidates = result.bottles.map {
                            ListPicks.Candidate(
                                wine = it.wine,
                                fit = it.fitScore,
                                order = it.listRank
                            )
                        },
                        rules = rules,
                        profile = AppGraph.palate.profile.value,
                        cellar = AppGraph.cellar.data.value.wines
                    )
                } else {
                    null
                }
                if (advice != null && result.bottles.size > 1 &&
                    (restaurant || result.scene == Scene.SHELF)
                ) {
                    AdviceBlock(advice = advice, bottles = result.bottles, shop = !restaurant)
                }
                if (restaurant && result.bottles.isNotEmpty()) {
                    // Said plainly, because a list that quietly keeps itself is a list nobody looks
                    // for again. This is where the reminder will land.
                    Text(
                        "Kept with today's date and the page as you photographed it — reopen it " +
                            "any time from Cellar › Saved wine menus, " +
                            "and name it after the restaurant if you want it findable.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (ranking != null) {
                    // The older per-wine judgement looks the same on screen, so say which one this
                    // is when the advice answer did not survive.
                    Text(
                        "Basic ranking — the advice answer did not come back, so this is the " +
                            "model's order for the wines it could judge.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    when (ranking.basis) {
                        // Three names and nothing else is not an answer either: the best line gets
                        // the read's own reason, and every place after it keeps its number.
                        // D5: the unit is chosen first and the number actually paid is ranked.
                        ListPicks.Basis.READ -> SectionCard("Worth ordering, best first") {
                            // Identity binding: the shortlist carries row ids only, and every
                            // field on a card comes from the one catalog row behind that id.
                            // buildCard (ListIdentity) is the only constructor; a card that
                            // fails the guard is dropped with its rowId, never repaired.
                            var service by remember(result) { mutableStateOf<Service?>(null) }
                            val bottleById = remember(result) {
                                result.bottles.associateBy { it.wine.id }
                            }
                            val catalog = remember(result) {
                                result.bottles.map { it.wine.toCatalogRow() }
                            }
                            val serviceRows = remember(result) {
                                result.bottles.map { it.toServiceRow() }
                            }
                            val serviceById = remember(serviceRows) {
                                serviceRows.associateBy { it.id }
                            }
                            val scored = remember(ranking) {
                                // Ids are the source of the ranking: no index fallback. If the
                                // shortlist is empty there is nothing to render.
                                ranking.topIds.map { id ->
                                    Scored(id, bottleById[id]?.fitReason.orEmpty())
                                }
                            }
                            // D5 then D1: filter by the unit that will actually be
                            // paid, then cap to one wine per producer, then rank.
                            // Capping first would drop the only pour in the chosen
                            // unit. Glass eligibility is a menu glass price only.
                            val ranked = remember(scored, service) {
                                rankForService(scored, serviceById, service)
                            }
                            val rendered = remember(ranked, catalog) {
                                ListIdentity.renderCards(
                                    scored = ranked,
                                    catalog = catalog,
                                    log = { message -> android.util.Log.w("WineVerdict", message) },
                                    warn = { message -> android.util.Log.w("WineVerdict", message) }
                                )
                            }
                            // D5: a glass answer may not contain a bottle-only wine.
                            val chosen = service
                            val unitError = remember(rendered, chosen) {
                                runCatching {
                                    if (chosen == Service.GLASS || chosen == Service.BOTTLE) {
                                        assertUniformUnit(
                                            rendered.cards.map { Scored(it.rowId, "") },
                                            serviceById,
                                            chosen
                                        )
                                    }
                                }.exceptionOrNull()?.message
                            }
                            Text(
                                unitLine(service),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { service = Service.GLASS }) {
                                    Text("Glass" + if (service == Service.GLASS) " ✓" else "")
                                }
                                OutlinedButton(onClick = { service = Service.BOTTLE }) {
                                    Text("Bottle" + if (service == Service.BOTTLE) " ✓" else "")
                                }
                                OutlinedButton(onClick = { service = Service.UNSURE }) {
                                    Text("Not sure" + if (service == Service.UNSURE) " ✓" else "")
                                }
                            }
                            if (service == Service.GLASS && serviceRows.none { it.glassUsd != null }) {
                                Text(
                                    "This list is bottle-only — no glass prices are printed. " +
                                        "Showing the bottle answer instead.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (unitError != null) {
                                Text(
                                    "Unit check failed ($unitError). Showing only wines with a " +
                                        "${service?.name?.lowercase()} price.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (service == Service.UNSURE) {
                                unsurePourLine(ranked, serviceById)?.let { line ->
                                    Text(
                                        line + ".",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            val eff = effectiveService(service)
                            val pool = serviceRows
                            rendered.cards.forEachIndexed { position, card ->
                                val row = serviceById[card.rowId]
                                // D2/D3: every description and reason is validated in code.
                                // A portable or superlative line fails closed to facts.
                                // The card itself is untouched (identity); what renders is
                                // the validated text.
                                val shownWhat = row?.let {
                                    validatedText(card.detail.trim(), it, pool, eff)
                                }.orEmpty()
                                val shownWhy = row?.let {
                                    validatedText(card.reason.trim(), it, pool, eff)
                                }.orEmpty()
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        // Numbered by where the line sits, not by the score it
                                        // was sorted on: a repeated number ("1. 2. 2.") is the
                                        // one thing a best-first list must never print.
                                        // The price is half the decision at a table, so it
                                        // belongs next to the name; both come from the same
                                        // row behind card.rowId.
                                        "${position + 1}. ${card.title}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    // D4: one strip on every card, all values from the record.
                                    if (row != null) {
                                        Text(
                                            strip(row),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    // "You have had this", said before the advice rather than
                                    // after it: it is the one thing on this card that is already
                                    // known to be true.
                                    bottleById[card.rowId]?.let { picked ->
                                        priorFor(picked.wine, cellarNow.wines, drankNow)?.let { known ->
                                            Text(
                                                known.summary,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    // What it is, then why it is first: a name and a producer blurb
                                    // leave the reader exactly where they started.
                                    if (shownWhat.isNotBlank()) {
                                        Text(
                                            shownWhat,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (shownWhy.isNotBlank()) {
                                        Text(shownWhy, style = MaterialTheme.typography.bodySmall)
                                    }
                                    val label = bottleById[card.rowId]
                                    Button(
                                        onClick = {
                                            if (label != null) {
                                                scope.launch {
                                                    // Matched by identity, not by the menu's id:
                                                    // the same wine already on record is that
                                                    // record, and a second row of the same bottle
                                                    // is not a second bottle.
                                                    val added = AppGraph.cellar.noteOrdered(label.wine)
                                                    // The one wine of the three the answer named
                                                    // that was actually ordered. The card's count
                                                    // is this, not the suggestion.
                                                    keptListId?.let { id ->
                                                        AppGraph.lists.choose(id, label.wine.id)
                                                    }
                                                    snackbarHostState.showSnackbar(
                                                        when {
                                                            !added -> "Already in your cellar — " +
                                                                if (eff == Service.GLASS) "noted for the glass"
                                                                else "noted for the bottle"
                                                            eff == Service.GLASS -> "Noted for a glass"
                                                            else -> "Noted for the bottle"
                                                        }
                                                    )
                                                }
                                            }
                                        },
                                        enabled = service != null && label != null
                                    ) {
                                        Text(orderButtonLabel(service))
                                    }
                                    if (label != null) {
                                        KeepLine(wine = label.wine, shop = false)
                                    }
                                }
                            }
                        }
                        // The read came back level, which is what an empty taste file produces. Say
                        // so, and say the one thing the page does separate, rather than sending the
                        // user to another screen before the list will say anything.
                        ListPicks.Basis.PRICE -> if (!localOnly) {
val idWine = remember(result) { result.bottles.associate { it.wine.id to it.wine } }
                            val cheap = ranking.cheapestId?.let { idWine[it] }
                            val dear = ranking.priciestId?.let { idWine[it] }
                            Banner(
                                "The judgement came back level, so no bottle is crowned here. The " +
                                    "one thing the page separates is price: " +
                                    "${cheap?.title?.take(40)} at " +
                                    "${formatMoney(cheap?.price, cheap?.currency.orEmpty())} and " +
                                    "${dear?.title?.take(40)} at " +
                                    "${formatMoney(dear?.price, dear?.currency.orEmpty())}."
                            )
                        }
                        ListPicks.Basis.NONE -> Banner(
                            "Nothing on this page separates the lines: the read ranked them level " +
                                "and the prices do not differ either, so they are left in menu order."
                        )
                    }
                }
                // Nothing on a printed list prints an ABV, so "the ceiling cannot be checked" under
                // every single card is a fact about the list, not about the wines: said once, here.
                val noAbvAtAll = result.bottles.isNotEmpty() &&
                    result.bottles.none { it.wine.alcohol != null }
                val hideAbvUnknown = restaurant && noAbvAtAll && rules.maxAlcohol != null
                // Only worth saying when it is the reason the page could not be ranked.
                if (rules.isEmpty && result.bottles.isNotEmpty() && ranking?.separated != true) {
                    Banner(
                        "Your limits are not set, so these picks are the model's own read of the " +
                            "wines rather than a filter against your taste. Set a sweetness or oak " +
                            "preference, or a budget band in Taste, and bottles that break them drop " +
                            "out of the ranking."
                    )
                }
                if (hideAbvUnknown) {
                    Banner(
                        "No ABV is printed on this list, so the ${rules.maxAlcohol}% ceiling cannot " +
                            "be checked on any of these bottles. That is the list, not the wines."
                    )
                }
                result.bottles.forEachIndexed { index, bottle ->
                    if (result.scene == Scene.BOTTLE) {
                        LabelResult(
                            result = bottle,
                            reReading = reReading,
                            onReRead = { reRead() },
                            onReview = {
                                // The bottle reaches the cellar through the editor, so the photo is
                                // saved and named on the draft before the screen changes: the
                                // record must not arrive pointing at a picture nobody wrote.
                                scope.launch {
                                    val photo = keepLabelPhoto(bottle.wine.id)
                                    ScanHandoff.put(bottle.wine.copy(photo = photo))
                                    onReview()
                                }
                            }
                        )
                    } else {
                        val record = bottle.wine.copy(
                            source = if (restaurant) "wine-list" else "shelf-scan"
                        )
                        BottleVerdictCard(
                            result = bottle,
                            restaurant = restaurant,
                            currency = currency,
                            hideAbvUnknown = hideAbvUnknown,
                            pick = ranking?.picks?.getOrNull(index),
                            adviceShown = result.advice != null,
                            onAdd = {
                                scope.launch {
                                    AppGraph.cellar.upsert(record)
                                    snackbarHostState.showSnackbar("Added ${record.title}")
                                    // One bottle confirmed from a label is worth an explanation; a
                                    // shelf of them is not, so a shelf card takes the answer this
                                    // wine already has and never pays for a second one.
                                    AppGraph.scope.launch {
                                        runCatching { AppGraph.insights.stampCached(record) }
                                    }
                                }
                            },
                            onReview = {
                                ScanHandoff.put(record)
                                onReview()
                            }
                        )
                    }
                }
                if (result.notes.isNotBlank()) {
                    Text(
                        result.notes,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ScanDetailsPanel(trace = debugTrace, basis = ranking?.basis?.name)
            }

            Text(
                "Photos are compressed on the phone and sent to $providerName with your own key, " +
                    "only when you tap Analyse. Nothing is stored in the cloud.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun LabelResult(
    result: ScannedLabel,
    reReading: Boolean,
    onReRead: () -> Unit,
    onReview: () -> Unit
) {
    SectionCard("What the model read") {
        if (!result.wineFound) {
            Text(
                "That photo does not look like a wine label. Get square to the front label, fill the frame, and avoid glare.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (result.readNotes.isNotBlank()) {
                Text(
                    result.readNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // A reply that leaves out `personal_fit` still shows what it read, but the app no
            // longer manufactures a score for the bottle: the facts and the label text are the
            // honest part, and the model's own verdict is shown only when it gave one.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("confidence: ${result.confidence}")
                result.fitScore?.let { Pill("AI match $it/5") }
            }
            Text(result.wine.title, style = MaterialTheme.typography.titleLarge)
            if (result.wine.subtitle.isNotBlank()) {
                Text(
                    result.wine.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // The same verdict a shelf card gets: a bottle in your hand is the usual shop moment.
            VerdictBlock(result.wine, restaurant = false, why = result.fitReason)
            if (result.wine.drinkWindowLabel != "unknown") {
                LabeledValue("Drinking window", result.wine.drinkWindowLabel)
            }
            val price = result.priceEstimate ?: result.wine.price
            if (price != null) {
                LabeledValue(
                    if (result.priceEstimate != null) "Typical price" else "Price on the tag",
                    formatMoney(price, result.wine.currency)
                )
            }
            if (result.wine.expertNotes.isNotBlank()) {
                Text(result.wine.expertNotes, style = MaterialTheme.typography.bodyMedium)
                AiDisclaimer()
            }
            if (result.labelText.isNotBlank()) {
                Text("Text on the label", style = MaterialTheme.typography.labelMedium)
                Text(
                    result.labelText.take(600),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (result.readNotes.isNotBlank()) {
                Text(
                    "Read notes: ${result.readNotes}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (result.uncertainFields.isNotEmpty() || result.nextTip.isNotBlank()) {
                SectionCard("To be sure") {
                    if (result.uncertainFields.isNotEmpty()) {
                        Text(
                            "Double-check: " + result.uncertainFields.joinToString(", ") + ".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (result.nextTip.isNotBlank()) {
                        Text(
                            "Next: " + result.nextTip,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (result.wine.alcohol == null) {
                        Text(
                            "Next: photograph the back label — that is usually where the ABV is.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (result.wine.alcohol == null && result.wineFound) {
                SectionCard("To be sure") {
                    Text(
                        "ABV was not legible, so the alcohol rule cannot be checked. Photograph the back label — that is usually where it is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                onClick = onReview,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Review and add to cellar")
            }
        }
        TextButton(onClick = onReRead, enabled = !reReading, modifier = Modifier.fillMaxWidth()) {
            Text(if (reReading) "Reading the label again…" else "Re-read the label more carefully")
        }
    }
}

/**
 * Keeps a wine that is not in the cellar: a pick off a list, a bottle somebody else opened.
 *
 * It is saved with no bottles, so no count, value or "ready now" moves; what it gains is a page of
 * its own, where the four words and the notes work exactly as they do on a bottle at home. The
 * action disappears once the wine is on record and says which kind of record it is — stock, or a
 * wine kept without any.
 */
@Composable
private fun KeepLine(wine: Wine, shop: Boolean) {
    val cellar by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val kept = remember(cellar.wines, wine) {
        cellar.wines.firstOrNull { InsightStore.key(it) == InsightStore.key(wine) }
    }
    if (kept == null) {
        TextButton(onClick = { scope.launch { AppGraph.cellar.saveWine(wine) } }) {
            Text(if (shop) "Save this wine" else "Save this one")
        }
    } else {
        Text(
            if (kept.quantity > 0) {
                "In your cellar · ${kept.quantity}"
            } else {
                "Saved with no bottles — give it a verdict from Cellar."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * What to order, from the advice answer.
 *
 * The name and the prices come from the label the phone read — the advice carries line numbers,
 * never text — so a pick cannot show a wine or a price the page does not print. The reason is the
 * model's own words, in full: it is the part of the answer worth reading.
 */
/**
 * The pour line, worded for the advice block.
 *
 * [unitLine] asks the reader to choose a pour, which is right above a ranked section that is
 * showing nothing until they do — but the advice block shows picks, glass and bottle from the
 * first frame, so its idle line has to say what is on screen, not what is missing.
 */
private fun adviceUnitLine(service: Service?): String = when (service) {
    null -> "Showing everything: bottle picks and the glass pour."
    Service.GLASS -> "Answering for a glass."
    Service.BOTTLE -> "Answering for a bottle."
    Service.UNSURE -> "Answering for either — glass and bottle both shown."
}

@Composable
private fun AdviceBlock(advice: ListAdvice, bottles: List<ScannedLabel>, shop: Boolean = false) {
    if (advice.picks.isEmpty()) return
    // The reader's own history, read once for the whole answer rather than once per card.
    val cellarNow by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val drankNow by AppGraph.drinks.drinks.collectAsStateWithLifecycle()
    // Which pour the answer is read in. The ranked section carried this before advice replaced it,
    // and it is the first question at a table: a list that pours by the glass and one that only
    // sells bottles need different answers, and the advice was written for both.
    var service by remember(advice) { mutableStateOf<Service?>(null) }
    val glassOnPage = bottles.any { it.glassPrice != null }
    val picks = if (service == Service.GLASS) {
        advice.picks.filter { bottles.getOrNull(it.index)?.glassPrice != null }
    } else {
        advice.picks
    }
    SectionCard(if (shop) "What to buy" else "What to order") {
        Text(
            if (shop) {
                "Picked from what the shelf prints: the price and what the bottle adds."
            } else {
                adviceUnitLine(service)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // A shelf sells bottles, and a shop price is the whole question, so there is no glass
        // toggle and no pour answer to choose between.
        if (!shop) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { service = Service.GLASS }) {
                    Text(if (service == Service.GLASS) "Glass ✓" else "Glass")
                }
                OutlinedButton(onClick = { service = Service.BOTTLE }) {
                    Text(if (service == Service.BOTTLE) "Bottle ✓" else "Bottle")
                }
                OutlinedButton(onClick = { service = Service.UNSURE }) {
                    Text(if (service == Service.UNSURE) "Not sure ✓" else "Not sure")
                }
            }
        }
        if (service == Service.GLASS && !glassOnPage) {
            Text(
                "This list is bottle-only — no glass prices are printed. Showing the bottle answer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (service == Service.GLASS && picks.isEmpty()) {
            Text(
                "None of these picks is poured by the glass. The glass answer is just below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        picks.forEachIndexed { position, pick ->
            val bottle = bottles.getOrNull(pick.index)
            if (bottle != null) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Pill(
                        when (position) {
                            0 -> if (shop) "Buy this" else "Order this"
                            1 -> "Second"
                            else -> "Third"
                        },
                        color = if (position == 0) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                    Text(bottle.wine.title, style = MaterialTheme.typography.titleMedium)
                    priceLine(bottle)?.let { prices ->
                        Text(
                            prices,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // The one line on this pick that is already known to be true: you have had it,
                    // or you own it — said before the reason, not after it.
                    priorFor(bottle.wine, cellarNow.wines, drankNow)?.let { known ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Pill(known.summary, color = MaterialTheme.colorScheme.primaryContainer)
                            known.verdict?.let { Pill(it) }
                        }
                    }
                    if (pick.headline.isNotBlank()) {
                        Text(pick.headline, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (pick.why.isNotBlank()) {
                        Text(pick.why, style = MaterialTheme.typography.bodySmall)
                    }
                    if (pick.value != null) {
                        Pill(
                            "${pick.value} value",
                            color = if (pick.value == "good") {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                        if (pick.valueNote.isNotBlank()) {
                            Text(
                                pick.valueNote,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    KeepLine(wine = bottle.wine, shop = shop)
                }
            }
        }

        if (!shop && service != Service.BOTTLE) advice.glass?.let { glass ->
            val bottle = bottles.getOrNull(glass.index)
            if (bottle != null) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("By the glass", style = MaterialTheme.typography.labelMedium)
                    Text(bottle.wine.title, style = MaterialTheme.typography.titleSmall)
                    priceLine(bottle)?.let { prices ->
                        Text(
                            prices,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val sentence = glass.why.ifBlank { glass.headline }
                    if (sentence.isNotBlank()) {
                        Text(
                            sentence,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (advice.skips.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Skip", style = MaterialTheme.typography.labelMedium)
                advice.skips.forEach { skip ->
                    val bottle = bottles.getOrNull(skip.index)
                    if (bottle != null) {
                        Text(
                            "${bottle.wine.title} — ${skip.why.ifBlank { skip.headline }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (advice.note.isNotBlank()) {
            Text(
                advice.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** The prices the menu prints for this line, in the record's own currency. */
private fun priceLine(label: ScannedLabel): String? {
    val parts = mutableListOf<String>()
    label.wine.price?.let { parts += "${formatMoney(it, label.wine.currency)} bottle" }
    label.glassPrice?.let { parts += "${formatMoney(it, label.wine.currency)} glass" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
}

/**
 * The buy-or-leave verdict, shared by the single-bottle card and the shelf and list cards.
 *
 * A lone bottle photographed is the ordinary shop case, so it gets the same verdict as a shelf. The
 * price band only enters when a price tag was actually read, and at a restaurant it never enters.
 */
@Composable
private fun VerdictBlock(
    wine: Wine,
    restaurant: Boolean,
    hideAbvUnknown: Boolean = false,
    pick: ListPicks.Pick? = null,
    why: String = "",
    adviceShown: Boolean = false
) {
    val rules = AppGraph.palate.rules
    val profile = AppGraph.palate.profile.value
    val cellar = AppGraph.cellar.data.value.wines
    if (adviceShown) {
        // The advice already answered which bottles to buy. A second headline under every card is
        // the noise it replaced, so all that is left is what the app knows and the answer does not:
        // a limit broken, a window passed, a bottle already at home. Never an unknown.
        if (why.isNotBlank()) {
            Text(why, style = MaterialTheme.typography.bodyMedium)
        }
        val chips = remember(wine, rules, cellar) { BuyAdvice.chips(wine, rules, cellar) }
        chips.forEach { chip ->
            Text(
                text = (if (chip.warning) "\u2715  " else "\u00b7  ") + chip.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (chip.warning) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        return
    }
    val advice = remember(wine, rules, profile, cellar, restaurant) {
        BuyAdvice.forBottle(
            wine = wine,
            rules = rules,
            profile = profile,
            cellar = cellar,
            occasion = if (restaurant) BuyAdvice.Occasion.RESTAURANT else BuyAdvice.Occasion.SHOP,
            // What most wine goes with is the only standing dish the app has; without it the
            // 14.0–14.5 allowance would live only in tests.
            dish = PairingTable.dishFor(profile.choice(QuickStart.Q_CONTEXT))
        )
    }
    val leaving = advice.verdict == BuyAdvice.Verdict.LEAVE
    // On a list the useful headline is the ranking, not the same "nothing wrong with it" nine times:
    // the best line is called out, the runners-up are numbered, and a line that breaks one of the
    // user's limits keeps its warning whatever the ranking says.
    val headline = when {
        leaving -> advice.verdict.label(restaurant)
        pick?.rank == 1 && !pick.tied -> BuyAdvice.Verdict.BUY.label(restaurant)
        pick != null && pick.rank != null -> pick.label
        pick != null && pick.note.isNotBlank() -> pick.note
        else -> advice.verdict.label(restaurant)
    }
    val tint = when {
        leaving -> MaterialTheme.colorScheme.errorContainer
        pick?.rank == 1 -> MaterialTheme.colorScheme.primaryContainer
        pick != null && pick.rank != null -> MaterialTheme.colorScheme.secondaryContainer
        advice.verdict == BuyAdvice.Verdict.BUY -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    // A list is not a shop: "Order the bottle" under every line is the noise the ranking replaced,
    // so the Buy/Leave chip stays for a shelf and for a bottle in the hand. A ranked line keeps its
    // place, and a line that breaks one of the user's limits still says why below.
    if (!restaurant || pick?.rank != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(headline, color = tint)
        }
    }
    // Why this one sits where it does, in the read's own words: a pill on its own ("Best pick")
    // tells the reader nothing about the bottle under it.
    if (why.isNotBlank()) {
        Text(why, style = MaterialTheme.typography.bodyMedium)
    }
    advice.reasons.forEach { line ->
        Text(
            text = (if (leaving) "\u2715  " else "\u00b7  ") + line,
            style = MaterialTheme.typography.bodySmall,
            color = if (leaving) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
    // The gap a bottle would fill sits below the reasons, quieter: it is a reason to be curious
    // about the bottle, not a reason to buy it, and it never lifts the verdict on its own.
    // On a list there is no cellar purchase to be curious about: "your first bottle from
    // Catalonia" is not an argument for ordering it off a menu.
    if (!restaurant) advice.gaps.forEach { line ->
        Text(
            text = "\u00b7  $line",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
    advice.unknowns
        .filterNot { hideAbvUnknown && it.startsWith("no ABV") }
        .forEach { line ->
            Text(
                text = "?  $line",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
}

/** One wine out of a shelf or a list, with its verdict. No ranking across the others. */
@Composable
private fun BottleVerdictCard(
    result: ScannedLabel,
    restaurant: Boolean,
    currency: String,
    hideAbvUnknown: Boolean = false,
    pick: ListPicks.Pick? = null,
    adviceShown: Boolean = false,
    onAdd: () -> Unit,
    onReview: () -> Unit
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (result.confidence != "high") Pill("confidence: ${result.confidence}")
                if (!restaurant && result.valueVerdict != "unknown") Pill(result.valueVerdict)
            }
            Text(result.wine.title, style = MaterialTheme.typography.titleMedium)
            Text(
                result.wine.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // What this wine already is to you — on the shelf, opened before, judged — which is the
            // one question a printed menu can never answer and this app can.
            val cellarNow by AppGraph.cellar.data.collectAsStateWithLifecycle()
            val drankNow by AppGraph.drinks.drinks.collectAsStateWithLifecycle()
            priorFor(result.wine, cellarNow.wines, drankNow)?.let { known ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Pill(known.summary, color = MaterialTheme.colorScheme.primaryContainer)
                    known.verdict?.let { Pill(it) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val money = result.wine.currency.ifBlank { currency }
                val bottle = result.wine.price
                val glass = result.glassPrice
                // On a list the two prices are different questions: what a glass costs now, and what
                // the bottle costs if the table finishes it. Show both, labelled, or neither.
                if (bottle != null || glass != null) {
                    Text(
                        buildString {
                            if (bottle != null) append(formatMoney(bottle, money))
                            if (bottle != null && glass != null) append("  ·  ")
                            if (glass != null) append("glass ").append(formatMoney(glass, money))
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                result.wine.alcohol?.let { Pill(abvLabel(it)) }
                if (result.wine.drinkWindowLabel != "unknown") Pill(result.wine.drinkWindowLabel)
            }
            // What the wine actually is, in the judgement's own words: on a list of names nobody
            // recognises, this is the line that answers "what am I looking at".
            if (result.wine.expertNotes.isNotBlank()) {
                Text(
                    result.wine.expertNotes,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            VerdictBlock(
                result.wine, restaurant, hideAbvUnknown, pick, result.fitReason,
                adviceShown = adviceShown
            )
            val shelfDoubt = buildList {
                if (result.uncertainFields.isNotEmpty()) add("Unsure: " + result.uncertainFields.joinToString(", "))
                if (result.nextTip.isNotBlank()) add(result.nextTip)
                // No printed list carries an ABV, so on a list this is a fact about menus, said
                // once in a banner, not a caveat repeated under every card.
                else if (!restaurant && result.wine.alcohol == null) {
                    add("ABV not visible — the alcohol rule cannot be checked from this photo.")
                }
            }
            shelfDoubt.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (restaurant) {
                // A list is where a wine is met that nobody owns yet, so keeping it records the
                // meeting without claiming a bottle.
                KeepLine(wine = result.wine, shop = false)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAdd) {
                        Icon(Icons.Filled.ShoppingCart, contentDescription = null)
                        Text("  Add")
                    }
                    TextButton(onClick = onReview) { Text("Review") }
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Text(
            text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The batch queue: how many photos have been read, how many bottles came out of them, and roughly
 * how long the free tier needs for the rest. Reading happens in the background, so nothing here
 * blocks the next photograph.
 */
@Composable
private fun BatchQueueCard(
    items: List<PendingScan>,
    running: Boolean,
    waitingUntil: Long,
    providers: Int,
    framesPerMinute: Double,
    advice: SceneScan?,
    advising: Boolean,
    onAdvise: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onAddAll: () -> Unit,
    onClear: () -> Unit,
    onRetryFailed: () -> Unit,
    onAdd: (Wine) -> Unit,
    onReview: (Wine) -> Unit
) {
    val framesRead = items.count { it.status == PendingScan.DONE }
    val failed = items.count { it.status == PendingScan.FAILED }
    val pending = items.count { it.status == PendingScan.QUEUED || it.status == PendingScan.READING }
    val bottles = remember(items) { AppGraph.scanQueue.readBottles() }
    val notAdded = bottles.count { !it.added }
    val eta = ScanQueue.estimateMinutes(pending, framesPerMinute)
    val perMinute = ScanQueue.bottlesPerMinute(framesPerMinute)

    SectionCard("Batch queue") {
        Text(
            buildString {
                append("$framesRead photos read · ${bottles.size} bottles found")
                if (pending > 0) append(" · $pending to go")
                if (failed > 0) append(" · $failed failed")
                if (eta > 0) append(" · about $eta min")
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            if (providers > 1) {
                "A photo of three to five bottles is read in one request, spread across $providers " +
                    "providers: about $perMinute bottles a minute."
            } else {
                "A photo of three to five bottles is read in one request: about $perMinute bottles a " +
                    "minute on this provider. Adding a second provider or vision model raises it."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        if (waitingUntil > 0) Countdown(waitingUntil)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending > 0) {
                OutlinedButton(onClick = if (running) onPause else onStart) {
                    Text(if (running) "Pause" else "Resume")
                }
            }
            if (notAdded > 0) {
                Button(onClick = onAddAll) { Text("Add all $notAdded to cellar") }
            }
            if (failed > 0) {
                TextButton(onClick = onRetryFailed) { Text("Retry failed") }
            }
            if (framesRead > 0 || failed > 0) {
                TextButton(onClick = onClear) { Text("Clear finished") }
            }
        }
        // The shelf's answer covers every frame the queue has read, so it is asked for once here
        // rather than per photo: three picks across the shelf, not three per frame.
        val shelfPicks = advice?.advice
        if (bottles.size > 1) {
            if (shelfPicks != null) {
                AdviceBlock(advice = shelfPicks, bottles = advice?.bottles.orEmpty(), shop = true)
            } else {
                OutlinedButton(onClick = onAdvise, enabled = !advising) {
                    Text(if (advising) "Asking what to buy…" else "What to buy from this shelf")
                }
            }
        }
        bottles.take(10).forEachIndexed { index, bottle ->
            key(index) {
                val wine = bottle.label.wine
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(wine.title, style = MaterialTheme.typography.bodyMedium)
                        val detail = listOfNotNull(
                            wine.subtitle.takeIf { it.isNotBlank() },
                            "confidence: ${bottle.label.confidence}",
                            if (bottle.added) "in cellar" else null
                        ).joinToString(" · ")
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onReview(wine) }) { Text("Review") }
                    if (!bottle.added) {
                        TextButton(onClick = { onAdd(wine) }) { Text("Add") }
                    }
                }
            }
        }
        if (bottles.size > 10) {
            Text(
                "…and ${bottles.size - 10} more, all included in Add all.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun Countdown(until: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        while (until > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }
    val seconds = ((until - now) / 1000).coerceAtLeast(0)
    if (seconds > 0) {
        Text(
            "Waiting ${seconds}s for the free-tier token budget — nothing to do.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
/**
 * What the last scan did, so a wrong answer can be diagnosed on the phone itself.
 *
 * Debug builds always show it; a release build (including the sideload APK) shows it only after
 * Settings → Show scan details. Nothing here is persisted — it is the scan that just ran, held in
 * memory, and it is gone when the process dies.
 */
@Composable
private fun ScanDetailsPanel(trace: ScanTrace?, basis: String?) {
    if (!BuildConfig.DEBUG && !AppGraph.settings.showScanDetails) return
    SectionCard("Scan details") {
        if (trace == null) {
            Text(
                "No scan recorded in this session yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        Text(
            "${trace.wineCount} wines read · scene ${trace.scene.ifBlank { "unknown" }} · " +
                "ranking ${basis ?: "not ranked"}",
            style = MaterialTheme.typography.bodyMedium
        )
        // One row per request: who answered, with which model, how long the answer was. A step
        // with no row did not complete, which is the fact worth seeing.
        trace.requests.forEach { request ->
            Text(
                request.summary,
                style = MaterialTheme.typography.labelSmall,
                color = if (request.ok) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
        }
        if (trace.dropped.isNotEmpty()) {
            Text("Advice lines dropped", style = MaterialTheme.typography.labelMedium)
            trace.dropped.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (trace.answerStep.isNotBlank()) {
            Text(
                "${trace.answerStep}: ${trace.answerChars} characters",
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                trace.answer.take(4_000),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (trace.lines.isNotEmpty()) {
            Text(
                "Lines read on the phone (${trace.lines.size})",
                style = MaterialTheme.typography.labelMedium
            )
            trace.lines.take(30).forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
