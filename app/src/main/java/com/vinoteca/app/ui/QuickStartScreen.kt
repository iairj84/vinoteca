package com.vinoteca.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinoteca.app.AppGraph
import com.vinoteca.app.data.PalateFile
import com.vinoteca.app.data.PalateProfile
import com.vinoteca.app.data.PalateRules
import com.vinoteca.app.data.QuickStart
import com.vinoteca.app.data.QuickStartQuestion
import com.vinoteca.app.data.Shortlist
import kotlinx.coroutines.launch

/**
 * The tap-only quick start: seven taps, three picks to look for, three more taps, then the file.
 *
 * Nothing here types, and nothing here can block: every question has a Skip that records "unknown"
 * rather than producing a rule. The last screen shows the actual Markdown that will be saved, because
 * the file is the thing the model obeys and the user should see it before it becomes one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickStartScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val cellar by AppGraph.cellar.data.collectAsStateWithLifecycle()
    var profile by remember { mutableStateOf(AppGraph.palate.profile.value) }
    var index by remember { mutableIntStateOf(0) }
    val steps = remember { buildSteps() }

    fun finish() {
        val finished = profile.completed()
        AppGraph.palate.save(finished)
        val body = PalateFile.build(
            finished,
            PalateRules.from(finished),
            ratings = cellar.wines.count { it.rating != null }
        )
        val current = AppGraph.taste.text.value
        val keep = AppGraph.palate.handEdited || !current.contains(PalateFile.MARKER)
        AppGraph.taste.writeFromQuickStart(body, keepExisting = keep)
        AppGraph.taste.rebuildLovedSection(cellar.wines)
        scope.launch {
            snackbar.showSnackbar("Saved mytaste.md")
            onDone()
        }
    }

    fun goNext() {
        if (index < steps.lastIndex) index += 1 else finish()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Build my palate") },
                navigationIcon = {
                    IconButton(onClick = { if (index == 0) onDone() else index -= 1 }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ProgressDots(total = steps.size, current = index)
            Text(
                "${index + 1} of ${steps.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            when (val step = steps[index]) {
                is Step.Ask -> AskStep(
                    question = step.question,
                    profile = profile,
                    onChange = { profile = it },
                    onNext = { goNext() },
                    onSkip = {
                        profile = profile.skip(step.question.id)
                        goNext()
                    }
                )
                is Step.Picks -> PicksStep(profile = profile, onNext = { goNext() })
                is Step.Review -> ReviewStep(profile = profile, onSave = { finish() })
            }
        }
    }
}

private sealed interface Step {
    data class Ask(val question: QuickStartQuestion) : Step
    data object Picks : Step
    data object Review : Step
}

private fun buildSteps(): List<Step> {
    val steps = mutableListOf<Step>()
    QuickStart.questions.filter { !it.afterPicks }.forEach { steps += Step.Ask(it) }
    steps += Step.Picks
    QuickStart.questions.filter { it.afterPicks }.forEach { steps += Step.Ask(it) }
    steps += Step.Review
    return steps
}

@Composable
private fun ProgressDots(total: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (i <= current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskStep(
    question: QuickStartQuestion,
    profile: PalateProfile,
    onChange: (PalateProfile) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    val selected = profile.choices(question.id)

    Text(question.prompt, style = MaterialTheme.typography.headlineSmall)
    if (question.help.isNotBlank()) {
        Text(
            question.help,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        question.options.forEach { option ->
            FilterChip(
                selected = option.id in selected,
                onClick = {
                    val next = when {
                        !question.multi -> listOf(option.id)
                        option.id in selected -> selected - option.id
                        option.id == QuickStart.AVOID_NOTHING -> listOf(option.id)
                        else -> (selected - QuickStart.AVOID_NOTHING) + option.id
                    }
                    onChange(profile.withAnswer(question.id, next))
                },
                label = { Text(option.label) }
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onNext,
            enabled = selected.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) {
            Text("Continue")
        }
        TextButton(onClick = onSkip) { Text("Skip") }
    }
}

@Composable
private fun PicksStep(profile: PalateProfile, onNext: () -> Unit) {
    val picks = remember(profile) { Shortlist.forProfile(profile) }

    Text("Three things to look for", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Not bottles I can vouch for — styles that follow from your answers. Scan one when you find it and the cellar will take over from there.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    picks.forEachIndexed { i, pick ->
        SectionCard("${i + 1}. ${pick.look}") {
            Text(pick.why, style = MaterialTheme.typography.bodySmall)
        }
    }

    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text("Continue")
    }
}

@Composable
private fun ReviewStep(profile: PalateProfile, onSave: () -> Unit) {
    val cellar by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val ratings = cellar.wines.count { it.rating != null }
    val body = remember(profile, ratings) { PalateFile.build(profile, ratings = ratings) }
    val missing = QuickStart.questions.count { !profile.answered(it.id) }

    Text("This is your mytaste.md", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Written from your answers. Nothing in it was invented, and the gaps are listed at the end" +
            if (missing > 0) " — $missing questions were skipped, so they read as unknown." else ".",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = body,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
    }

    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text("Save and finish")
    }
}