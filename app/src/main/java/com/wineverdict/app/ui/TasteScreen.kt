package com.wineverdict.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wineverdict.app.AppGraph
import com.wineverdict.app.data.DetailsBackfill
import com.wineverdict.app.data.GateVerdict
import com.wineverdict.app.data.PairingTable
import com.wineverdict.app.data.PalateFile
import com.wineverdict.app.data.PalateRules
import com.wineverdict.app.data.QuickStart

/**
 * The Taste tab: what the app knows, and the exact text a model reads.
 *
 * It used to be six cards of settings — answers, a typed ceiling, a links box, gates, pairing classes
 * and the whole file — while the flow collected some of the same facts in another place. Every
 * answer now lives in the interview; this screen shows the result. The brief is printed verbatim
 * because it is the thing that actually reaches a model, and until now it could not be seen anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasteScreen(
    onStartQuickStart: () -> Unit = {},
    onAddDetail: () -> Unit = {},
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
        cellar.wines.count { wine -> rules.checks(wine).any { it.verdict.isPass } }
    }
    // A record that cannot be rendered must say so on this screen, not take the app down with it:
    // a force close here reads as "the Taste tab is broken" and hides which fact caused it.
    val answersResult = remember(profile) { runCatching { PalateFile.answers(profile) } }
    val answers = answersResult.getOrDefault(emptyList())
    // The live brief, from the same lambda a scan uses: what is printed here is what a model reads.
    val briefResult = remember(profile, cellar.wines, text) { runCatching { AppGraph.taste.brief() } }
    val brief = briefResult.getOrDefault("")
    val screenError = briefResult.exceptionOrNull() ?: answersResult.exceptionOrNull()
    LaunchedEffect(screenError) {
        screenError?.let { Log.e("TasteScreen", "could not build the taste profile", it) }
    }
    var showAnswers by remember { mutableStateOf(false) }
    var showFile by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Taste profile") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            screenError?.let { error ->
                SectionCard("This screen hit an error") {
                    Text(
                        "Something in the profile or the cellar could not be read, so the rest of " +
                            "this page may be incomplete. The app stayed up; this is the message it " +
                            "gave: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Text(
                "A scan, a shelf and a restaurant list send the short brief below — your answers in " +
                    "first person, about a page. Ask sends the whole mytaste.md. The gates are " +
                    "checked by the app itself, not by the model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionCard(if (profile.isComplete) "What I know" else "Start here") {
                if (profile.isComplete) {
                    Text(
                        "${profile.knowledgePercent}% of the ten-tap baseline — " +
                            "${profile.baselineAnswered} of ${QuickStart.baseline.size} answered.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { profile.knowledgePercent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Optional interview: ${profile.extrasAnswered} of ${QuickStart.extras.size} " +
                            "answered. Rating a bottle is what turns any of this into a palate.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                // The interview is the point of this button, so it opens at the
                                // gate rather than at question one.
                                if (profile.extrasAnswered < QuickStart.extras.size) onAddDetail()
                                else onStartQuickStart()
                            }
                        ) {
                            Text(
                                if (profile.extrasAnswered < QuickStart.extras.size) "Add more detail"
                                else "Review my answers"
                            )
                        }
                        OutlinedButton(onClick = onStartQuickStart) { Text("Retake") }
                    }
                } else {
                    Text(
                        "Ten taps, about two minutes, no wine vocabulary. Every question can be " +
                            "skipped — skipping is \"unknown\", not a wrong answer. After them come " +
                            "twelve optional questions that sharpen a wine-list answer, and three " +
                            "lines of your own words.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onStartQuickStart, modifier = Modifier.fillMaxWidth()) {
                        Text("Start the quick start")
                    }
                }
            }

            SectionCard("What the model reads") {
                Text(
                    "${brief.length} characters, built from the answers above and your cellar. " +
                        "An unanswered question is left out rather than guessed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = brief.ifBlank { "Nothing yet — the answers start from scratch." },
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }

            SectionCard("Gates the app checks") {
                if (rules.isEmpty) {
                    Text(
                        "No checkable gate yet — the quick start did not ask for a number. Style and " +
                            "price stay guidance, not rules.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    // Rendered from the registry, so the screen cannot show fewer gates than the
                    // engine runs: all four, in the authored order, with whose number each is.
                    remember(rules) { rules.specs() }.forEach { spec ->
                        LabeledValue(
                            spec.title,
                            "${spec.test} · ${spec.severity.name.lowercase()} · " +
                                spec.confidence.label
                        )
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
                                "${cellar.wines.size - failures - passing} cannot be checked from " +
                                "the record.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        val incomplete = remember(cellar.wines) {
                            DetailsBackfill.pending(cellar.wines).size
                        }
                        if (incomplete > 0) {
                            Text(
                                "$incomplete of them are missing something a verdict reads — a " +
                                    "drinking window, a place, an ABV or published notes. " +
                                    "Settings → Fill in the details collects them in one pass.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    Text(
                        "A missing fact reads as unknown, never as a pass — an unlisted ABV cannot " +
                            "satisfy an alcohol ceiling.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            SectionCard("Your answers (${answers.size})") {
                TextButton(onClick = { showAnswers = !showAnswers }) {
                    Text(if (showAnswers) "Hide" else "Show")
                }
                if (showAnswers) {
                    if (answers.isEmpty()) {
                        Text("Nothing answered yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        answers.forEach { (question, answer) -> LabeledValue(question, answer) }
                    }
                } else {
                    Text(
                        "The questions and the chips selected. Changing one means running the flow " +
                            "again; it opens with your current answers already chosen.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            SectionCard("Pairing") {
                if (dishes.isNotEmpty()) {
                    dishes.forEach { dish -> LabeledValue(dish.label, dish.wins) }
                }
                Text(
                    "Nothing here is written into mytaste.md: the file says pairing is worked out " +
                        "per dish when you ask. These classes ship with the app as reference.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            SectionCard("The file") {
                Text(
                    "${text.length} characters of plain Markdown — yours to read, edit and export. " +
                        "Ask sends it whole.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { showFile = !showFile }) {
                    Text(if (showFile) "Hide the file" else "Show the file")
                }
                if (showFile) {
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
                }
                OutlinedButton(onClick = onOpenAdvanced, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit mytaste.md (advanced)")
                }
            }
        }
    }
}

