package com.vinoteca.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinoteca.app.AppGraph
import com.vinoteca.app.model.CellarData
import com.vinoteca.app.model.Wine
import com.vinoteca.app.model.drinkStatus
import com.vinoteca.app.model.formatMoney
import com.vinoteca.app.model.locationName
import com.vinoteca.app.model.stats

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
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
                )
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
                            "Try a different search, or clear the filter."
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
                com.vinoteca.app.model.DrinkStatus.READY,
                com.vinoteca.app.model.DrinkStatus.PEAK
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