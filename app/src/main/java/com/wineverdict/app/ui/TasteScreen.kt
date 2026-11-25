package com.wineverdict.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wineverdict.app.AppGraph
import com.wineverdict.app.data.GateVerdict
import com.wineverdict.app.data.PairingTable
import com.wineverdict.app.data.PalateFile
import com.wineverdict.app.data.PalateProfile
import com.wineverdict.app.data.PalateRules
import com.wineverdict.app.data.QuickStart
import java.util.Locale

/**
 * What the app knows about your palate, and how it knows it.
 *
 * The raw Markdown lives one screen away: this tab is the summary — the answers, the thresholds the
 * app checks itself, and the part of the file the model actually reads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasteScreen(
    onStartQuickStart: () -> Unit = {},
    onOpenAdvanced: () -> Unit = {}
) {
    val profile by AppGraph.palate.profile.collectAsStateWithLifecycle()
    val text by AppGraph.taste.text.collectAsStateWithLifecycle()
    val cellar by AppGraph.cellar.data.collectAsStateWithLifecycle()

    val rules = remember(profile) { PalateRules.from(profile) }
    val dishes = remember(profile) {
        PairingTable.forContext(profile.choice(QuickStart.Q_CONTEXT))
    }
    val failures = remember(cellar.wines, rules) {
        cellar.wines.count { wine -> rules.fails(wine) }
    }
    // A rule of the user's that fails a bottle the user loves is a contradiction worth one line.
    val lovedConflict = remember(cellar.wines, rules) { rules.lovedConflict(cellar.wines) }
    val passing = remember(cellar.wines, rules) {
        cellar.wines.count { wine -> rules.checks(wine).any { it.verdict == GateVerdict.PASS } }
    }
    val answers = remember(profile) { PalateFile.answers(profile) }
    var ceilingText by remember(profile.alcoholCeiling) {
        mutableStateOf(profile.alcoholCeiling?.let { oneDecimal(it) } ?: "")
    }
    var linksText by remember(profile.links) { mutableStateOf(profile.links) }
    val ceilingTyped = ceilingText.trim().toDoubleOrNull()
    val ceilingValid = ceilingText.isBlank() ||
        (ceilingTyped != null && ceilingTyped in PalateProfile.ALCOHOL_RANGE)

    Scaffold(topBar = { TopAppBar(title = { Text("Taste profile") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "mytaste.md is what the model judges wine against. Ask sends the whole file; a scan or a shelf frame sends the sections about what you like and avoid. The gates below are checked by the app itself, not by the model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionCard(if (profile.isComplete) "What I know" else "Start here") {
                if (profile.isComplete) {
                    Text(
                        "${profile.knowledgePercent}% of the baseline palate — ten taps, and no ratings yet.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { profile.knowledgePercent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Rating a bottle is what turns this into a palate. Three ratings make the answers worth revising.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    OutlinedButton(onClick = onStartQuickStart) {
                        Text("Retake the quick start")
                    }
                } else {
                    Text(
                        "Ten taps, about two minutes, no wine vocabulary. Every question can be skipped — skipping is \"unknown\", not a wrong answer. Three optional lines you can type at the end are the only typing there is.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onStartQuickStart, modifier = Modifier.fillMaxWidth()) {
                        Text("Start the quick start")
                    }
                }
            }

            if (answers.isNotEmpty()) {
                SectionCard("Your answers") {
                    answers.forEach { (question, answer) ->
                        LabeledValue(question, answer)
                    }
                }
            }

            SectionCard("Your limits") {
                Text(
                    "A ceiling is a number you give, never one I guess. \"Avoid strong\" in the " +
                        "quick start is a preference; a limit is something you type here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = ceilingText,
                    onValueChange = { typed ->
                        ceilingText = typed
                            .filter { it.isDigit() || it == '.' || it == ',' }
                            .replace(',', '.')
                            .take(5)
                    },
                    label = { Text("Alcohol ceiling (% vol)") },
                    placeholder = { Text("e.g. 13.5") },
                    singleLine = true,
                    isError = !ceilingValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (!ceilingValid) {
                    Text(
                        "A wine is between 5% and 25% — that looks like a typo.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { AppGraph.palate.save(profile.withAlcoholCeiling(ceilingTyped)) },
                        enabled = ceilingValid && ceilingTyped != profile.alcoholCeiling
                    ) {
                        Text("Save ceiling")
                    }
                    if (profile.alcoholCeiling != null) {
                        TextButton(onClick = {
                            ceilingText = ""
                            AppGraph.palate.save(profile.withAlcoholCeiling(null))
                        }) {
                            Text("Remove")
                        }
                    }
                }
            }

            SectionCard("What links the wines you love?") {
                Text(
                    "One sentence in your words. It goes into the brief the model reads, so a reason " +
                        "can be tied to the bottles you actually loved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = linksText,
                    onValueChange = { linksText = it.take(PalateProfile.NOTE_MAX_CHARS) },
                    label = { Text("In your words") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { AppGraph.palate.save(profile.withLinks(linksText)) },
                    enabled = linksText.trim() != profile.links
                ) {
                    Text("Save")
                }
            }

            SectionCard("Gates the app checks") {
                if (rules.isEmpty) {
                    Text(
                        "No checkable gate yet — the quick start did not ask for a number. Style and price stay guidance, not rules.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    rules.maxSugar?.let {
                        LabeledValue("Sweetness", "dry — under ${oneDecimal(it)} g/L")
                    }
                    rules.maxAlcohol?.let {
                        LabeledValue("Alcohol", "${oneDecimal(it)}% or under")
                    }
                    lovedConflict?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (cellar.wines.isNotEmpty()) {
                        Text(
                            "On the ${cellar.wines.size} wines in your cellar: $failures fail a gate, " +
                                "${cellar.wines.size - failures - passing} cannot be checked from the record.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "A missing fact reads as unknown, never as a pass — an unlisted ABV cannot satisfy an alcohol ceiling.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            SectionCard("Pairing") {
                if (dishes.isNotEmpty()) {
                    dishes.forEach { dish ->
                        LabeledValue(dish.label, dish.wins)
                    }
                }
                Text(
                    "Nothing here is written into mytaste.md: the file says pairing is worked out per " +
                        "dish when you ask. These classes ship with the app as reference.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            SectionCard("The file") {
                Text(
                    "${text.length} characters of plain Markdown — readable, editable and exportable.",
                    style = MaterialTheme.typography.bodySmall
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = text.ifBlank { "No file yet." },
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(onClick = onOpenAdvanced, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit mytaste.md (advanced)")
                }
            }
        }
    }
}

private fun oneDecimal(value: Double): String =
    String.format(Locale.US, "%.1f", value)