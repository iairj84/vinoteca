package com.wineverdict.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wineverdict.app.AppGraph
import com.wineverdict.app.ai.InsightParsing
import com.wineverdict.app.data.GateScope
import com.wineverdict.app.data.GateVerdict
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.drinkStatus
import com.wineverdict.app.model.formatMoney
import com.wineverdict.app.model.locationName
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One decimal place, without the locale comma that would turn 14.1 into "14,1" mid-sentence. */
private fun oneDecimal(value: Double): String =
    String.format(java.util.Locale.US, "%.1f", value)

/** Domains only: a grounding URL is long, and the host is what tells you who wrote the note. */
private fun sourceHosts(sources: String): String =
    sources.split(' ', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { url -> runCatching { java.net.URI(url).host }.getOrNull() ?: url }
        .distinct()
        .joinToString(" \u00b7 ")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WineDetailScreen(wineId: String?, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val data by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val wine = data.wines.firstOrNull { it.id == wineId }
    val scope = rememberCoroutineScope()
    // Derived match lines follow the palate answers: they say something about the rules as they are,
    // not about the day the bottle was added.
    val profile by AppGraph.palate.profile.collectAsStateWithLifecycle()
    val rules = remember(profile) { AppGraph.palate.rules }
    var showNoteDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var looking by remember { mutableStateOf(false) }
    var lookupMessage by remember { mutableStateOf<String?>(null) }
    var lookupError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bottle", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (wine != null) {
                        IconButton(onClick = { scope.launch { AppGraph.cellar.toggleFavorite(wine.id) } }) {
                            Icon(
                                imageVector = if (wine.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "Favourite",
                                tint = if (wine.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { onEdit(wine.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (wine == null) {
            EmptyState(
                title = "Not in the cellar",
                body = "This bottle is no longer in your collection.",
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(wine.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = wine.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Wraps rather than pushing the last badge off the edge, which is what happened to
            // the rating at double text.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DrinkBadge(wine.drinkStatus())
                Pill(if (wine.quantity > 0) "${wine.quantity} in cellar" else "none left")
                wine.alcohol?.let { Pill("${oneDecimal(it)}% vol") }
                when {
                    (wine.rating ?: 0) >= 4 -> Pill("${wine.rating}★")
                    wine.rating != null -> Pill("${wine.rating}★")
                    wine.favorite -> Pill("loved")
                }
            }

            RatingStars(wine.rating) { value ->
                scope.launch { AppGraph.cellar.setRating(wine.id, value) }
            }
            Text(
                text = "Tap a star to rate what you thought of it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )

            // No score on a bottle you already own: that decision was made at the till. What the
            // page can honestly say is which of the user's own thresholds this bottle passes,
            // breaks, or cannot answer — and an unknown gate is shown as unknown.
            SectionCard("Your rules") {
                val checks = remember(wine, rules) {
                    // A bottle on this screen is already in the cellar, so the gates read it the
                    // way a glass does: over the ceiling is a note about buying, not a refusal.
                    if (rules.isEmpty) emptyList() else rules.verdicts(wine, scope = GateScope.OWNED)
                }
                if (checks.isEmpty()) {
                    Text(
                        "No sweetness, oak or alcohol limit set. Answer those three in Taste and every bottle gets checked against them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    checks.forEach { result ->
                        Text(
                            text = when (result.verdict) {
                                GateVerdict.FAIL -> "\u2715  ${result.detail}"
                                GateVerdict.PASS -> "\u2713  ${result.detail}"
                                GateVerdict.CONDITIONAL -> "~  ${result.detail}"
                                GateVerdict.UNKNOWN -> "?  ${result.detail}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (result.verdict) {
                                GateVerdict.FAIL -> MaterialTheme.colorScheme.error
                                GateVerdict.PASS, GateVerdict.CONDITIONAL -> MaterialTheme.colorScheme.onSurface
                                GateVerdict.UNKNOWN -> MaterialTheme.colorScheme.outline
                            }
                        )
                    }
                    if (checks.none { it.verdict == GateVerdict.FAIL }) {
                        Text(
                            "None of your rules is broken by this bottle. That is not the same as a good match.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SectionCard("Where it lives") {
                LabeledValue("Location", data.locationName(wine.locationId))
                if (wine.slot.isNotBlank()) LabeledValue("Slot / shelf", wine.slot)
                if (wine.barcode.isNotBlank()) LabeledValue("Barcode", wine.barcode)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bottles", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    FilledTonalIconButton(
                        onClick = { scope.launch { AppGraph.cellar.adjustQuantity(wine.id, -1) } }
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = "One fewer")
                    }
                    Text(
                        text = "${wine.quantity}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                    FilledTonalIconButton(
                        onClick = { scope.launch { AppGraph.cellar.adjustQuantity(wine.id, 1) } }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "One more")
                    }
                }
            }

            SectionCard("Drinking window") {
                LabeledValue("Window", wine.drinkWindowLabel)
                LabeledValue("Status", wine.drinkStatus().label)
                LabeledValue("Paid", formatMoney(wine.price, wine.currency))
                if (wine.vintage != null) LabeledValue("Vintage", wine.vintage.toString())
                val stamp = remember(wine.addedAt) {
                    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(wine.addedAt))
                }
                LabeledValue("Added", stamp)
            }

            // Read from the record, so a re-scan of the same wine shows it without a request: the
            // store stamps the answer onto the wine the first time it is asked for.
            val insight = remember(wine.insightJson) { InsightParsing.parse(wine.insightJson) }
            if (insight == null) {
                InsightAsk(wine)
            } else {
                SectionCard("Why it tastes this way") {
                    if (insight.headline.isNotBlank()) {
                        Text(insight.headline, style = MaterialTheme.typography.bodyMedium)
                    }
                    insight.visibleClaims().forEach { claim ->
                        Text(
                            text = "\u00b7  $claim",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    insight.taste?.let { taste ->
                        if (taste.summary.isNotBlank()) LabeledValue("Body", taste.summary)
                        if (taste.notes.isNotEmpty()) {
                            LabeledValue("Flavours", taste.notes.joinToString(", "))
                        }
                    }
                    insight.window?.let { window ->
                        LabeledValue(
                            "Drink",
                            listOfNotNull(
                                window.label.takeIf { it.isNotBlank() },
                                window.confidence.takeIf { it.isNotBlank() }?.let { "confidence: $it" }
                            ).joinToString(" \u00b7 ")
                        )
                    }
                    if (insight.pairings.isNotEmpty()) {
                        LabeledValue("With food", insight.pairings.joinToString(" \u00b7 "))
                    }
                    insight.forYou?.let {
                        Text("For you: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    val written = remember(wine.insightDate, wine.insightModel) {
                        val day = if (wine.insightDate > 0L) {
                            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(wine.insightDate))
                        } else {
                            ""
                        }
                        listOf("Written by ${wine.insightModel.ifBlank { "the model" }}", day)
                            .filter { it.isNotBlank() }
                            .joinToString(" \u00b7 ")
                    }
                    Text(
                        written,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AiDisclaimer()
                }
            }

            SectionCard("Reference notes") {
                if (wine.expertNotes.isBlank()) {
                    Text(
                        text = if (wine.notesCheckedAt > 0L) {
                            "Checked, and no published notes were found for this bottling. Nothing here is guessed."
                        } else {
                            "Nothing checked yet. A scan's notes are general knowledge; a lookup reads published notes and keeps the pages it used."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(wine.expertNotes, style = MaterialTheme.typography.bodyMedium)
                    if (wine.notesSource.isNotBlank()) {
                        Text(
                            text = "From the web \u00b7 ${sourceHosts(wine.notesSource)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        AiDisclaimer()
                    }
                }
                OutlinedButton(
                    enabled = !looking,
                    onClick = {
                        looking = true
                        lookupError = null
                        lookupMessage = null
                        scope.launch {
                            try {
                                val found = AppGraph.ai.sourcedNotes(wine)
                                if (found.notes.isBlank()) {
                                    lookupMessage = found.caveat
                                        .ifBlank { "No published notes found for this bottle." }
                                } else {
                                    AppGraph.cellar.applySourcedNotes(
                                        id = wine.id,
                                        notes = found.notes,
                                        tags = found.tags,
                                        sources = found.sources.joinToString(" "),
                                        alcohol = found.alcohol
                                    )
                                    lookupMessage = buildString {
                                        append(
                                            if (found.sources.isEmpty()) {
                                                "Saved, but no page was cited."
                                            } else {
                                                "Saved from ${found.sources.size} page" +
                                                    (if (found.sources.size == 1) "" else "s") + "."
                                            }
                                        )
                                        found.alcohol?.let { abv ->
                                            if (wine.alcohol == null) {
                                                append(" ABV ${oneDecimal(abv)}% recorded.")
                                            }
                                        }
                                        if (found.caveat.isNotBlank()) append(" ").append(found.caveat)
                                    }
                                }
                            } catch (e: Exception) {
                                lookupError = e.message ?: "The lookup failed."
                            } finally {
                                looking = false
                            }
                        }
                    }
                ) {
                    if (looking) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp))
                        Text("  Searching the web\u2026")
                    } else {
                        Text(if (wine.notesCheckedAt == 0L) "Look up tasting notes" else "Look up again")
                    }
                }
                lookupMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                lookupError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            SectionCard("My tasting notes") {
                if (wine.myNotes.isBlank()) {
                    Text(
                        text = "Nothing yet. Add the first note after you open it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(wine.myNotes, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = { showNoteDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  Add a note")
                }
            }

            if (wine.flavorTags.isNotEmpty()) {
                SectionCard("Flavour tags") {
                    Text(wine.flavorTags.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (showNoteDialog && wine != null) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("Tasting note") },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("How was it?") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (note.isNotBlank()) {
                            scope.launch { AppGraph.cellar.addTastingNote(wine.id, note) }
                        }
                        showNoteDialog = false
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmDelete && wine != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove this wine?") },
            text = { Text("“${wine.title}” and its notes will be deleted from your cellar.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { AppGraph.cellar.delete(wine.id) }
                        confirmDelete = false
                        onBack()
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            }
        )
    }
}

    /**
     * The bottle page's way back in when there is no explanation.
     *
     * The ask used to happen only at the confirmation, so a 503 on that one request left a bottle with no
     * section and no way to try again — "nothing here" and "the model was busy" looked identical. The
     * button asks on demand, says what went wrong when it does, and a second tap is the retry.
     */
    @Composable
    private fun InsightAsk(wine: Wine) {
        var asking by remember(wine.id) { mutableStateOf(false) }
        var failed by remember(wine.id) { mutableStateOf(false) }
        var message by remember(wine.id) { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        SectionCard("Why it tastes this way") {
            Text(
                text = message ?: "Not asked yet. One request on the advice model says where the flavours " +
                    "come from — the region, the grape, the year, the producer.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (message == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            OutlinedButton(
                enabled = !asking,
                onClick = {
                    asking = true
                    message = null
                    scope.launch {
                        val outcome = runCatching { AppGraph.insights.explain(wine) }
                        asking = false
                        when {
                            outcome.isFailure -> {
                                failed = true
                                message = outcome.exceptionOrNull()?.message ?: "The request failed."
                            }
                            outcome.getOrNull() == null -> {
                                failed = true
                                message = "The answer came back empty. Try again."
                            }
                            // Otherwise the record now carries the insight and this card is replaced by it.
                            else -> message = null
                        }
                    }
                }
            ) {
                if (asking) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    Text("  Asking…")
                } else {
                    Text(if (failed) "Try again" else "Explain this bottle")
                }
            }
        }
    }
