package com.vinoteca.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinoteca.app.AppGraph
import kotlinx.coroutines.launch

/**
 * The gate for people who want to write the file themselves.
 *
 * It is deliberately behind Settings → Advanced and named as the file, not as "taste settings": the
 * quick start is the normal route, and this screen is the escape hatch for anyone who would rather
 * type Markdown than answer ten taps.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdvancedTasteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val taste by AppGraph.taste.text.collectAsStateWithLifecycle()
    val cellar by AppGraph.cellar.data.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }

    LaunchedEffect(taste, dirty) {
        if (!dirty) draft = taste
    }

    val loved = remember(cellar.wines) { AppGraph.taste.lovedBottles(cellar.wines) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                scope.launch { snackbarHostState.showSnackbar("Could not read that file") }
            } else {
                AppGraph.taste.write(text)
                AppGraph.palate.markFileEdited()
                dirty = false
                draft = text
                scope.launch { snackbarHostState.showSnackbar("Imported mytaste.md") }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(AppGraph.taste.text.value.toByteArray())
                }
            }
            scope.launch { snackbarHostState.showSnackbar("Exported mytaste.md") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit mytaste.md") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Advanced. This is the whole file the model reads — Ask sends all of it, a scan sends the sections about what you like and avoid. Saving here marks the file as yours, so a later quick start keeps it instead of replacing it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionCard("Loved bottles that feed this file") {
                Text(
                    if (loved.isEmpty()) {
                        "No favourite or 4★+ bottles yet."
                    } else {
                        "${loved.size} bottles: " + loved.take(6).joinToString(", ") { it.title } +
                            if (loved.size > 6) "…" else ""
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = {
                        val updated = AppGraph.taste.rebuildLovedSection(cellar.wines)
                        AppGraph.palate.markFileEdited()
                        dirty = false
                        draft = updated
                        scope.launch {
                            snackbarHostState.showSnackbar("Rebuilt the loved-bottles section")
                        }
                    }
                ) {
                    Text("Rebuild from loved bottles")
                }
            }

            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    dirty = true
                },
                label = { Text("mytaste.md") },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 340.dp)
            )

            Button(
                onClick = {
                    AppGraph.taste.write(draft)
                    AppGraph.palate.markFileEdited()
                    dirty = false
                    scope.launch { snackbarHostState.showSnackbar("Saved mytaste.md") }
                },
                enabled = dirty,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (dirty) "Save changes" else "Saved")
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*")) }) {
                    Text("Import file")
                }
                OutlinedButton(onClick = { exportLauncher.launch("mytaste.md") }) {
                    Text("Export file")
                }
            }

            Text(
                "The section between the AUTO markers is replaced when you rebuild. Everything you write by hand is kept.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}