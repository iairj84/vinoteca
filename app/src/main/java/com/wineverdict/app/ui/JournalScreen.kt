package com.wineverdict.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wineverdict.app.AppGraph
import com.wineverdict.app.data.Drink
import com.wineverdict.app.data.bottleTastings
import com.wineverdict.app.data.Search
import com.wineverdict.app.data.matchesWords
import com.wineverdict.app.data.dayWords
import com.wineverdict.app.data.yearAgo
import com.wineverdict.app.data.yearAgoWords
import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
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
    // The entry being corrected, if any. Held as the entry itself rather than an id so the sheet
    // opens holding what was saved even if the list moves underneath it.
    var editing by remember { mutableStateOf<Drink?>(null) }
    // The book is a record of years, so it can be read two ways: by the words on a label, or by the
    // evening. Neither changes a single entry — they only choose what is shown.
    var query by rememberSaveable { mutableStateOf("") }
    var calendar by rememberSaveable { mutableStateOf(false) }
    // A YearMonth cannot be held by saved screen state, so the month travels as its ISO string.
    var monthKey by rememberSaveable { mutableStateOf(YearMonth.now(zone).toString()) }
    var pickedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val calendarMonth = remember(monthKey) {
        runCatching { YearMonth.parse(monthKey) }.getOrNull() ?: YearMonth.now(zone)
    }
    val picked = remember(pickedKey) {
        pickedKey?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

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
                venue = entry.venue,
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

    /**
     * A night corrected: the book first, then the bottle the night wrote onto.
     *
     * **Drank one** and a hand-written entry are the two that put a verdict and a dated line on the
     * bottle, and the taste brief reads the bottle rather than this log — a correction that stopped
     * at the book would leave the word the user just changed feeding every scan and every list.
     */
    fun correct(
        entry: Drink,
        at: Long,
        rating: Int?,
        flavors: List<String>,
        again: Boolean?,
        note: String,
        venue: String
    ) {
        val after = AppGraph.drinks.update(entry.id, at, rating, note, flavors, again, venue)
            ?: return
        val onBottle = entry.wineId.isNotBlank() &&
            (entry.via == Drink.MANUAL || entry.via == Drink.DRANK)
        if (onBottle) {
            scope.launch { AppGraph.cellar.correctJournalTaste(entry, after) }
        }
        scope.launch {
            snackbarState.showSnackbar(
                if (onBottle) "Corrected — here and on the bottle." else "Corrected."
            )
        }
    }

    /** Takes a night out of the book. The bottle keeps whatever that night wrote onto it. */
    fun forget(entry: Drink) {
        AppGraph.drinks.remove(entry.id)
        scope.launch { snackbarState.showSnackbar("Taken out of the cellar book.") }
    }

    // What the bottles already know and the book was never told: a verdict or a note from before
    // there was a record. Derived, so the offer disappears as soon as it has been taken.
    val tastedBottles = remember(data.wines, drinks) { bottleTastings(data.wines, drinks) }
    fun bringAcross() {
        val count = AppGraph.drinks.bringAcross(tastedBottles)
        if (count > 0) {
            scope.launch {
                snackbarState.showSnackbar(
                    if (count == 1) "One tasting brought across from its bottle."
                    else "$count tastings brought across from their bottles."
                )
            }
        }
    }

    // Every word a row shows is a word to search by. A day picked in the calendar then narrows
    // what is left, and the record itself is never touched.
    val matched = remember(drinks, query) { drinks.filter { matchesWords(it, query) } }
    val shown = remember(matched, picked, zone) {
        val day = picked ?: return@remember matched
        matched.filter { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == day }
    }
    // Which nights carry an entry, for the calendar's marks.
    val byDay = remember(drinks, zone) {
        drinks.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
            .mapValues { it.value.size }
    }
    // "A year ago tonight" is about the evening the calendar points at: the picked day when one is
    // picked, today otherwise. Derived, so it follows the pick without a second piece of state.
    val calendarNight = picked ?: LocalDate.now(zone)
    val yearAgoNight = calendarNight.minusYears(1)
    val yearAgoEntries = remember(drinks, calendarNight, zone) {
        yearAgo(drinks, calendarNight, zone)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cellar book", style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    // Two ways of reading the same record; the icon says what the tap will show.
                    IconButton(onClick = { calendar = !calendar }) {
                        Icon(
                            imageVector = if (calendar) {
                                Icons.Filled.ViewList
                            } else {
                                Icons.Filled.CalendarMonth
                            },
                            contentDescription = if (calendar) {
                                "Show the list of nights"
                            } else {
                                "Show the calendar"
                            }
                        )
                    }
                }
            )
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
                    .verticalScroll(rememberScrollState())
            ) {
                if (tastedBottles.isNotEmpty()) {
                    BringAcrossCard(
                        count = tastedBottles.size,
                        onBring = { bringAcross() },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    )
                }
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

        // Tastings brought across from bottles are entries in the record but not nights the app
        // watched, so the seven- and thirty-day lines count bottles only.
        val fromBottles = drinks.count { it.via == Drink.BOTTLE }
        val week = drinks.count { it.via != Drink.BOTTLE && it.at >= now - 7 * DAY_MS }
        val month = drinks.count { it.via != Drink.BOTTLE && it.at >= now - 30 * DAY_MS }
        // Already newest first, and groupBy keeps that order, so the nights come out in the order
        // they happened without a second sort.
        val nights: Map<LocalDate, List<Drink>> = shown.groupBy { drink ->
            Instant.ofEpochMilli(drink.at).atZone(zone).toLocalDate()
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (tastedBottles.isNotEmpty()) {
                item(key = "bring-across") {
                    BringAcrossCard(count = tastedBottles.size, onBring = { bringAcross() })
                }
            }
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search a bottle, place, note…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = if (query.isBlank()) {
                        null
                    } else {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear the search")
                            }
                        }
                    }
                )
            }
            if (calendar) {
                item(key = "calendar") {
                    CalendarCard(
                        month = calendarMonth,
                        counts = byDay,
                        picked = picked,
                        onMonth = { monthKey = it.toString() },
                        onPick = { pickedKey = it?.toString() }
                    )
                }
                if (yearAgoEntries.isNotEmpty()) {
                    item(key = "year-ago") {
                        YearAgoCard(
                            day = yearAgoNight,
                            entries = yearAgoEntries,
                            // The tap moves the calendar onto that night, so the whole record —
                            // the row, its words, its correction — is one step away.
                            onOpen = {
                                monthKey = YearMonth.from(yearAgoNight).toString()
                                pickedKey = yearAgoNight.toString()
                            }
                        )
                    }
                }
            }
            val selectedDay = picked
            if (selectedDay != null) {
                item(key = "picked-day") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            selectedDay.format(
                                DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault())
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { pickedKey = null }) { Text("Show all") }
                    }
                }
            }
            item {
                SectionCard("On record") {
                    Text(
                        text = "$week in the last 7 days · $month in the last 30 · " +
                            "${drinks.size - fromBottles} recorded" +
                            if (fromBottles > 0) " · $fromBottles from bottles" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val pickedDay = picked
            if (shown.isEmpty()) {
                item(key = "no-match") {
                    EmptyState(
                        title = "Nothing in the book matches",
                        body = when {
                            pickedDay != null && query.isNotBlank() ->
                                "No bottle on that day matches those words. The record itself is " +
                                    "unchanged."
                            pickedDay != null ->
                                "Nothing was written in the book on that day."
                            else ->
                                "No bottle in the book matches those words. The record itself is " +
                                    "unchanged — clear the search to see it all."
                        }
                    )
                }
            }
            nights.forEach { (date, list) ->
                item(key = "night-$date") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            dayWords(list.first().at, now, zone),
                            style = MaterialTheme.typography.titleSmall
                        )
                        val drank = list.count { it.via != Drink.BOTTLE }
                        val tasted = list.size - drank
                        val label = buildList {
                            if (drank > 0) add(if (drank == 1) "1 bottle" else "$drank bottles")
                            if (tasted > 0) add(if (tasted == 1) "1 tasting" else "$tasted tastings")
                        }.joinToString(" · ")
                        Text(
                            " · " + label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(list, key = { it.id }) { drink ->
                    DrinkRow(drink, zone, onEdit = { editing = drink })
                }
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

    editing?.let { entry ->
        JournalEditDialog(
            drink = entry,
            onDismiss = { editing = null },
            onSave = { at, rating, flavors, again, note, venue ->
                editing = null
                correct(entry, at, rating, flavors, again, note, venue)
            },
            onDelete = {
                editing = null
                forget(entry)
            }
        )
    }
}

/**
 * A month of the book, so a bottle can be found by the evening rather than by the words.
 *
 * Days that carry an entry are marked and can be tapped; tapping one narrows the list below to that
 * night, and tapping it again lets go. Weeks start on the locale's first day, because a calendar
 * that begins on the wrong day is read wrongly before it is read at all.
 *
 * A book spans years, so a month at a time is not always enough: **Today** comes back to the month
 * the phone is in and the double arrows move a year for each tap, with the title saying where the
 * month landed.
 */
@Composable
private fun CalendarCard(
    month: YearMonth,
    counts: Map<LocalDate, Int>,
    picked: LocalDate?,
    onMonth: (YearMonth) -> Unit,
    onPick: (LocalDate?) -> Unit
) {
    val weekStart = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val today = LocalDate.now()
    val thisMonth = YearMonth.now()
    SectionCard(title = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            // Back to the month the phone is in, and off when it is already there with nothing
            // picked: a button that can only do nothing is worse than no button. A night picked in
            // another decade is let go as well — "today" is the month and the whole book, and
            // Show all is one more tap this can save.
            TextButton(
                onClick = {
                    onMonth(thisMonth)
                    onPick(null)
                },
                enabled = month != thisMonth || picked != null
            ) {
                Text("Today")
            }
            IconButton(onClick = { onMonth(month.minusMonths(1)) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous month")
            }
            IconButton(onClick = { onMonth(month.plusMonths(1)) }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Next month")
            }
        }
        val nights = counts.keys.count { YearMonth.from(it) == month }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (nights) {
                    0 -> "Nothing written this month."
                    1 -> "1 night written this month."
                    else -> "$nights nights written this month."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            // Twelve months is a long way through a five-year book; these move a year per tap and
            // the title above says which one the month landed in.
            IconButton(onClick = { onMonth(month.minusYears(1)) }) {
                Icon(Icons.Filled.KeyboardDoubleArrowLeft, contentDescription = "Previous year")
            }
            IconButton(onClick = { onMonth(month.plusYears(1)) }) {
                Icon(Icons.Filled.KeyboardDoubleArrowRight, contentDescription = "Next year")
            }
        }
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { index ->
                Text(
                    weekStart.plus(index.toLong())
                        .getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        val leading = (month.atDay(1).dayOfWeek.value - weekStart.value + 7) % 7
        val cells: List<LocalDate?> = buildList {
            repeat(leading) { add(null) }
            for (day in 1..month.lengthOfMonth()) add(month.atDay(day))
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        DayCell(
                            date = date,
                            count = counts[date] ?: 0,
                            selected = date == picked,
                            today = date == today,
                            modifier = Modifier.weight(1f),
                            onClick = { onPick(if (date == picked) null else date) }
                        )
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The night a year earlier, when the calendar is looking at a day that has one.
 *
 * The book already knows which evening is being read, so its anniversary costs no searching: the
 * wines with the words they were given, the date, and a tap that moves the calendar onto that night
 * so it can be read and corrected like any other.
 */
@Composable
private fun YearAgoCard(day: LocalDate, entries: List<Drink>, onOpen: () -> Unit) {
    SectionCard(
        title = "A year ago tonight",
        modifier = Modifier.clickable(onClick = onOpen)
    ) {
        Text(
            day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(yearAgoWords(entries), style = MaterialTheme.typography.bodyMedium)
        Text(
            "Tap to open that night.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** One day of the calendar: its number, whether anything was written, and whether it is chosen. */
@Composable
private fun DayCell(
    date: LocalDate,
    count: Int,
    selected: Boolean,
    today: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> scheme.primaryContainer
                    count > 0 -> scheme.surfaceVariant
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "${date.dayOfMonth}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
            color = when {
                selected -> scheme.onPrimaryContainer
                count > 0 -> scheme.onSurface
                else -> scheme.outline
            },
            maxLines = 1
        )
        if (count > 0) {
            Spacer(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (selected) scheme.onPrimaryContainer else scheme.primary)
            )
        }
    }
}

/** One bottle, as the night it was opened. A tap opens it for correction. */
@Composable
private fun DrinkRow(drink: Drink, zone: ZoneId, onEdit: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
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
                // A tap anywhere on the row opens it; the pencil is what says so.
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Correct this night",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
            if (drink.venue.isNotBlank()) {
                Text(
                    "At ${drink.venue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
            // the day above the card is the whole of what is known. A tasting from a bottle is the
            // same, and says where it came from instead of pretending to be a night.
            when (drink.via) {
                Drink.MANUAL -> Unit
                Drink.BOTTLE -> Text(
                    "from the bottle",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                else -> Text(
                    timeOf(drink.at, zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/**
 * The bottles that were tasted before the book existed.
 *
 * One tap, because the alternative is retyping a verdict the app already holds. The date is the
 * note's when it has one, the bottle's last change when it does not, and every entry says it came
 * from the bottle: a tasting, not a night the app saw.
 */
@Composable
private fun BringAcrossCard(count: Int, onBring: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(title = "From your bottles", modifier = modifier) {
        Text(
            text = if (count == 1) {
                "One bottle carries a verdict or a tasting note from before the book."
            } else {
                "$count bottles carry a verdict or a tasting note from before the book."
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "Dated by the note, or by when the bottle last changed. They are marked as " +
                "from the bottle, not as a night the app saw.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onBring) {
            Text(if (count == 1) "Bring it across" else "Bring them across")
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
    val venue: String,
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
    var venue by remember { mutableStateOf("") }
    var pickingDate by remember { mutableStateOf(false) }

    val matches = remember(name, wines, picked) {
        val words = name.trim()
        if (picked != null || words.length < 2) {
            emptyList()
        } else {
            wines.filter { Search.matches(listOf(it.title), words) }
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

                    OutlinedTextField(
                        value = venue,
                        onValueChange = { venue = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Where (optional)") },
                        placeholder = { Text("A restaurant, a friend's house…") }
                    )

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
                                    venue = venue,
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
        NightPickerDialog(
            night = night,
            onDismiss = { pickingDate = false },
            onNight = { night = it }
        )
    }
}

/**
 * The date picker both sheets use, so a night typed in and a night corrected cannot be dated two
 * different ways: the picker works in UTC days, the record is a local night, and today keeps the
 * hour it is written at instead of snapping back to midnight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightPickerDialog(night: Long, onDismiss: () -> Unit, onNight: (Long) -> Unit) {
    // The picker works in UTC days, so it is seeded with the local night's date at UTC midnight.
    // Seeded with the timestamp itself, an evening after 19:00 in a western zone opened on the next
    // day — and pressing Set without touching anything moved the night by one.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = Instant.ofEpochMilli(night)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { chosen ->
                        val zone = ZoneId.systemDefault()
                        val day = Instant.ofEpochMilli(chosen)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        onNight(
                            if (day == LocalDate.now(zone)) {
                                System.currentTimeMillis()
                            } else {
                                day.atStartOfDay(zone).toInstant().toEpochMilli()
                            }
                        )
                    }
                    onDismiss()
                }
            ) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        DatePicker(state = state)
    }
}

/**
 * One night, corrected.
 *
 * The date, the verdict, the taste words, the buy-again answer and the line are the five things a
 * remembered evening gets wrong, so the sheet opens holding what was saved rather than blank.
 *
 * The bottle is not editable here on purpose: moving a night onto a second bottle would leave the
 * first holding a tasting it never had. That is a night to delete and write again, which is why
 * Delete lives on this sheet and asks before it writes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun JournalEditDialog(
    drink: Drink,
    onDismiss: () -> Unit,
    onSave: (
        at: Long,
        rating: Int?,
        flavors: List<String>,
        again: Boolean?,
        note: String,
        venue: String
    ) -> Unit,
    onDelete: () -> Unit
) {
    var night by remember(drink.id) { mutableStateOf(drink.at) }
    var rating by remember(drink.id) { mutableStateOf(drink.rating) }
    // A word saved before the survey offered it still shows, or correcting the night would drop it.
    val words = remember(drink.flavors) { (drink.flavors + SURVEY_FLAVORS).distinct() }
    var flavors by remember(drink.id) { mutableStateOf(drink.flavors) }
    var again by remember(drink.id) { mutableStateOf(drink.again) }
    var note by remember(drink.id) { mutableStateOf(drink.note) }
    var venue by remember(drink.id) { mutableStateOf(drink.venue) }
    var pickingDate by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val onBottle = drink.wineId.isNotBlank() &&
        (drink.via == Drink.MANUAL || drink.via == Drink.DRANK)

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
                        "Correct this night",
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
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(drink.title, style = MaterialTheme.typography.titleSmall)
                        val where = listOf(drink.type, drink.region)
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                        if (where.isNotBlank()) {
                            Text(
                                where,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Said at the top, before anything is changed: the survey's chips are tall
                        // enough to push a line at the bottom of the sheet under its fold.
                        Text(
                            text = if (onBottle) {
                                "Correcting this night also corrects the bottle: its verdict and " +
                                    "this night's line, unless a later verdict is yours. Delete takes " +
                                    "the entry out and leaves the bottle as it is."
                            } else {
                                "This changes the book only — the bottle keeps its own rating and " +
                                    "notes. Delete takes the entry out of the book."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
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

                    OutlinedTextField(
                        value = venue,
                        onValueChange = { venue = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Where (optional)") },
                        placeholder = { Text("A restaurant, a friend's house…") }
                    )

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
                        words.forEach { word ->
                            val chosen = word in flavors
                            FilterChip(
                                selected = chosen,
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
                        maxLines = 3
                    )

                    TextButton(
                        onClick = { confirming = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete this night")
                    }
                    // Room under the last line, so the sheet's own edge never cuts it in half.
                    Spacer(Modifier.size(8.dp))
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
                        onClick = { onSave(night, rating, flavors, again, note, venue) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }

    if (pickingDate) {
        NightPickerDialog(
            night = night,
            onDismiss = { pickingDate = false },
            onNight = { night = it }
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete this night?") },
            text = {
                Text(
                    "“${drink.title}” comes out of the cellar book. The bottle's own rating and " +
                        "notes keep what they have, and the night cannot be put back from here."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep it") }
            }
        )
    }
}
