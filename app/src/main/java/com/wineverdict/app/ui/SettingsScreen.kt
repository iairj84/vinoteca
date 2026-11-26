package com.wineverdict.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wineverdict.app.AppGraph
import com.wineverdict.app.BuildConfig
import com.wineverdict.app.ai.AiProvider
import com.wineverdict.app.data.Backup
import com.wineverdict.app.data.BackupFile
import com.wineverdict.app.data.DetailsBackfill
import com.wineverdict.app.data.ImportPreview
import com.wineverdict.app.data.SettingsStore
import com.wineverdict.app.data.SpendMeter
import com.wineverdict.app.data.Updater
import com.wineverdict.app.data.WineImport
import com.wineverdict.app.model.StorageLocation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenAdvancedTaste: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val data by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val fill by AppGraph.details.progress.collectAsStateWithLifecycle()
    var showAi by remember { mutableStateOf(false) }

    var currency by remember { mutableStateOf(AppGraph.settings.currency) }
    var primaryProvider by remember { mutableStateOf(AppGraph.settings.primaryProvider) }
    var drafts by remember {
        mutableStateOf(AiProvider.entries.associateWith { draftOf(AppGraph.settings, it) })
    }
    var revealedProvider by remember { mutableStateOf<AiProvider?>(null) }
    var testingProvider by remember { mutableStateOf<AiProvider?>(null) }
    var testResults by remember { mutableStateOf<Map<AiProvider, TestResult>>(emptyMap()) }

    var newLocationName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<StorageLocation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var confirmClearChat by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var dataMessage by remember { mutableStateOf<String?>(null) }
    var restoreCandidate by remember { mutableStateOf<BackupFile?>(null) }
    var importPreview by remember { mutableStateOf<ImportPreview?>(null) }

    var update by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
    var updateProgress by remember { mutableStateOf(0f) }
    var installCandidate by remember { mutableStateOf<File?>(null) }
    var updateApk by remember { mutableStateOf<File?>(null) }

    fun saveProvider(provider: AiProvider) {
        val draft = drafts[provider] ?: return
        AppGraph.settings.setKey(provider, draft.key)
        AppGraph.settings.setBaseUrl(provider, draft.baseUrl)
        AppGraph.settings.setVisionModels(provider, draft.vision.split(',', '\n'))
        AppGraph.settings.setTextModels(provider, draft.text.split(',', '\n'))
        AppGraph.settings.setAdviceModels(provider, draft.advice.split(',', '\n'))
        AppGraph.settings.setRequestsPerMinute(
            provider,
            draft.rpm.toIntOrNull() ?: provider.defaultRequestsPerMinute
        )
    }

    fun makePrimary(provider: AiProvider) {
        AppGraph.settings.primaryProvider = provider
        primaryProvider = provider
    }

    fun testProvider(provider: AiProvider) {
        saveProvider(provider)
        testingProvider = provider
        testResults = testResults - provider
        scope.launch {
            try {
                val models = AppGraph.ai.listModels(provider)
                AppGraph.settings.setLastModelList(provider, models.joinToString("\n"))
                testResults = testResults + (
                    provider to TestResult(
                        true,
                        if (models.isEmpty()) {
                            "Connected, but no models were listed for this key."
                        } else {
                            "Connected. ${models.size} models available, for example: " +
                                models.take(4).joinToString(", ")
                        }
                    )
                    )
            } catch (e: Exception) {
                testResults = testResults + (
                    provider to TestResult(false, e.message ?: "Connection failed.")
                    )
            } finally {
                testingProvider = null
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val cellar = AppGraph.cellar.data.value
                Backup.write(
                    context,
                    uri,
                    Backup.encode(
                        cellar = cellar,
                        taste = AppGraph.taste.text.value,
                        appVersion = BuildConfig.VERSION_NAME,
                        currency = AppGraph.settings.currency
                    )
                )
                dataMessage = "Backup written: ${cellar.wines.size} wines, " +
                    "${cellar.wines.sumOf { it.quantity }} bottles, plus your mytaste.md."
            } catch (e: Exception) {
                dataMessage = e.message ?: "Could not write the backup."
            } finally {
                busy = false
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                restoreCandidate = Backup.decode(Backup.decodeText(Backup.readBytes(context, uri)))
            } catch (e: Exception) {
                dataMessage = e.message ?: "That file is not a Wine Verdict backup."
            } finally {
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val text = Backup.decodeText(Backup.readBytes(context, uri))
                importPreview = WineImport.fromCsv(text, AppGraph.settings.currency)
            } catch (e: Exception) {
                dataMessage = e.message ?: "That file could not be imported."
            } finally {
                busy = false
            }
        }
    }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Back from "install unknown apps": carry on with the APK that was already downloaded.
        val apk = installCandidate
        installCandidate = null
        if (apk != null && AppGraph.updater.canInstall()) {
            runCatching { AppGraph.updater.install(apk) }
                .onFailure { update = UpdateUi.Failed("Android would not open its installer.") }
        }
    }

    fun checkForUpdates() {
        if (update is UpdateUi.Checking || update is UpdateUi.Downloading) return
        update = UpdateUi.Checking
        scope.launch {
            update = try {
                val release = AppGraph.updater.check()
                if (release == null) UpdateUi.UpToDate else UpdateUi.Available(release)
            } catch (e: Exception) {
                UpdateUi.Failed(e.message ?: "GitHub could not be reached.")
            }
        }
    }

    fun installUpdate(apk: File) {
        if (AppGraph.updater.canInstall()) {
            runCatching { AppGraph.updater.install(apk) }
                .onFailure { update = UpdateUi.Failed("Android would not open its installer.") }
        } else {
            installCandidate = apk
            runCatching { installLauncher.launch(AppGraph.updater.installSettingsIntent()) }
                .onFailure {
                    update = UpdateUi.Failed("This phone has no install-settings page for apps.")
                }
        }
    }

    fun downloadUpdate(release: Updater.Release) {
        update = UpdateUi.Downloading(release)
        updateProgress = 0f
        scope.launch {
            try {
                val apk = AppGraph.updater.download(release) { progress -> updateProgress = progress }
                updateApk = apk
                update = UpdateUi.Ready(release)
                installUpdate(apk)
            } catch (e: Exception) {
                update = UpdateUi.Failed(e.message ?: "The download did not finish.")
            }
        }
    }

    // One quiet question when Settings opens. Nothing is downloaded until it is tapped.
    LaunchedEffect(Unit) { if (update is UpdateUi.Idle) checkForUpdates() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
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
            SectionCard("AI provider") {
                val saved = AiProvider.entries.filter { AppGraph.settings.key(it).isNotBlank() }
                Text(
                    if (saved.isEmpty()) {
                        "No key yet, so scanning, Ask and the lookups cannot run. This is the " +
                            "account the app talks to on your behalf; the key is stored only on this " +
                            "phone."
                    } else {
                        "${saved.joinToString(" and ") { it.label }} " +
                            (if (saved.size == 1) "is" else "are") +
                            " answering. Keys, models and this month's spend are below — you only " +
                            "need them when something stops working, or to change the account."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { showAi = !showAi }) {
                    Text(if (showAi) "Hide the AI details" else "Change the AI provider")
                }
            }

            if (showAi) {
            SpendSection()

            SectionCard("Which provider runs first") {
                Text(
                    "Questions and scans go to the first provider that has a key, and fall back to the other when it is rate-limited or fails. The batch queue spreads frames across every provider with a key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AiProvider.entries.forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { makePrimary(provider) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = primaryProvider == provider,
                            onClick = { makePrimary(provider) }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(provider.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (AppGraph.settings.key(provider).isNotBlank()) {
                                    "key saved"
                                } else {
                                    "no key yet"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            AiProvider.entries.forEach { provider ->
                ProviderCard(
                    provider = provider,
                    isPrimary = primaryProvider == provider,
                    draft = drafts[provider] ?: ProviderDraft(),
                    revealed = revealedProvider == provider,
                    testing = testingProvider == provider,
                    result = testResults[provider],
                    onDraftChange = { drafts = drafts + (provider to it) },
                    onToggleReveal = {
                        revealedProvider = if (revealedProvider == provider) null else provider
                    },
                    onSave = {
                        saveProvider(provider)
                        scope.launch { snackbarHostState.showSnackbar("${provider.shortLabel} saved") }
                    },
                    onCopy = {
                        val key = (drafts[provider] ?: ProviderDraft()).key
                        if (key.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar("No key to copy") }
                        } else {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("${provider.shortLabel} API key", key))
                            scope.launch { snackbarHostState.showSnackbar("Key copied — paste it in the other app") }
                        }
                    },
                    onTest = { testProvider(provider) },
                    onOpenKeyPage = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl)))
                        }
                    }
                )
            }
            }

            SectionCard("Currency") {
                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it.uppercase().take(4) },
                    label = { Text("ISO code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        AppGraph.settings.currency = currency
                        scope.launch { snackbarHostState.showSnackbar("Currency saved") }
                    }
                ) {
                    Text("Save")
                }
            }

            SectionCard("Fill in the details") {
                val pending = DetailsBackfill.pending(data.wines).size
                val withNotes = data.wines.count { it.notesCheckedAt > 0L }
                val withAbv = data.wines.count { it.alcohol != null }
                val missing = data.wines.count { it.alcohol == null || it.notesCheckedAt == 0L }
                val rpm = AppGraph.settings.requestsPerMinute(primaryProvider).coerceAtLeast(1)
                val minutes = (pending * 2 + rpm - 1) / rpm
                val hasKey = AiProvider.entries.any { AppGraph.settings.key(it).isNotBlank() }
                Text(
                    "$withNotes of ${data.wines.size} bottles carry notes read from published pages, " +
                        "and $withAbv record an ABV. One pass fills in whatever is missing — a " +
                        "paragraph about the wine, its flavour words, and the alcohol figure the " +
                        "gates cannot work without — from pages somebody published, never from " +
                        "guesswork. Three bottles run at a time, and a lookup that does not answer " +
                        "is asked again next time rather than being written off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pending > 0) {
                    Text(
                        "$pending bottles still have something missing — about $minutes minutes. " +
                            "Keep the app open; it can be stopped and carried on.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else if (missing > 0) {
                    Text(
                        "$missing bottles were looked up and a fact is still missing from the " +
                            "pages. A page published since can turn an unknown into a number, so " +
                            "they can be asked again.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (fill.running) {
                    Text(
                        "${(fill.done + 1).coerceAtMost(fill.total)} of ${fill.total} · ${fill.current}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { AppGraph.details.cancel() }) { Text("Stop") }
                } else {
                    OutlinedButton(
                        // With nothing new to do, the button still has a job: the bottles whose
                        // pages printed nothing can be asked again, once they have been looked at.
                        onClick = { AppGraph.details.start(again = pending == 0) },
                        enabled = (pending > 0 || missing > 0) && hasKey
                    ) {
                        Text(
                            when {
                                !hasKey -> "Add an API key first"
                                pending > 0 -> "Fill in $pending bottles"
                                missing > 0 -> "Look again at $missing bottles"
                                else -> "Every bottle is filled in"
                            }
                        )
                    }
                }
                fill.message?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard("Storage locations") {
                data.locations.forEach { location ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(location.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${data.wines.count { it.locationId == location.id }} wines",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                renameTarget = location
                                renameText = location.name
                            }
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Rename")
                        }
                        IconButton(
                            onClick = { scope.launch { AppGraph.cellar.deleteLocation(location.id) } }
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newLocationName,
                        onValueChange = { newLocationName = it },
                        label = { Text("New location") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val name = newLocationName
                            scope.launch {
                                AppGraph.cellar.addLocation(name)
                                newLocationName = ""
                            }
                        }
                    ) {
                        Text("Add")
                    }
                }
            }

            SectionCard("Advanced") {
                Text(
                    "Edit mytaste.md directly. It is the file the model judges wine against, and most people never need to open it: the quick start writes it from ten taps and the Taste tab shows what it knows. A file you write or import here is kept when the quick start runs again; one the quick start wrote is replaced.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onOpenAdvancedTaste) {
                    Text("Edit mytaste.md")
                }
                // Debug builds show the scan panel unconditionally; this is the switch that makes
                // it readable on a sideloaded release build, where there is no log to pull.
                var showScanDetails by remember { mutableStateOf(AppGraph.settings.showScanDetails) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show scan details", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "After a scan, show what was read, which provider and model answered each request, the raw judgement and its length, and whether the cards came from the model or the price fallback. Held in memory only.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showScanDetails,
                        onCheckedChange = {
                            showScanDetails = it
                            AppGraph.settings.showScanDetails = it
                        }
                    )
                }
            }

            SectionCard("Your data") {
                Text(
                    "Cellar, tasting notes and mytaste.md are stored in this app's private files on the phone. Photos are sent only to the provider whose key you pasted, only when you tap Analyse or Send. There is no account and no server of ours.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { confirmClearChat = true }) {
                    Text("Clear chat history")
                }
            }

            SectionCard("Backup and import") {
                Text(
                    "Your cellar lives in this app's private files, so keep a copy somewhere else. A backup is one JSON file with every wine, note and location plus your mytaste.md — no API key is ever included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { exportLauncher.launch(Backup.suggestedFileName()) },
                        enabled = !busy
                    ) {
                        Text("Export backup")
                    }
                    OutlinedButton(
                        onClick = {
                            restoreLauncher.launch(
                                arrayOf("application/json", "text/plain", "application/octet-stream")
                            )
                        },
                        enabled = !busy
                    ) {
                        Text("Restore")
                    }
                }
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(
                            arrayOf(
                                "text/csv",
                                "text/comma-separated-values",
                                "text/plain",
                                "application/vnd.ms-excel",
                                "application/octet-stream"
                            )
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import a CSV (CellarTracker, Vivino, any spreadsheet)")
                }
                Text(
                    "CellarTracker: on the desktop site open My Cellar → Individual Bottles → Export and tick every column first. Any CSV whose headings include Wine, Producer, Vintage or Quantity works; duplicates are matched on producer, name and vintage, so re-importing is harmless.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (busy) CircularProgressIndicator()
                dataMessage?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard("Updates") {
                Text(
                    "Wine Verdict updates itself from this project's GitHub releases. Opening Settings asks " +
                        "GitHub once whether a newer one exists; the APK is downloaded only when you tap, " +
                        "and Android's own installer does the rest. Your cellar, keys and mytaste.md stay " +
                        "where they are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Installed: Wine Verdict ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall
                )
                when (val state = update) {
                    UpdateUi.Idle, UpdateUi.Checking -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Checking GitHub…", style = MaterialTheme.typography.bodySmall)
                    }

                    UpdateUi.UpToDate -> Text(
                        "This is the newest release.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )

                    is UpdateUi.Available -> {
                        Text(
                            "Wine Verdict ${state.release.version} is available.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        val notes = state.release.notes
                            .lines()
                            .filter { it.isNotBlank() }
                            .take(4)
                            .joinToString("\n")
                            .take(400)
                        if (notes.isNotBlank()) {
                            Text(
                                notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = { downloadUpdate(state.release) }) {
                            Text("Download and install")
                        }
                    }

                    is UpdateUi.Downloading -> {
                        Text(
                            "Downloading ${state.release.fileName} — ${(updateProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                        LinearProgressIndicator(
                            progress = { updateProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is UpdateUi.Ready -> {
                        Text(
                            "WineVerdict ${state.release.version} is downloaded. Finish in Android's " +
                                "installer, which opened behind this screen.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        updateApk?.let { apk ->
                            OutlinedButton(onClick = { installUpdate(apk) }) { Text("Install again") }
                        }
                    }

                    is UpdateUi.Failed -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                OutlinedButton(
                    onClick = { checkForUpdates() },
                    enabled = update !is UpdateUi.Checking && update !is UpdateUi.Downloading
                ) {
                    Text("Check for updates")
                }
            }

            SectionCard("About") {
                Text(
                    "WineVerdict ${BuildConfig.VERSION_NAME} — a local cellar notebook with an AI sommelier on the side.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Expert notes and drinking windows produced by the model are estimates from general knowledge, not published critic reviews. Treat them as a starting point.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename location") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { AppGraph.cellar.renameLocation(target.id, renameText) }
                        renameTarget = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmClearChat) {
        AlertDialog(
            onDismissRequest = { confirmClearChat = false },
            title = { Text("Clear chat history?") },
            text = { Text("The conversation is deleted. Your cellar and taste file are untouched.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppGraph.chat.clear()
                        confirmClearChat = false
                    }
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearChat = false }) { Text("Cancel") }
            }
        )
    }

    restoreCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { restoreCandidate = null },
            title = { Text("Restore backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Exported ${formatDay(candidate.exportedAt)}" +
                            if (candidate.appVersion.isBlank()) "." else " by WineVerdict ${candidate.appVersion}."
                    )
                    Text(
                        "${candidate.cellar.wines.size} wines · ${candidate.bottles} bottles · " +
                            "${candidate.cellar.locations.size} storage locations"
                    )
                    if (candidate.taste.isNotBlank()) Text("Includes the mytaste.md from that phone.")
                    Text("Merge keeps what is on this phone and matches duplicates. Replace deletes it first.")
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val outcome = AppGraph.cellar.mergeCellar(candidate.cellar)
                                if (candidate.taste.isNotBlank()) AppGraph.taste.write(candidate.taste)
                                if (candidate.currency.isNotBlank()) {
                                    AppGraph.settings.currency = candidate.currency
                                    currency = candidate.currency
                                }
                                dataMessage = "Merged: ${outcome.added} new wines, ${outcome.updated} matched, " +
                                    "${outcome.locationsAdded} locations added."
                                restoreCandidate = null
                            }
                        }
                    ) { Text("Merge") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                AppGraph.cellar.replaceAll(candidate.cellar)
                                if (candidate.taste.isNotBlank()) AppGraph.taste.write(candidate.taste)
                                if (candidate.currency.isNotBlank()) {
                                    AppGraph.settings.currency = candidate.currency
                                    currency = candidate.currency
                                }
                                dataMessage = "Restored ${candidate.cellar.wines.size} wines from the backup."
                                restoreCandidate = null
                            }
                        }
                    ) { Text("Replace") }
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreCandidate = null }) { Text("Cancel") }
            }
        )
    }

    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text("Import ${preview.distinctWines} wines?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${preview.distinctWines} wines · ${preview.bottles} bottles from ${preview.source}.")
                    if (preview.locationNames.isNotEmpty()) {
                        Text(
                            "Storage locations: ${preview.locationNames.joinToString(", ").take(140)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    preview.rows.take(5).forEach { row ->
                        Text("· ${row.wine.title}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (preview.rows.size > 5) {
                        Text("…and ${preview.rows.size - 5} more.", style = MaterialTheme.typography.bodySmall)
                    }
                    preview.notes.forEach { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "Wines already in the cellar keep their notes and the higher bottle count.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val outcome = AppGraph.cellar.mergeRows(preview.rows)
                            dataMessage = "Imported ${outcome.added} wines · ${outcome.updated} matched existing " +
                                "bottles · ${outcome.locationsAdded} locations added."
                            importPreview = null
                        }
                    }
                ) { Text("Add to cellar") }
            },
            dismissButton = {
                TextButton(onClick = { importPreview = null }) { Text("Cancel") }
            }
        )
    }
}

private data class ProviderDraft(
    val key: String = "",
    val baseUrl: String = "",
    val vision: String = "",
    val text: String = "",
    val advice: String = "",
    val rpm: String = ""
)

private data class TestResult(val ok: Boolean, val message: String)

private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val release: Updater.Release) : UpdateUi
    data class Downloading(val release: Updater.Release) : UpdateUi
    data class Ready(val release: Updater.Release) : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

private fun draftOf(settings: SettingsStore, provider: AiProvider): ProviderDraft = ProviderDraft(
    key = settings.key(provider),
    baseUrl = settings.baseUrl(provider),
    vision = settings.visionModels(provider).joinToString(", "),
    text = settings.textModels(provider).joinToString(", "),
    advice = settings.adviceModels(provider).joinToString(", "),
    rpm = settings.requestsPerMinute(provider).toString()
)

/** Amber, for "nearly at the cap": loud enough to read, not the red of "refused". */
private val WarnAmber = Color(0xFFB26A00)

/**
 * The month's spend, counted from what the providers said each answer cost.
 *
 * It sits first in Settings because it is the one number that answers "what is this costing me?",
 * and because the cap it enforces is silent until it fires: a warning at 80% is the only chance the
 * reader gets to raise the limit before a scan is refused.
 */
@Composable
private fun SpendSection() {
    var tick by remember { mutableStateOf(0) }
    var limitText by remember { mutableStateOf(centsText(AppGraph.settings.spend.limitCents)) }
    // Other screens add to the meter while this one is open (a batch scan, a backfill of notes),
    // so the figure is re-read rather than frozen at the moment Settings was opened.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }
    val total = remember(tick) { AppGraph.settings.spend.total() }

    SectionCard("Spend this month") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${total.spent} of ${total.limit}",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "${total.percent}%",
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    total.blocked -> MaterialTheme.colorScheme.error
                    total.warning -> WarnAmber
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        LinearProgressIndicator(
            progress = { total.fraction },
            modifier = Modifier.fillMaxWidth(),
            color = when {
                total.blocked -> MaterialTheme.colorScheme.error
                total.warning -> WarnAmber
                else -> MaterialTheme.colorScheme.primary
            }
        )
        Text(
            when {
                total.blocked ->
                    "The limit is used, so nothing more is sent until ${total.month.plusMonths(1)} " +
                        "or until you raise it here."
                total.warning ->
                    "${SpendMeter.WARN_PERCENT}% used — ${total.remaining} left this month. " +
                        "Requests are refused once it is gone."
                total.limitNanos == 0L ->
                    "No limit set. Scans and questions are sent whatever this month has cost."
                else ->
                    "${total.remaining} left this month. A label scan is about a third of a cent; " +
                        "the meter counts the tokens each answer reports, at the model's listed price."
            },
            style = MaterialTheme.typography.bodySmall,
            color = when {
                total.blocked -> MaterialTheme.colorScheme.error
                total.warning -> WarnAmber
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Text(
            "${thousands(total.inputTokens)} tokens in · ${thousands(total.outputTokens)} out",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        OutlinedTextField(
            value = limitText,
            onValueChange = { typed ->
                limitText = typed.filter { it.isDigit() || it == '.' }.take(7)
            },
            label = { Text("Monthly limit (\$)") },
            supportingText = { Text("0 means no limit") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val cents = ((limitText.toDoubleOrNull() ?: 0.0)
                    .coerceIn(0.0, SpendMeter.MAX_LIMIT_CENTS / 100.0) * 100).roundToInt()
                AppGraph.settings.spend.limitCents = cents
                limitText = centsText(cents)
                tick++
            }
        ) {
            Text("Save limit")
        }
    }
}

private fun centsText(cents: Int): String = String.format(Locale.US, "%.2f", cents / 100.0)

private fun thousands(value: Long): String = String.format(Locale.US, "%,d", value)

@Composable
private fun ProviderCard(
    provider: AiProvider,
    isPrimary: Boolean,
    draft: ProviderDraft,
    revealed: Boolean,
    testing: Boolean,
    result: TestResult?,
    onDraftChange: (ProviderDraft) -> Unit,
    onToggleReveal: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onTest: () -> Unit,
    onOpenKeyPage: () -> Unit
) {
    SectionCard(if (isPrimary) "${provider.label} — primary" else provider.label) {
        Text(
            provider.notes,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = draft.key,
            onValueChange = { onDraftChange(draft.copy(key = it)) },
            label = { Text("API key (${provider.keyHint})") },
            singleLine = true,
            visualTransformation = if (revealed) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onToggleReveal) {
                    Icon(
                        imageVector = if (revealed) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = "Show or hide the key"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave) { Text("Save") }
            OutlinedButton(onClick = onCopy) { Text("Copy key") }
            OutlinedButton(onClick = onTest, enabled = !testing) {
                Text(if (testing) "Testing…" else "Test connection")
            }
            TextButton(onClick = onOpenKeyPage) { Text("Get a key") }
        }
        if (testing) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        result?.let { test ->
            Text(
                test.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (test.ok) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
        OutlinedTextField(
            value = draft.baseUrl,
            onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.vision,
            onValueChange = { onDraftChange(draft.copy(vision = it)) },
            label = { Text("Vision models") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.text,
            onValueChange = { onDraftChange(draft.copy(text = it)) },
            label = { Text("Chat models") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.advice,
            onValueChange = { onDraftChange(draft.copy(advice = it)) },
            label = { Text("Advice model") },
            supportingText = {
                Text("What answers a wine list and the chat: it has to reason about a whole page, not read a label. Empty falls back to the chat models above.")
            },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.rpm,
            onValueChange = { onDraftChange(draft.copy(rpm = it.filter { ch -> ch.isDigit() })) },
            label = { Text("Requests per minute") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Model ids are comma separated. The queue reads one frame per request and rotates through every model of every configured provider. Keys come from ${provider.keyPageHint}.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

private fun formatDay(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()