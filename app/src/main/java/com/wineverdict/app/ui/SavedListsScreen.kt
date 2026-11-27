package com.wineverdict.app.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wineverdict.app.AppGraph
import com.wineverdict.app.data.InsightStore
import com.wineverdict.app.data.SavedList
import com.wineverdict.app.data.pickedWines
import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.formatMoney
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun listDate(at: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(at))

/**
 * The lists that have been read, newest first.
 *
 * A wine list is evidence about one evening: what the page offered, what the answer picked, and
 * what it cost. Keeping it is what makes the scan worth taking after the meal is over — and it is
 * where the one-hour reminder lands, so the list has to be a place, not a notification that opens
 * the cellar and leaves the user to find their own way back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedListsScreen(onBack: () -> Unit, onOpenList: (String) -> Unit) {
    val lists by AppGraph.lists.lists.collectAsStateWithLifecycle()
    // The cellar, for the one pick signal that survives a menu kept before picks were recorded:
    // a wine that exists there under the menu's own id was ordered or kept off that page.
    val cellar by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val cellarIds = remember(cellar) { cellar.wines.map { it.id }.toSet() }
    var renaming by remember { mutableStateOf<SavedList?>(null) }
    var deleting by remember { mutableStateOf<SavedList?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved wine menus", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (lists.isEmpty()) {
            EmptyState(
                title = "No menus yet",
                body = "Photograph a restaurant's wine list and it is kept here with its wines, the " +
                    "day it was read, the page itself and what you picked off it. Name it after the " +
                    "restaurant and it reads as that evening rather than as a date.",
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(lists, key = { it.id }) { list ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenList(list.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // The page itself, as a thumbnail: a photograph is the fastest way to
                            // tell two evenings at the same restaurant apart.
                            AppGraph.lists.photoFile(list)?.let { page ->
                                UriImage(
                                    uri = Uri.fromFile(page),
                                    modifier = Modifier
                                        .padding(end = 10.dp)
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(list.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${list.wines.size} " +
                                        (if (list.wines.size == 1) "wine" else "wines") +
                                        " · " + listDate(list.savedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                        // What the user had, not what the answer suggested: three picks the model
                        // would order is not three bottles on the table.
                        val picked = pickedWines(list, cellarIds)
                        if (picked.isNotEmpty()) {
                            Text(
                                "${picked.size} picked that night",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { renaming = list }) { Text("Name it") }
                            IconButton(onClick = { deleting = list }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete this list",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { list ->
        var name by remember(list.id) { mutableStateOf(list.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name this list") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The restaurant, the table, the occasion — whatever makes it findable later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Osteria, Friday") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppGraph.lists.rename(list.id, name)
                        renaming = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            }
        )
    }

    deleting?.let { list ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this list?") },
            text = {
                Text(
                    "“${list.title}”, its wines and the photograph go. The wines you kept from it " +
                        "stay in your cellar with their verdicts."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppGraph.lists.delete(list.id)
                        deleting = null
                    }
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }
        )
    }
}

/**
 * One list, reopened.
 *
 * The wines are the page as it was read. A verdict tapped here belongs to the wine, so it is
 * written where that wine lives: on the cellar record when there is one, and on the list's own
 * copy when there is not — one answer per wine, never two that disagree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedListScreen(listId: String?, onBack: () -> Unit) {
    val lists by AppGraph.lists.lists.collectAsStateWithLifecycle()
    val cellar by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val list = lists.firstOrNull { it.id == listId }
    var ratingWine by remember { mutableStateOf<Wine?>(null) }
    var showingPage by remember { mutableStateOf(false) }
    // The photograph, when this build kept one for the list.
    val page = AppGraph.lists.photoFile(list)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        list?.title ?: "List",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (list == null) {
            EmptyState(
                title = "This list is gone",
                body = "It was deleted, or the backup it came from no longer has it.",
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }
        val picked = pickedWines(list, cellar.wines.map { it.id }.toSet())
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        listDate(list.savedAt) + " · ${list.wines.size} " +
                            (if (list.wines.size == 1) "wine" else "wines"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (list.name.isBlank()) {
                        Text(
                            "Name it from the saved menus to say where it was.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
            if (page != null) {
                item {
                    // First, because it is the reference: the wines below are what was read out of
                    // this page, and a line the reading missed is on the photograph.
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showingPage = true },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column {
                            UriImage(
                                uri = Uri.fromFile(page),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                            )
                            Text(
                                "The page as you photographed it · tap to read it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
            if (list.note.isNotBlank()) {
                item {
                    SectionCard(null) {
                        Text(list.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(list.wines, key = { it.id }) { wine ->
                val kept = cellar.wines.firstOrNull {
                    InsightStore.key(it) == InsightStore.key(wine)
                }
                val verdict = Verdict.labelFor(kept ?: wine)
                val rank = list.picks.indexOf(wine.id)
                val mine = wine.id in picked
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { ratingWine = wine },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                wine.title,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            // Two different claims, so two different badges: what you had, and
                            // where the answer put it.
                            if (mine) {
                                Pill("Picked", color = MaterialTheme.colorScheme.primaryContainer)
                            }
                            if (rank >= 0) Pill("#${rank + 1}")
                        }
                        if (wine.subtitle.isNotBlank()) {
                            Text(
                                wine.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            wine.price?.let { Pill(formatMoney(it, wine.currency)) }
                            verdict?.let { Pill(it) }
                            if (kept != null) {
                                Pill(if (kept.quantity > 0) "In your cellar" else "Kept, no bottles")
                            }
                        }
                        Text(
                            if (verdict == null) "Tap to say what it was like" else "Tap to change it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }

    if (showingPage && page != null) {
        // The whole page, full screen and zoomable: the point of keeping it is reading print the
        // reading did not capture, and a menu at fit-to-screen is unreadable by design.
        ZoomDialog(uri = Uri.fromFile(page), onClose = { showingPage = false })
    }

    ratingWine?.let { wine ->
        val kept = cellar.wines.firstOrNull { InsightStore.key(it) == InsightStore.key(wine) }
        var rating by remember(wine.id) { mutableStateOf((kept ?: wine).rating) }
        var note by remember(wine.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { ratingWine = null },
            title = { Text("How was it?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(wine.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (kept != null) {
                                "This wine is on record, so the verdict is kept on it."
                            } else {
                                "Kept with this list and no bottles — the verdict stays with the list."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    VerdictChips(rating) { rating = it }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("One line about it") }
                    )
                    if (kept == null) {
                        TextButton(
                            onClick = {
                                ratingWine = null
                                scope.launch { AppGraph.cellar.saveWine(wine) }
                            }
                        ) { Text("Keep this one in my wines") }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ratingWine = null
                        // An answer about a wine is the strongest sign there is that it was poured:
                        // it is the pick, whether the verdict is kept on the cellar record or on
                        // the menu's own copy.
                        list?.let { open -> AppGraph.lists.choose(open.id, wine.id) }
                        scope.launch {
                            if (kept != null) {
                                if (rating != kept.rating) {
                                    AppGraph.cellar.setRating(kept.id, rating)
                                }
                                if (note.isNotBlank()) AppGraph.cellar.addTastingNote(kept.id, note)
                            } else {
                                // The dialog only opens from inside a list, but the null check
                                // above belongs to the Scaffold's scope, not this one.
                                list?.let { open ->
                                    AppGraph.lists.rate(open.id, wine.id, rating, note)
                                }
                            }
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { ratingWine = null }) { Text("Cancel") }
            }
        )
    }
}
