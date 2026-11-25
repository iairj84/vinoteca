package com.vinoteca.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinoteca.app.AppGraph
import com.vinoteca.app.ScanHandoff
import com.vinoteca.app.model.Wine
import com.vinoteca.app.model.WineType
import com.vinoteca.app.model.locationName
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WineEditorScreen(wineId: String?, onDone: () -> Unit) {
    val data by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val existing = remember(wineId, data.wines) {
        wineId?.let { id -> data.wines.firstOrNull { it.id == id } }
    }
    val initial = remember { existing ?: ScanHandoff.take() ?: Wine(currency = AppGraph.settings.currency) }
    val isNew = existing == null
    val scope = rememberCoroutineScope()

    var producer by remember { mutableStateOf(initial.producer) }
    var name by remember { mutableStateOf(initial.name) }
    var vintage by remember { mutableStateOf(initial.vintage?.toString().orEmpty()) }
    var type by remember { mutableStateOf(initial.type) }
    var country by remember { mutableStateOf(initial.country) }
    var region by remember { mutableStateOf(initial.region) }
    var appellation by remember { mutableStateOf(initial.appellation) }
    var grapes by remember { mutableStateOf(initial.grapes.joinToString(", ")) }
    var alcohol by remember { mutableStateOf(initial.alcohol?.toString().orEmpty()) }
    var barcode by remember { mutableStateOf(initial.barcode) }
    var quantity by remember { mutableStateOf(initial.quantity) }
    var locationId by remember { mutableStateOf(initial.locationId) }
    var slot by remember { mutableStateOf(initial.slot) }
    var price by remember { mutableStateOf(initial.price?.toString().orEmpty()) }
    var currency by remember { mutableStateOf(initial.currency) }
    var drinkFrom by remember { mutableStateOf(initial.drinkFrom?.toString().orEmpty()) }
    var drinkTo by remember { mutableStateOf(initial.drinkTo?.toString().orEmpty()) }
    var expertNotes by remember { mutableStateOf(initial.expertNotes) }
    var myNotes by remember { mutableStateOf(initial.myNotes) }
    var favorite by remember { mutableStateOf(initial.favorite) }
    var rating by remember { mutableStateOf(initial.rating) }
    var locationMenuOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val cleanProducer = producer.trim()
        val cleanName = name.trim()
        if (cleanProducer.isBlank() && cleanName.isBlank()) {
            error = "Give the bottle at least a producer or a wine name."
            return
        }
        val updated = initial.copy(
            producer = cleanProducer,
            name = cleanName,
            vintage = vintage.trim().toIntOrNull(),
            type = type,
            country = country.trim(),
            region = region.trim(),
            appellation = appellation.trim(),
            grapes = grapes.split(',', ';').map { it.trim() }.filter { it.isNotBlank() },
            alcohol = alcohol.trim().replace(',', '.').toDoubleOrNull(),
            barcode = barcode.trim(),
            quantity = quantity.coerceAtLeast(0),
            locationId = locationId,
            slot = slot.trim(),
            price = price.trim().replace(',', '.').toDoubleOrNull(),
            currency = currency.trim().uppercase().ifBlank { "USD" },
            drinkFrom = drinkFrom.trim().toIntOrNull(),
            drinkTo = drinkTo.trim().toIntOrNull(),
            expertNotes = expertNotes.trim(),
            myNotes = myNotes.trim(),
            rating = rating,
            favorite = favorite
        )
        scope.launch {
            AppGraph.cellar.upsert(updated)
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New wine" else "Edit wine") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { save() }) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isNew && initial.source != "manual") {
                Card(
                    text = "Drafted from a photo. Check every field before saving — the model can misread a label."
                )
            }

            OutlinedTextField(
                value = producer,
                onValueChange = { producer = it },
                label = { Text("Producer *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Wine name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = vintage,
                    onValueChange = { vintage = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Vintage") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = alcohol,
                    onValueChange = { alcohol = it },
                    label = { Text("ABV %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }

            Text("Style", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                WineType.entries.forEach { option ->
                    FilterChip(
                        selected = option == type,
                        onClick = { type = option },
                        label = { Text(option.label) }
                    )
                }
            }

            OutlinedTextField(
                value = country,
                onValueChange = { country = it },
                label = { Text("Country") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = region,
                onValueChange = { region = it },
                label = { Text("Region") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = appellation,
                onValueChange = { appellation = it },
                label = { Text("Appellation / vineyard") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = grapes,
                onValueChange = { grapes = it },
                label = { Text("Grapes (comma separated)") },
                placeholder = { Text("Nebbiolo, Barbera") },
                modifier = Modifier.fillMaxWidth()
            )

            Text("Storage", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { locationMenuOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(data.locationName(locationId), maxLines = 1)
                    }
                    DropdownMenu(
                        expanded = locationMenuOpen,
                        onDismissRequest = { locationMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Unassigned") },
                            onClick = {
                                locationId = null
                                locationMenuOpen = false
                            }
                        )
                        data.locations.forEach { location ->
                            DropdownMenuItem(
                                text = { Text(location.name) },
                                onClick = {
                                    locationId = location.id
                                    locationMenuOpen = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = slot,
                    onValueChange = { slot = it },
                    label = { Text("Slot") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bottles", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = { quantity = (quantity - 1).coerceAtLeast(0) }) {
                    Icon(Icons.Filled.Remove, contentDescription = "Fewer")
                }
                Text(
                    text = "$quantity",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                FilledTonalIconButton(onClick = { quantity += 1 }) {
                    Icon(Icons.Filled.Add, contentDescription = "More")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Price paid") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it.uppercase().take(4) },
                    label = { Text("Currency") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Text("Drinking window", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = drinkFrom,
                    onValueChange = { drinkFrom = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("From") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = drinkTo,
                    onValueChange = { drinkTo = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("To") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Loved bottle", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Feeds your mytaste.md profile",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = favorite, onCheckedChange = { favorite = it })
            }
            RatingStars(rating) { rating = it }

            OutlinedTextField(
                value = expertNotes,
                onValueChange = { expertNotes = it },
                label = { Text("Expert / reference notes") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = myNotes,
                onValueChange = { myNotes = it },
                label = { Text("My tasting notes") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = barcode,
                onValueChange = { barcode = it },
                label = { Text("Barcode") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (error != null) {
                Text(
                    error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isNew) "Add to cellar" else "Save changes")
            }
            Spacer(Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun Card(text: String) {
    com.vinoteca.app.ui.SectionCard(title = null) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}