package com.wineverdict.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wineverdict.app.AppGraph
import com.wineverdict.app.ai.BottleCandidate
import com.wineverdict.app.data.DetailsBackfill
import com.wineverdict.app.data.InsightStore
import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.drinkStatus
import com.wineverdict.app.model.formatMoney
import com.wineverdict.app.model.locationName
import com.wineverdict.app.model.stats
import kotlinx.coroutines.launch

private enum class CellarFilter(val label: String) {
    ALL("All"),
    RED("Red"),
    WHITE("White"),
    SPARKLING("Sparkling"),
    READY("Ready now"),
    LOVED("Loved")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellarScreen(onOpenWine: (String) -> Unit, onAddWine: () -> Unit) {
    val data by AppGraph.cellar.data.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(CellarFilter.ALL) }

    // The search box is also how a bottle that is not in the cellar yet gets in: the words typed
    // here are the query, the candidates come from the results, and the pages are read only for
    // the one chosen.
    val scope = rememberCoroutineScope()
    var asked by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<BottleCandidate>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }

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
            asked = null
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
        floatingActionButton = {
            FloatingActionButton(onClick = onAddWine) {
                Icon(Icons.Filled.Add, contentDescription = "Add a wine")
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
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search producer, grape, region, shelf…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    // The same words that filter the cellar are the query for a bottle it does not
                    // hold yet, so the keyboard's search key is the lookup.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { lookUp(query) })
                )
            }
            if (query.trim().length >= 3) {
                item {
                    OutlinedButton(
                        onClick = { lookUp(query) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Find “${query.trim()}” on the web",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            item {
                FilterChipRow(
                    options = CellarFilter.entries.map { it.label },
                    selected = filter.label,
                    onSelect = { label ->
                        filter = CellarFilter.entries.firstOrNull { it.label == label } ?: CellarFilter.ALL
                    }
                )
            }
            if (visible.isEmpty()) {
                item {
                    EmptyState(
                        title = if (data.wines.isEmpty()) "Your cellar is empty" else "Nothing matches",
                        body = if (data.wines.isEmpty()) {
                            "Scan a label or add a bottle by hand. Everything you record stays on this phone."
                        } else {
                            "Nothing in the cellar matches. Look the bottle up on the web with the " +
                                "button above, or add it by hand — everything you record stays on this phone."
                        }
                    )
                }
            } else {
                items(visible, key = { it.id }) { wine ->
                    WineRow(
                        wine = wine,
                        locationName = data.locationName(wine.locationId),
                        onClick = { onOpenWine(wine.id) }
                    )
                }
            }
        }
    }

    asked?.let { words ->
        AlertDialog(
            onDismissRequest = { if (adding == null) asked = null },
            title = { Text("Is this your bottle?") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Searched for “$words”. Only the result titles and snippets were read; the " +
                            "pages are opened for the one you pick.",
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
                        searchError != null -> Text(searchError!!)
                        candidates.isEmpty() -> Column {
                            Text(
                                "The results did not name that wine, so nothing was added rather " +
                                    "than inventing a bottle."
                            )
                            TextButton(onClick = {
                                asked = null
                                onAddWine()
                            }) { Text("Type it in instead") }
                        }
                        else -> candidates.forEach { candidate ->
                            CandidateCard(
                                candidate = candidate,
                                busy = adding == candidate.key,
                                enabled = adding == null,
                                onClick = { add(candidate) }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { asked = null }, enabled = adding == null) { Text("Close") }
            }
        )
    }
}

@Composable
private fun CandidateCard(
    candidate: BottleCandidate,
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
                if (busy) "Adding…" else "Tap to add this bottle",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun StatsCard(bottles: Int, wines: Int, ready: Int, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatCell("$bottles", "bottles")
            StatCell("$wines", "wines")
            StatCell("$ready", "drink now")
            StatCell(value, "paid")
        }
    }
}

@Composable
private fun StatCell(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
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
        CellarFilter.LOVED -> wine.favorite || (wine.rating ?: 0) >= 4
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