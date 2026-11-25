package com.wineverdict.app.ui

import android.net.Uri
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
import com.wineverdict.app.ScanHandoff
import com.wineverdict.app.ai.AiException
import com.wineverdict.app.ai.Prompts
import com.wineverdict.app.ai.ScanParsing
import com.wineverdict.app.ai.ScannedLabel
import com.wineverdict.app.ai.Scene
import com.wineverdict.app.ai.SceneScan
import com.wineverdict.app.ai.Thinking
import com.wineverdict.app.ai.digest
import com.wineverdict.app.ai.withFinePrint
import com.wineverdict.app.data.BuyAdvice
import com.wineverdict.app.data.ImportRow
import com.wineverdict.app.data.ListIdentity
import com.wineverdict.app.data.ListPicks
import com.wineverdict.app.data.Scored
import com.wineverdict.app.data.toCatalogRow
import com.wineverdict.app.data.PendingScan
import com.wineverdict.app.data.ScanQueue
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
private const val JUDGE_TIMEOUT_MS = 45_000L

/** The whole scan never outlives this: first byte to last card. No scan takes minutes. */
private const val SCAN_DEADLINE_MS = 95_000L

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
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scan by remember { mutableStateOf<SceneScan?>(null) }
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
    LaunchedEffect(loading) {
        if (!loading) return@LaunchedEffect
        elapsed = 0
        while (loading) {
            delay(1_000L)
            elapsed += 1
        }
    }
    val queueItems by AppGraph.scanQueue.items.collectAsStateWithLifecycle()
    val queueWaitingUntil by AppGraph.scanQueue.waitingUntil.collectAsStateWithLifecycle()
    val queueRunning by AppGraph.scanQueue.running.collectAsStateWithLifecycle()

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
            // Remaining budget for the network reads; every step below spends from it.
            fun remainingMs(): Long =
                SCAN_DEADLINE_MS - (System.currentTimeMillis() - scanStart)
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
                // The phone's own reading of a printed list, shown as real cards while the model
                // works: names, prices and the order down the page are all on the photo already,
                // and a provider that times out must not take them down with it.
                val onPhone = instantLines?.let { LocalListRead.wines(it, currency) }
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
                val readJson: suspend () -> SceneScan? = {
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
                            onBudgetWait = hold
                        )
                        }
                        waitedFor = 0L
                        ScanParsing.parseScene(raw, currency)
                    }.onFailure { lastProblem = it.message }.getOrNull()
                }

                // The small print on the label's centre crop: ABV, vintage, volume, appellation.
                // A dozen short fields, so it is the cheapest request in the scan — and the one
                // that decides the alcohol rule and the drinking window.
                suspend fun readFinePrint(crop: String): ScannedLabel? {
                    val fineRaw = runCatching {
                        withTimeoutOrNull(minOf(RETRY_TIMEOUT_MS, remainingMs())) {
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
                                maxTokens = 400
                            )
                        }
                    }.getOrNull()
                    return fineRaw?.let { runCatching { ScanParsing.parseLabel(it, currency) }.getOrNull() }
                }

                // The judgement on a list the phone read: text in, ranks out, no image. A short
                // text answer is what a free tier is fastest at, and the lines match the wines by
                // number, so the model's spelling changes nothing on the cards. This is the answer
                // for a list — what the wines are and which to order — so it gets the longest
                // budget of any text call.
                suspend fun judgeList(read: SceneScan): SceneScan? {
                    return runCatching {
                        val raw = withTimeoutOrNull(minOf(JUDGE_TIMEOUT_MS, remainingMs())) {
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
                                maxTokens = 800,
                                onBudgetWait = hold
                            )
                        } ?: throw AiException("The ranking did not arrive in time.")
                        waitedFor = 0L
                        ScanParsing.applyJudgement(read, raw)
                    }.onFailure { lastProblem = it.message }.getOrNull()
                }

                // Plain lines, and the first request: no braces, no schema, nothing that can arrive
                // half-written, and the scene and the order come back in the same answer. That is
                // one round trip for a list instead of a cheap question followed by a real one.
                // Hedged when two keys exist: after 12s with no answer the second provider is
                // asked too and whichever answers first wins — one stalled provider no longer
                // costs its full timeout before the other is tried.
                suspend fun readPage(): String {
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
                                        onBudgetWait = hold
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
                                        onBudgetWait = hold
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
                    if (hedged != null) return hedged
                    waitedFor = 0L
                    // Neither answered in time: one smaller retry on whatever budget is left.
                    val left = remainingMs()
                    if (left < 8_000L) throw AiException(
                        "$who did not answer in time ($photoKb KB up). Nothing was saved — " +
                            "try again on Wi-Fi, or add the other provider's key in Settings."
                    )
                    stage = "Retrying with a smaller photo" +
                        (second?.let { " on ${it.shortLabel}" } ?: " on $who")
                    val small = withContext(Dispatchers.IO) {
                        Base64.encodeToString(
                            ImageUtils.jpegBytes(context, uri, maxDim = 1_200, quality = 75),
                            Base64.NO_WRAP
                        )
                    }
                    return withTimeoutOrNull(minOf(RETRY_TIMEOUT_MS, left)) {
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
                            onBudgetWait = hold
                        )
                    } ?: throw AiException(
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
                    stage = "Judging the wines — what they are and which to order"
                    parsed = judgeList(onPhone)
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
                            notes = ""
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
                val only = parsed?.bottles?.singleOrNull()
                if (!finePrinted && parsed != null && only != null &&
                    (only.wine.alcohol == null || only.wine.vintage == null) &&
                    remainingMs() > 10_000L
                ) {
                    val fine = cropDeferred.await()?.let { readFinePrint(it) }
                    if (fine != null) {
                        parsed = parsed.copy(bottles = listOf(only.withFinePrint(fine)))
                    }
                }

                if (parsed != null) {
                    scan = parsed
                    localOnly = false
                }
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
                val ranking = if (restaurant && result.bottles.size > 1) {
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
                if (ranking != null) {
                    when (ranking.basis) {
                        // Three names and nothing else is not an answer either: the best line gets
                        // the read's own reason, and every place after it keeps its number.
                        ListPicks.Basis.READ -> SectionCard("Worth ordering, best first") {
                            // Identity binding: the shortlist carries row ids only, and every
                            // field on a card comes from the one catalog row behind that id.
                            // buildCard (ListIdentity) is the only constructor; a card that
                            // fails the guard is dropped with its rowId, never repaired.
                            val bottleById = remember(result) {
                                result.bottles.associateBy { it.wine.id }
                            }
                            val catalog = remember(result) {
                                result.bottles.map { it.wine.toCatalogRow() }
                            }
                            val scored = remember(ranking, result) {
                                ranking.topIds.ifEmpty {
                                    ranking.top.mapNotNull { bottleById.values.elementAtOrNull(it)?.wine?.id }
                                }.map { id ->
                                    Scored(id, bottleById[id]?.fitReason.orEmpty())
                                }
                            }
                            val rendered = remember(scored, catalog) {
                                ListIdentity.renderCards(
                                    scored = scored,
                                    catalog = catalog,
                                    log = { message -> android.util.Log.w("WineVerdict", message) },
                                    warn = { message -> android.util.Log.w("WineVerdict", message) }
                                )
                            }
                            rendered.cards.forEachIndexed { position, card ->
                                val why = card.reason.trim()
                                val what = card.detail.trim()
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
                                    // What it is, then why it is first: a name and a producer blurb
                                    // leave the reader exactly where they started.
                                    if (what.isNotBlank()) {
                                        Text(
                                            what,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (why.isNotBlank()) {
                                        Text(why, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        // The read came back level, which is what an empty taste file produces. Say
                        // so, and say the one thing the page does separate, rather than sending the
                        // user to another screen before the list will say anything.
                        ListPicks.Basis.PRICE -> if (!localOnly) {
                            val cheap = ranking.cheapest?.let { result.bottles[it].wine }
                            val dear = ranking.priciest?.let { result.bottles[it].wine }
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
                            "wines rather than a filter against your taste. Set an alcohol ceiling, " +
                            "a sweetness or oak preference, or a budget band in Taste and bottles " +
                            "that break them drop out of the ranking."
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
                                ScanHandoff.put(bottle.wine)
                                onReview()
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
                            onAdd = {
                                scope.launch {
                                    AppGraph.cellar.upsert(record)
                                    snackbarHostState.showSnackbar("Added ${record.title}")
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
    why: String = ""
) {
    val rules = AppGraph.palate.rules
    val profile = AppGraph.palate.profile.value
    val cellar = AppGraph.cellar.data.value.wines
    val advice = remember(wine, rules, profile, cellar, restaurant) {
        BuyAdvice.forBottle(
            wine = wine,
            rules = rules,
            profile = profile,
            cellar = cellar,
            occasion = if (restaurant) BuyAdvice.Occasion.RESTAURANT else BuyAdvice.Occasion.SHOP
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
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(headline, color = tint)
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
            VerdictBlock(result.wine, restaurant, hideAbvUnknown, pick, result.fitReason)
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
            if (!restaurant) {
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
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}