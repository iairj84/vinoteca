package com.wineverdict.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wineverdict.app.AppGraph
import com.wineverdict.app.data.Drink
import com.wineverdict.app.data.dayWords
import com.wineverdict.app.model.Verdict
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 * What has actually been drunk, as a list.
 *
 * The same record Ask answers from, readable without asking a model about your own evening: grouped
 * by the night, newest first, each bottle with the verdict given then — or the plain fact that the
 * count was corrected instead, because a bottle taken out with the minus button left the shelf too.
 *
 * It is a place rather than a filter on the cellar, for the same reason a saved list is: a bottle
 * finished months ago is still "in the cellar" as a record with nothing left, and the history of
 * what was opened is a different question from what is on the shelf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrinksScreen(onBack: () -> Unit) {
    val drinks by AppGraph.drinks.drinks.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(drinks) { System.currentTimeMillis() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Drinks", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (drinks.isEmpty()) {
            EmptyState(
                title = "Nothing drunk yet",
                body = "Every bottle that leaves the cellar is written here — Drank one, and the minus " +
                    "button too — with the day and the verdict you gave it. Ask answers what you " +
                    "drank from this same record.",
                modifier = Modifier.padding(padding)
            )
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
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
            Text(
                timeOf(drink.at, zone),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
