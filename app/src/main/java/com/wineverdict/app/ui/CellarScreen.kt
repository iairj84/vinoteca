package com.wineverdict.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wineverdict.app.AppGraph
import com.wineverdict.app.ai.BottleCandidate
import com.wineverdict.app.data.DetailsBackfill
import com.wineverdict.app.data.InsightStore
import com.wineverdict.app.data.dayWords
import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.drinkStatus
import com.wineverdict.app.model.formatMoney
import com.wineverdict.app.model.locationName
import com.wineverdict.app.model.stats
import com.wineverdict.app.util.ImageUtils
import kotlinx.coroutines.launch

private enum class CellarFilter(val label: String) {
    ALL("All"),
    RED("Red"),
    WHITE("White"),
    SPARKLING("Sparkling"),
    READY("Ready now"),
    LOVED("Loved"),
    // A wine on record with no bottles: kept off a list, or every bottle drunk. It used to be
    // reachable only by scrolling to the bottom of the cellar. Label says what it shows, because
    // a saved wine and a bottle you finished are the same thing to the record.
    EMPTY("No bottles")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellarScreen(
    onOpenWine: (String) -> Unit,
    onAddWine: () -> Unit,
    onScan: () -> Unit,
    onOpenLists: () -> Unit,
    onOpenDrinks: () -> Unit
) {
    val data by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val savedLists by AppGraph.lists.lists.collectAsStateWithLifecycle()
    val drinks by AppGraph.drinks.drinks.collectAsStateWithLifecycle()
    // A candidate the cellar already holds is a second bottle, not a twin: the same identity the
    // add matches on, so a card can say what the tap will do before it is tapped.
    val owned = remember(data) {
        data.wines.associateBy { InsightStore.key(it) }
    }
    var query by remember { mutableStateOf("") }
    // The filter outlives the screen: opening a bottle and coming back rebuilds this composable, and
    // a filter that resets on every lookup is a filter nobody keeps. Held by its label because a
    // string is what a saved screen state can carry; the enum is read back from it.
    var filterLabel by rememberSaveable { mutableStateOf(CellarFilter.ALL.label) }
    val filter = CellarFilter.entries.firstOrNull { it.label == filterLabel } ?: CellarFilter.ALL
    // The list's quick action. The id, not the bottle: the sheet reads the record as it is, so the
    // count it shows is the count after the tap that opened it.
    var drankId by remember { mutableStateOf<String?>(null) }
    val snackbarState = remember { SnackbarHostState() }

    // The search box above the list filters the cellar, and only the cellar. Adding a bottle that is
    // not in it is a different question, asked by the + button: the bottle in words, one search over
    // the result titles and snippets, a short list of the bottlings those results name, and the
    // pages read only for the one picked.
    val scope = rememberCoroutineScope()
    var addOpen by remember { mutableStateOf(false) }
    var words by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<BottleCandidate>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }

    fun closeAdd() {
        addOpen = false
        words = ""
        asked = null
        candidates = emptyList()
        searchError = null
    }

    fun lookUp(text: String) {
        val clean = text.trim()
        if (clean.length < 3) return
        asked = clean
        searching = true
        candidates = emptyList()
        searchError = null
        scope.launch {
            try {
                candidates = AppGraph.ai.findBottlings(clean)
            } catch (e: Exception) {
                searchError = e.message ?: "The search did not answer."
            } finally {
                searching = false
            }
        }
    }

    fun add(candidate: BottleCandidate) {
        if (adding != null) return
        adding = candidate.key
        scope.launch {
            val wine = candidate.toWine(AppGraph.settings.currency)
            // A bottle the cellar already holds is a second bottle, not a twin: the insight cache
            // already folds accents, case and punctuation, so it is the identity to match on.
            val existing = AppGraph.cellar.data.value.wines
                .firstOrNull { InsightStore.key(it) == InsightStore.key(wine) }
            val id = if (existing != null) {
                AppGraph.cellar.adjustQuantity(existing.id, 1)
                existing.id
            } else {
                AppGraph.cellar.upsert(wine)
                wine.id
            }
            // The page that named it usually carries a photograph of the wine, and that is the
            // picture the thumbnail on its page wants. Fetched after the add, so a slow shop page
            // cannot hold a bottle up, and written by its own call so the details lookup running
            // beside it cannot be written over.
            if (existing == null && candidate.sourceUrl.isNotBlank()) {
                AppGraph.scope.launch {
                    val picture = runCatching { AppGraph.ai.bottleImage(candidate.sourceUrl) }
                        .getOrNull()
                        // The page that named it can publish nothing usable; the pages that name
                        // the wine can still carry its label.
                        ?: runCatching { AppGraph.ai.bottleImageFor(wine) }.getOrNull()
                        ?: return@launch
                    val kept = AppGraph.cellar.wine(id) ?: return@launch
                    // A label scanned since, or a picture already kept, is not replaced by a shop's.
                    if (kept.photo.isNotBlank()) return@launch
                    val bytes = ImageUtils.jpegBytes(picture) ?: return@launch
                    val name = AppGraph.cellar.saveLabelPhoto(id, bytes)
                    if (name.isNotBlank()) AppGraph.cellar.attachPhoto(id, name)
                }
            }
            // The identity is the user's; the facts are the pages'. Read them for this one bottle,
            // after it is saved, so leaving the screen never loses it.
            val saved = AppGraph.cellar.data.value.wines.firstOrNull { it.id == id }
            if (saved != null && DetailsBackfill.missing(saved).isNotEmpty()) {
                AppGraph.scope.launch {
                    runCatching {
                        val found = AppGraph.ai.sourcedNotes(saved)
                        val abv = found.alcohol
                            ?: runCatching { AppGraph.ai.abvOf(saved) }.getOrNull()
                        AppGraph.cellar.applyDetails(id, abv, found)
                    }
                }
            }
            adding = null
            closeAdd()
            onOpenWine(id)
        }
    }

    val stats = remember(data) { data.stats() }
    val visible = remember(data, query, filter) {
        data.wines
            .filter { matches(it, query, filter, data) }
            .sortedWith(
                compareByDescending<Wine> { it.favorite }
                    .thenByDescending { it.quantity > 0 }
                    .thenByDescending { it.drinkStatus().ordinal }
                    .thenBy { it.producer.lowercase() }
                    .thenBy { it.vintage ?: 9999 }
            )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cellar", style = MaterialTheme.typography.headlineSmall) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { addOpen = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add a bottle")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                StatsCard(
                    bottles = stats.bottles,
                    wines = stats.distinctWines,
                    ready = stats.readyToDrink,
                    value = formatMoney(stats.invested, stats.currency)
                )
            }
            if (savedLists.isNotEmpty()) {
                item {
                    // The lists are a place of their own, not a filter: a list is an evening with
                    // its prices and its picks, which no cellar row can say.
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenLists),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                start = 14.dp,
                                end = 6.dp,
                                top = 8.dp,
                                bottom = 8.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Saved wine menus", style = MaterialTheme.typography.titleSmall)
                                Text(
                                                                            (if (savedLists.size == 1) "1 menu · " else "${savedLists.size} menus · ") +
                                        savedLists.first().title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
            if (drinks.isNotEmpty()) {
                item {
                    // The record of what has been drunk is a place, not only something to ask
                    // about: the answer should not need a model to be readable.
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenDrinks),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                start = 14.dp,
                                end = 6.dp,
                                top = 8.dp,
                                bottom = 8.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Drinks", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    (if (drinks.size == 1) "1 bottle · " else "${drinks.size} bottles · ") +
                                        dayWords(drinks.first().at) + ": " + drinks.first().title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search producer, grape, region, shelf…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
                )
            }
            item {
                FilterChipRow(
                    options = CellarFilter.entries.map { it.label },
                    selected = filter.label,
                    onSelect = { label -> filterLabel = label }
                )
            }
            if (visible.isEmpty()) {
                item {
                    EmptyState(
                        title = if (data.wines.isEmpty()) "Your cellar is empty" else "Nothing matches",
                        body = if (data.wines.isEmpty()) {
                            "Tap + and name the bottle, scan its label, or type it in by hand. " +
                                "Everything you record stays on this phone."
                        } else {
                            "No bottle in the cellar matches those words. To add one you do not " +
                                "own yet, tap + — everything you record stays on this phone."
                        }
                    )
                }
            } else {
                items(visible, key = { it.id }) { wine ->
                    WineRow(
                        wine = wine,
                        locationName = data.locationName(wine.locationId),
                        onClick = { onOpenWine(wine.id) },
                        // Out of the inventory on the tap, then the question worth asking. The
                        // count must not wait on a dialog nobody may finish.
                        onDrank = {
                            scope.launch { AppGraph.cellar.adjustQuantity(wine.id, -1) }
                            drankId = wine.id
                        }
                    )
                }
            }
        }
    }

    /**
     * The way back from a tap that was not meant.
     *
     * Offered once the sheet is closed, because a snackbar behind a dialog is a snackbar nobody
     * sees, and the sheet is exactly when a mistap is noticed. Only the bottle comes back: a verdict
     * or a note given in the same breath is the user's, and undoing the count must not throw it away.
     * The night goes with the bottle: an undo is the tap never having happened.
     */
    fun offerUndo(drinkId: String, wineId: String) {
        scope.launch {
            val answer = snackbarState.showSnackbar(
                message = "One bottle out of the cellar.",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long
            )
            if (answer == SnackbarResult.ActionPerformed) {
                AppGraph.drinks.remove(drinkId)
                AppGraph.cellar.adjustQuantity(wineId, 1)
            }
        }
    }

    /**
     * The night, written down as it happens.
     *
     * **Drank one** is the only moment the app learns a bottle left the shelf, and a count going
     * down is not a record: "what did I drink yesterday" has to be answerable months later, when the
     * number means nothing. The answer given in the sheet is part of the entry — a drink with no
     * verdict is still a drink.
     */
    fun recordDrank(wine: Wine, rating: Int?, note: String) {
        val drink = AppGraph.drinks.log(wine, rating, note)
        offerUndo(drink.id, wine.id)
    }

    val drank = drankId?.let { id -> data.wines.firstOrNull { it.id == id } }
    if (drank != null) {
        DrankDialog(
            wine = drank,
            onSave = { rating, note ->
                drankId = null
                recordDrank(drank, rating, note)
                scope.launch {
                    // Only when the word changed: an untouched sheet must not overwrite a verdict,
                    // and setting a null rating would clear a heart the bottle still earns.
                    if (rating != drank.rating) AppGraph.cellar.setRating(drank.id, rating)
                    if (note.isNotBlank()) AppGraph.cellar.addTastingNote(drank.id, note)
                }
            },
            onClose = {
                drankId = null
                recordDrank(drank, drank.rating, "")
            }
        )
    }

    if (addOpen) {
        AddBottleDialog(
            words = words,
            owned = owned,
            onWords = { words = it },
            asked = asked,
            searching = searching,
            candidates = candidates,
            error = searchError,
            adding = adding,
            onSearch = { lookUp(words) },
            onSearchAgain = { asked?.let { lookUp(it) } },
            onPick = { add(it) },
            onScan = {
                closeAdd()
                onScan()
            },
            onTypeIn = {
                closeAdd()
                onAddWine()
            },
            onClose = { if (adding == null) closeAdd() }
        )
    }
}

/**
 * The way a bottle gets in when it is not in the cellar yet.
 *
 * It starts as one question with a text field, because the bottle is in the user's hand and the
 * words on the label are what they know. The two fallbacks sit in the same dialog, so the choice
 * between naming it, scanning it and typing it in is made in one place.
 */
@Composable
private fun AddBottleDialog(
    words: String,
    owned: Map<String, Wine>,
    onWords: (String) -> Unit,
    asked: String?,
    searching: Boolean,
    candidates: List<BottleCandidate>,
    error: String?,
    adding: String?,
    onSearch: () -> Unit,
    onSearchAgain: () -> Unit,
    onPick: (BottleCandidate) -> Unit,
    onScan: () -> Unit,
    onTypeIn: () -> Unit,
    onClose: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (asked == null) "Add a bottle" else "Is this your bottle?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (asked == null) {
                    Text(
                        "Type what the label says — the year, the producer, the bottling. One " +
                            "search finds it and you pick the right one from the list.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = words,
                        onValueChange = onWords,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                        singleLine = true,
                        placeholder = { Text("2025 stoller rose") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() })
                    )
                    Button(
                        onClick = onSearch,
                        enabled = !searching && words.trim().length >= 3,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Search")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onScan) { Text("Scan the label") }
                        TextButton(onClick = onTypeIn) { Text("Type it in") }
                    }
                } else {
                    Text(
                        "Searched for “$asked”. Only the result titles and snippets were read; " +
                            "the pages are opened for the one you pick.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when {
                        searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Searching the web…", style = MaterialTheme.typography.bodySmall)
                        }
                        error != null -> Column {
                            Text(error)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = onSearchAgain) { Text("Search again") }
                                TextButton(onClick = onTypeIn) { Text("Type it in") }
                            }
                        }
                        candidates.isEmpty() -> Column {
                            Text(
                                "The results did not name that wine, so nothing was added " +
                                    "rather than inventing a bottle."
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = onSearchAgain) { Text("Search again") }
                                TextButton(onClick = onScan) { Text("Scan the label") }
                                TextButton(onClick = onTypeIn) { Text("Type it in") }
                            }
                        }
                        else -> candidates.forEach { candidate ->
                            CandidateCard(
                                candidate = candidate,
                                existing = owned[InsightStore.key(candidate.toWine())],
                                busy = adding == candidate.key,
                                enabled = adding == null,
                                onClick = { onPick(candidate) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onClose, enabled = adding == null) {
                Text(if (adding == null) "Close" else "Adding…")
            }
        }
    )
}

@Composable
private fun CandidateCard(
    candidate: BottleCandidate,
    existing: Wine?,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(candidate.title, style = MaterialTheme.typography.titleSmall)
            existing?.let { wine ->
                Text(
                    if (wine.quantity > 0) {
                        "Already in your cellar — ${wine.quantity} " +
                            (if (wine.quantity == 1) "bottle" else "bottles") +
                            ". Adding makes ${wine.quantity + 1}."
                    } else {
                        "In your cellar, none left. Adding brings it back."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val facts = listOf(
                candidate.type.label,
                candidate.region.ifBlank { candidate.country },
                candidate.grapes.joinToString(", ")
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (facts.isNotBlank()) {
                Text(facts, style = MaterialTheme.typography.bodySmall)
            }
            candidate.alcohol?.let {
                Text("$it% ABV", style = MaterialTheme.typography.bodySmall)
            }
            if (candidate.origin.isNotBlank()) {
                Text(
                    "named on ${candidate.origin}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (candidate.caveat.isNotBlank()) {
                Text(
                    candidate.caveat,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                when {
                    busy -> "Adding…"
                    existing != null -> "Tap to add one more bottle"
                    else -> "Tap to add this bottle"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/*
 * The cellar's label: the one solid claret object on the page, ruled in brass. A wine label is
 * printed on the bottle rather than on a card, and so is this, so the numbers read as a stamp on
 * the cellar instead of as another tile beside the bottles.
 */
@Composable
private fun StatsCard(bottles: Int, wines: Int, ready: Int, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)),
        colors = CardDefaults.cardColors(
            // At night the bright claret would become a pink slab, so the label goes to its deep
            // container colour and keeps the ivory serif it has in daylight.
            containerColor = if (isSystemInDarkTheme()) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.primary
            },
            contentColor = if (isSystemInDarkTheme()) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onPrimary
            }
        )
    ) {
        // Four capital labels do not fit across one row once the system font grows: at 1.3x
        // "READY NOW" ran into "PAID" and the labels ran together. Past that point the plaque is a
        // small ledger instead, one stat per line, so the text always has the whole width.
        if (LocalDensity.current.fontScale > 1.15f) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatRow("$bottles", "BOTTLES")
                StatRow("$wines", "WINES")
                StatRow("$ready", "READY NOW")
                StatRow(value, "PAID")
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatCell("$bottles", "BOTTLES")
                StatCell("$wines", "WINES")
                StatCell("$ready", "READY NOW")
                StatCell(value, "PAID")
            }
        }
    }
}

@Composable
private fun StatCell(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
            color = LocalContentColor.current.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/*
 * The large-text shape of a stat: the label keeps the left edge, the number keeps the right one, so
 * the line is as wide as the plaque and nothing has to collide or shrink.
 */
@Composable
private fun StatRow(value: String, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
            color = LocalContentColor.current.copy(alpha = 0.72f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.End,
            maxLines = 1
        )
    }
}

private fun matches(wine: Wine, query: String, filter: CellarFilter, data: CellarData): Boolean {
    val filterMatch = when (filter) {
        CellarFilter.ALL -> true
        CellarFilter.RED -> wine.type.label == "Red"
        CellarFilter.WHITE -> wine.type.label == "White"
        CellarFilter.SPARKLING -> wine.type.label == "Sparkling"
        CellarFilter.READY -> wine.quantity > 0 &&
            wine.drinkStatus() in listOf(
                com.wineverdict.app.model.DrinkStatus.READY,
                com.wineverdict.app.model.DrinkStatus.PEAK
            )
        CellarFilter.LOVED -> Verdict.liked(wine)
        CellarFilter.EMPTY -> wine.quantity <= 0
    }
    if (!filterMatch) return false
    if (query.isBlank()) return true
    val haystack = listOf(
        wine.producer,
        wine.name,
        wine.region,
        wine.country,
        wine.appellation,
        wine.grapes.joinToString(" "),
        wine.slot,
        data.locationName(wine.locationId),
        wine.flavorTags.joinToString(" "),
        wine.myNotes
    ).joinToString(" ").lowercase()
    return query.trim().lowercase().split(' ').all { haystack.contains(it) }
}
