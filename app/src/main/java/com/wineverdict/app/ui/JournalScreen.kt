package com.wineverdict.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wineverdict.app.AppGraph
import com.wineverdict.app.data.Drink
import com.wineverdict.app.data.dayWords
import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val DAY_MS = 24L * 60 * 60 * 1000

/** The clock time of a drink, for the row's last line. */
private fun timeOf(at: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(at)
        .atZone(zone)
        .toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))

/**
 * What has actually been drunk, as a list, and the way to write a night into it by hand.
 *
 * The same record Ask answers from, readable without asking a model about your own evening: grouped
 * by the night, newest first, each bottle with the verdict given then — or the plain fact that the
 * count was corrected instead, because a bottle taken out with the minus button left the shelf too.
 *
 * It is a place rather than a filter on the cellar, for the same reason a saved list is: a bottle
 * finished months ago is still "in the cellar" as a record with nothing left, and the history of
 * what was opened is a different question from what is on the shelf. **Add** is for the nights the
 * app could not see — a restaurant bottle, or one from before it was installed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen() {
    val drinks by AppGraph.drinks.drinks.collectAsStateWithLifecycle()
    val data by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(drinks) { System.currentTimeMillis() }
    var adding by remember { mutableStateOf(false) }

    /**
     * A night written by hand becomes two things: the entry, and the bottle behind it.
     *
     * A wine already on the shelf is used as it stands. A wine the user does not own becomes a
     * record with no bottles — the same "on record" idea the saved lists use — so the verdict and
     * the taste words have somewhere to live and the taste brief, which reads bottles rather than
     * the drink log, can use them.
     */
    fun record(entry: JournalEntry) {
        scope.launch {
            val picked = entry.picked
            val wine = picked ?: Wine(
                producer = entry.producer.trim(),
                name = entry.name.trim(),
                vintage = entry.vintage,
                type = entry.type,
                quantity = 0,
                source = "journal"
            ).also { AppGraph.cellar.upsert(it) }
            AppGraph.drinks.add(
                wineId = wine.id,
                title = wine.title,
                type = wine.type.label,
                region = wine.region.ifBlank { wine.country },
                price = wine.price,
                currency = wine.currency,
                rating = entry.rating,
                note = entry.note,
                flavors = entry.flavors,
                again = entry.again,
                at = entry.at
            )
            AppGraph.cellar.applyJournalTaste(
                id = wine.id,
                rating = entry.rating,
                flavors = entry.flavors,
                note = entry.note,
                day = Instant.ofEpochMilli(entry.at).atZone(zone).toLocalDate()
            )
            snackbarState.showSnackbar(
                if (picked == null) "Written in the cellar book, with a record for the wine."
                else "Written in the cellar book."
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Cellar book", style = MaterialTheme.typography.headlineSmall) })
        },
        snackbarHost = { SnackbarHost(snackbarState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add a bottle")
            }
        }
    ) { padding ->
        if (drinks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                EmptyState(
                    title = "Nothing drunk yet",
                    body = "Every bottle that leaves the cellar is written here — Drank one, and the " +
                        "minus button too — with the day and the verdict you gave it. Add a night " +
                        "the app could not see: a restaurant bottle, or one from before. Ask answers " +
                        "what you drank from this same record."
                )
            }
            return@Scaffold
        }

        val week = drinks.count { it.at >= now - 7 * DAY_MS }
        val month = drinks.count { it.at >= now - 30 * DAY_MS }
        // Already newest first, and groupBy keeps that order, so the nights come out in the order
        // they happened without a second sort.
        val nights: Map<LocalDate, List<Drink>> = drinks.groupBy { drink ->
            Instant.ofEpochMilli(drink.at).atZone(zone).toLocalDate()
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SectionCard("On record") {
                    Text(
                        text = "$week in the last 7 days · $month in the last 30 · " +
                            "${drinks.size} altogether",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            nights.forEach { (day, list) ->
                item(key = "night-$day") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            dayWords(list.first().at, now, zone),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            " · " + (if (list.size == 1) "1 bottle" else "${list.size} bottles"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(list, key = { it.id }) { drink -> DrinkRow(drink, zone) }
            }
        }
    }

    if (adding) {
        JournalEntryDialog(
            wines = data.wines,
            onDismiss = { adding = false },
            onSave = { entry ->
                adding = false
                record(entry)
            }
        )
    }
}

/** One bottle, as the night it was opened. */
@Composable
private fun DrinkRow(drink: Drink, zone: ZoneId) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    drink.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                // The word given at the time, or the honest marker for a corrected count: a verdict
                // was never asked for on the minus button and must not appear to have been.
                val word = Verdict.label(drink.rating)
                Pill(
                    when {
                        drink.via == Drink.COUNT -> "count adjusted"
                        word != null -> word
                        else -> "no verdict"
                    }
                )
            }
            val where = listOf(drink.type, drink.region)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (where.isNotBlank()) {
                Text(
                    where,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (drink.note.isNotBlank()) {
                Text("“${drink.note}”", style = MaterialTheme.typography.bodySmall)
            }
            val survey = buildList {
                when (drink.again) {
                    true -> add("would buy again")
                    false -> add("not again")
                    null -> Unit
                }
                if (drink.flavors.isNotEmpty()) add(drink.flavors.joinToString(", "))
            }.joinToString(" · ")
            if (survey.isNotBlank()) {
                Text(
                    survey,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // A night written in later has no clock time that anybody observed, so none is printed:
            // the day above the card is the whole of what is known.
            if (drink.via != Drink.MANUAL) {
                Text(
                    timeOf(drink.at, zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** Everything the Add sheet collects, before it becomes a drink and a bottle write. */
private data class JournalEntry(
    val picked: Wine?,
    val name: String,
    val producer: String,
    val vintage: Int?,
    val type: WineType,
    val rating: Int?,
    val flavors: List<String>,
    val again: Boolean?,
    val note: String,
    val at: Long
)

/**
 * The words the survey offers. They are the shape the taste brief already reads — a handful of
 * ordinary descriptors, not a vocabulary test — and six is the most the brief would print anyway.
 */
private val SURVEY_FLAVORS = listOf(
    "Cherry", "Plum", "Blackcurrant", "Strawberry", "Citrus", "Apple",
    "Pear", "Peach", "Minerality", "Oak", "Vanilla", "Spice",
    "Earth", "Leather", "Tobacco", "Floral", "Herbal", "Jammy",
    "Savoury", "Honey"
)

/**
 * A night the app did not see.
 *
 * The bottle is the first question, because it decides whether anything else needs naming: a wine
 * already on the shelf is picked from a short match list, and one that is not becomes a record with
 * no bottles. The night is editable because the entry is often made days later, and the survey is
 * three taps rather than another interview — the verdict, the words, and whether it was worth
 * buying again.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun JournalEntryDialog(
    wines: List<Wine>,
    onDismiss: () -> Unit,
    onSave: (JournalEntry) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Wine?>(null) }
    var producer by remember { mutableStateOf("") }
    var vintage by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(WineType.RED) }
    var night by remember { mutableStateOf(System.currentTimeMillis()) }
    var rating by remember { mutableStateOf<Int?>(null) }
    var flavors by remember { mutableStateOf<List<String>>(emptyList()) }
    var again by remember { mutableStateOf<Boolean?>(null) }
    var note by remember { mutableStateOf("") }
    var pickingDate by remember { mutableStateOf(false) }

    val matches = remember(name, wines, picked) {
        val words = name.trim()
        if (picked != null || words.length < 2) {
            emptyList()
        } else {
            wines.filter { it.title.contains(words, ignoreCase = true) }
                .sortedBy { it.title }
                .take(4)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize().imePadding(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Add to the cellar book",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (picked == null) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Wine") },
                            placeholder = { Text("Barolo, Chablis, Rioja…") }
                        )
                        matches.forEach { wine ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        picked = wine
                                        name = wine.title
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(wine.title, style = MaterialTheme.typography.bodyMedium)
                                    if (wine.subtitle.isNotBlank()) {
                                        Text(
                                            wine.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    if (wine.quantity > 0) "${wine.quantity} in the cellar" else "on record",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        Text(
                            if (matches.isEmpty()) {
                                "Not in your cellar? Fill in what you know — it becomes a record " +
                                    "with no bottles, so the verdict has somewhere to live."
                            } else {
                                "Tap a bottle above to write against the record you already have, " +
                                    "or fill in what you know."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        OutlinedTextField(
                            value = producer,
                            onValueChange = { producer = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Producer (optional)") }
                        )
                        OutlinedTextField(
                            value = vintage,
                            onValueChange = { vintage = it.filter(Char::isDigit).take(4) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Vintage (optional)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Text("Colour", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            WineType.entries.forEach { colour ->
                                FilterChip(
                                    selected = type == colour,
                                    onClick = { type = colour },
                                    label = { Text(colour.label) }
                                )
                            }
                        }
                    } else {
                        picked?.let { wine ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(wine.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        if (wine.quantity > 0) "${wine.quantity} in the cellar"
                                        else "on record, no bottles",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            TextButton(onClick = { picked = null }) { Text("Choose another bottle") }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("The night", style = MaterialTheme.typography.labelLarge)
                            Text(
                                dayWords(night),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { pickingDate = true }) { Text("Change") }
                    }

                    Text("How was it?", style = MaterialTheme.typography.labelLarge)
                    VerdictChips(rating) { rating = it }

                    Text(
                        if (flavors.isEmpty()) "What did it taste of? (up to six)"
                        else "What did it taste of? (${flavors.size} of 6)",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SURVEY_FLAVORS.forEach { word ->
                            val chosen = word in flavors
                            FilterChip(
                                selected = chosen,
                                // Six words is what the brief prints; a seventh would never leave
                                // the phone, so the chip says so rather than silently dropping it.
                                enabled = chosen || flavors.size < 6,
                                onClick = {
                                    flavors = if (chosen) flavors - word else flavors + word
                                },
                                label = { Text(word) }
                            )
                        }
                    }

                    Text("Would you buy it again?", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = again == true,
                            onClick = { again = if (again == true) null else true },
                            label = { Text("Yes") }
                        )
                        FilterChip(
                            selected = again == false,
                            onClick = { again = if (again == false) null else false },
                            label = { Text("No") }
                        )
                    }

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("One line about it (optional)") },
                        placeholder = { Text("Strawberry, cold, worth it") },
                        maxLines = 3
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onSave(
                                JournalEntry(
                                    picked = picked,
                                    name = if (picked != null) picked?.name.orEmpty() else name,
                                    producer = producer,
                                    vintage = vintage.toIntOrNull()?.takeIf { it in 1900..2100 },
                                    type = type,
                                    rating = rating,
                                    flavors = flavors,
                                    again = again,
                                    note = note,
                                    at = night
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = picked != null || name.isNotBlank()
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = night)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { chosen ->
                            val zone = ZoneId.systemDefault()
                            // The picker works in UTC days; the record is a local night.
                            val day = Instant.ofEpochMilli(chosen)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            night = if (day == LocalDate.now(zone)) {
                                System.currentTimeMillis()
                            } else {
                                day.atStartOfDay(zone).toInstant().toEpochMilli()
                            }
                        }
                        pickingDate = false
                    }
                ) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}
