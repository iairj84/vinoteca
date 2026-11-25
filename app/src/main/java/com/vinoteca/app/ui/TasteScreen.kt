package com.vinoteca.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinoteca.app.AppGraph
import com.vinoteca.app.data.GateVerdict
import com.vinoteca.app.data.PairingTable
import com.vinoteca.app.data.PalateFile
import com.vinoteca.app.data.PalateRules
import com.vinoteca.app.data.QuickStart
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
        cellar.wines.count { wine -> rules.checks(wine).any { it.verdict == GateVerdict.FAIL } }
    }
    val passing = remember(cellar.wines, rules) {
        cellar.wines.count { wine -> rules.checks(wine).any { it.verdict == GateVerdict.PASS } }
    }
    val answers = remember(profile) { PalateFile.answers(profile) }

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
                        "Ten taps, about two minutes, no typing and no wine vocabulary. Every question can be skipped — skipping is \"unknown\", not a wrong answer.",
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
                    rules.oak?.let {
                        LabeledValue("Oak", it.label)
                    }
                    rules.maxAlcohol?.let {
                        LabeledValue(
                            "Alcohol",
                            "${oneDecimal(it)}%${if (rules.alcoholAssumed) " (assumed)" else ""}"
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