package com.vinoteca.app.ui

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
import com.vinoteca.app.AppGraph
import com.vinoteca.app.ScanHandoff
import com.vinoteca.app.ai.Prompts
import com.vinoteca.app.ai.ScanParsing
import com.vinoteca.app.ai.ScannedLabel
import com.vinoteca.app.ai.Scene
import com.vinoteca.app.ai.SceneScan
import com.vinoteca.app.ai.Thinking
import com.vinoteca.app.ai.digest
import com.vinoteca.app.ai.withFinePrint
import com.vinoteca.app.data.BuyAdvice
import com.vinoteca.app.data.ImportRow
import com.vinoteca.app.data.PendingScan
import com.vinoteca.app.data.ScanQueue
import com.vinoteca.app.model.ChatMessage
import com.vinoteca.app.model.Wine
import com.vinoteca.app.model.formatMoney
import com.vinoteca.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One shutter, no mode switch. The model names the scene in the same reply that reads the bottles,
 * because the answer is in the photo — and a wrongly chosen chip used to change how prices were
 * judged without telling anyone.
 */
private const val SCAN_HINT =
    "One photo of whatever is in front of you — a bottle's label, a shop shelf, or a restaurant wine " +
        "list. Every wine is read out of it and gets a buy-or-leave verdict from your own limits, the " +
        "price in front of you, and what your cellar is missing."

private fun abvLabel(value: Double): String =
    String.format(java.util.Locale.US, "%.1f%%", value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onReview: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val currency = AppGraph.settings.currency

    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scan by remember { mutableStateOf<SceneScan?>(null) }
    var reReading by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
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
        scope.launch {
            try {
                val taste = AppGraph.taste.brief()
                val known = AppGraph.cellar.data.value.wines.map { it.title }.distinct()
                // One image per request. The free tier bills an image at a fixed 2,048 tokens, and
                // two of them left too little room for a long answer: that is what kept cutting the
                // JSON off. The centre crop is a label-only tool and is sent later, on its own.
                val full = withContext(Dispatchers.IO) {
                    Base64.encodeToString(
                        ImageUtils.jpegBytes(context, uri, maxDim = 2200, quality = 90),
                        Base64.NO_WRAP
                    )
                }
                val small = withContext(Dispatchers.IO) {
                    Base64.encodeToString(
                        ImageUtils.jpegBytes(context, uri, maxDim = 1200, quality = 80),
                        Base64.NO_WRAP
                    )
                }

                // What is this photo? A single word on a small image, and if that question fails it
                // changes nothing: every path has a fallback below.
                val scene = runCatching {
                    val word = AppGraph.ai.complete(
                        system = Prompts.sceneCheck(),
                        turns = listOf(ChatMessage(role = "user", text = "One word: what is in this photo?")),
                        imagesBase64 = listOf(small),
                        vision = true,
                        jsonMode = false,
                        thinking = Thinking.OFF,
                        temperature = 0.0,
                        maxTokens = 24
                    )
                    ScanParsing.sceneWord(word)
                }.getOrNull()

                var lastProblem: String? = null

                // Plain lines: no braces, no schema, nothing that can arrive half-written. It handles
                // a list, a shelf and a single bottle, and it is the fallback for every failure.
                val readLines: suspend () -> SceneScan? = {
                    runCatching {
                        val raw = AppGraph.ai.complete(
                            system = Prompts.listLines(currency),
                            turns = listOf(
                                ChatMessage(
                                    role = "user",
                                    text = "Copy every wine in this photo as one line each."
                                )
                            ),
                            imagesBase64 = listOf(full),
                            vision = true,
                            jsonMode = false,
                            thinking = Thinking.OFF,
                            temperature = 0.1,
                            maxTokens = 1_600
                        )
                        val labels = ScanParsing.parseListLines(raw, currency)
                        if (labels.isEmpty()) null
                        else SceneScan(
                            scene = if (labels.size == 1) Scene.BOTTLE else if (scene == Scene.LIST) Scene.LIST else Scene.SHELF,
                            bottles = labels,
                            notes = ""
                        )
                    }.onFailure { lastProblem = it.message }.getOrNull()
                }

                // The structured reader: richer per bottle (notes, window, fit), and the only path
                // that gets those. Any failure here is survivable now.
                val readJson: suspend () -> SceneScan? = {
                    runCatching {
                        val raw = AppGraph.ai.complete(
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
                            maxTokens = 3600
                        )
                        ScanParsing.parseScene(raw, currency)
                    }.onFailure { lastProblem = it.message }.getOrNull()
                }

                // A bottle is worth the richer read; a list or shelf is not, and gets lines first.
                var parsed = if (scene == Scene.BOTTLE) {
                    readJson()?.takeIf { it.bottles.isNotEmpty() } ?: readLines()
                } else {
                    readLines() ?: readJson()?.takeIf { it.bottles.isNotEmpty() }
                }

                if (parsed == null) {
                    error = lastProblem?.takeIf { it.isNotBlank() }
                        ?: "Nothing readable came back from that photo. Try again, or shoot the page in two halves."
                }

                // A single bottle missing its ABV or vintage gets the small-print pass on the crop.
                // Those two figures decide the verdict and the window, so they are worth one more
                // cheap request — the answer is a dozen short fields, not a whole reading.
                val only = parsed?.bottles?.singleOrNull()
                if (parsed != null && only != null &&
                    (only.wine.alcohol == null || only.wine.vintage == null)
                ) {
                    val crop = withContext(Dispatchers.IO) {
                        Base64.encodeToString(
                            ImageUtils.labelCropJpeg(context, uri, maxDim = 1800),
                            Base64.NO_WRAP
                        )
                    }
                    val fineRaw = runCatching {
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
                            maxTokens = 600
                        )
                    }.getOrNull()
                    val fine = fineRaw?.let { runCatching { ScanParsing.parseLabel(it, currency) }.getOrNull() }
                    if (fine != null) {
                        parsed = parsed.copy(bottles = listOf(only.withFinePrint(fine)))
                    }
                }

                scan = parsed
            } catch (e: Exception) {
                error = e.message ?: "Scan failed. Try again."
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
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
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
                result.bottles.forEach { bottle ->
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
                "Photos are compressed on the phone and sent to Groq only when you tap Analyse. Nothing is stored in the cloud.",
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
            VerdictBlock(result.wine, restaurant = false)
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
            if (result.fitReason.isNotBlank()) {
                Text(result.fitReason, style = MaterialTheme.typography.bodyMedium)
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
private fun VerdictBlock(wine: Wine, restaurant: Boolean) {
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
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(
            advice.verdict.label(restaurant),
            color = when (advice.verdict) {
                BuyAdvice.Verdict.BUY -> MaterialTheme.colorScheme.primaryContainer
                BuyAdvice.Verdict.LOOK -> MaterialTheme.colorScheme.secondaryContainer
                BuyAdvice.Verdict.LEAVE -> MaterialTheme.colorScheme.errorContainer
            }
        )
    }
    val leaving = advice.verdict == BuyAdvice.Verdict.LEAVE
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
    advice.unknowns.forEach { line ->
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
            VerdictBlock(result.wine, restaurant)
            if (result.fitReason.isNotBlank()) {
                Text(result.fitReason, style = MaterialTheme.typography.bodyMedium)
            }
            val shelfDoubt = buildList {
                if (result.uncertainFields.isNotEmpty()) add("Unsure: " + result.uncertainFields.joinToString(", "))
                if (result.nextTip.isNotBlank()) add(result.nextTip)
                else if (result.wine.alcohol == null) add("ABV not visible — the alcohol rule cannot be checked from this photo.")
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