package com.wineverdict.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wineverdict.app.AppGraph
import com.wineverdict.app.data.HereResult
import com.wineverdict.app.data.LatLng
import com.wineverdict.app.data.PLACE_RADIUS_CHOICES
import com.wineverdict.app.data.PLACE_RADIUS_M
import com.wineverdict.app.data.Place
import com.wineverdict.app.data.PlaceHit
import com.wineverdict.app.data.WhereAmI
import com.wineverdict.app.data.radiusWords
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Where a night happened: typed, tagged, or picked from the places already known.
 *
 * The three ways in are one box because they are one answer. Typing "Loyal L" offers the branches by
 * name and town; the location icon answers with Home when the fix is inside a circle the user drew,
 * and with a business when it is standing in one; the saved places are one tap each. Every path ends
 * in the same string, which is what the book, the search and the backup already hold.
 *
 * Nothing here is ever forced. A refused permission, a flat battery of a GPS, a throttled lookup and
 * a place that is not in OpenStreetMap all end the same way: the box takes what is typed, which is
 * exactly what it did before any of this existed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Where (optional)",
    placeholder: String = "A restaurant, a friend's house…"
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by AppGraph.places.places.collectAsStateWithLifecycle()
    var hits by remember { mutableStateOf<List<PlaceHit>>(emptyList()) }
    // Only a keystroke searches. A picked name or a prefilled night must not fire a lookup at the
    // server the moment the sheet opens.
    var typing by remember { mutableStateOf(false) }
    var near by remember { mutableStateOf<LatLng?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    var saveAt by remember { mutableStateOf<LatLng?>(null) }
    // Once per sheet: a refusal is not asked again for this night's place, and a grant is not asked
    // for at all.
    var locatedAsked by remember { mutableStateOf(false) }
    // Bumped when a fix arrives after the permission dialog, so the search that ran while the
    // question was on screen is asked again with somewhere to be near.
    var fixTick by remember { mutableStateOf(0) }
    // Which of the two taps asked for the permission: the icon (name where I am) or a keystroke
    // (find places nearby). The grant does a different thing for each.
    var grantForIcon by remember { mutableStateOf(false) }

    fun locate() {
        scope.launch {
            locating = true
            note = null
            val fix = WhereAmI.fix(context)
            near = fix
            when (val found = AppGraph.placeFinder.here(fix?.lat, fix?.lng)) {
                is HereResult.Saved -> {
                    typing = false
                    hits = emptyList()
                    onValueChange(found.place.name)
                    note = "Tagged ${found.place.name} — you are inside its circle."
                }

                is HereResult.Business -> {
                    typing = false
                    hits = emptyList()
                    onValueChange(found.hit.label)
                    note = "Tagged ${found.hit.label}."
                }

                HereResult.NothingKnown -> {
                    note = "No named place here. Save this spot so the next night tags itself."
                    saveAt = fix
                }

                HereResult.NoFix -> {
                    note = if (WhereAmI.allowed(context)) {
                        "No location fix — type the place instead."
                    } else {
                        "Location is off for this app, so type the place instead."
                    }
                }
            }
            locating = false
        }
    }

    val askLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            note = "Location is off, so this search is not nearby. Allow it in Android's settings, " +
                "or type the whole name."
            return@rememberLauncherForActivityResult
        }
        if (grantForIcon) {
            locate()
        } else {
            // Typing asked. The words stay as they are; the list is asked again from the fix.
            scope.launch {
                near = WhereAmI.fix(context)
                // Said rather than left as "asking…": a phone with no fix would otherwise sit
                // there looking like it was still trying.
                if (near == null) {
                    note = "No location fix, so this search is not nearby. Type the whole name."
                }
                fixTick++
            }
        }
    }

    // A pause, not a keystroke: Photon answers in about a second, and firing on every letter would
    // send five requests for one word.
    LaunchedEffect(value, typing, fixTick) {
        if (!typing || value.trim().length < 2) {
            hits = emptyList()
            return@LaunchedEffect
        }
        delay(450)
        // Half a name searched from nowhere finds a town in Kentucky, not the branch down the road:
        // the town is what tells four Loyal Legions apart. So the first search in this sheet is what
        // asks the phone where it is — the icon names where you are standing, and nobody types
        // "Loyal L" wanting an address three states away. Asked once, and a refusal leaves the
        // search exactly as it was.
        if (near == null && !locatedAsked) {
            locatedAsked = true
            if (WhereAmI.allowed(context)) {
                near = WhereAmI.fix(context)
                if (near == null) {
                    note = "No location fix, so this search is not nearby. Type the whole name."
                }
            } else {
                grantForIcon = false
                note = "Asking where you are, so the nearest branches come first…"
                askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        hits = runCatching { AppGraph.placeFinder.suggestions(value, near) }
            .getOrDefault(emptyList())
        // The question has been answered by the list itself.
        if (hits.isNotEmpty()) note = null
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                typing = true
                note = null
                onValueChange(it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (WhereAmI.allowed(context)) {
                            locate()
                        } else {
                            grantForIcon = true
                            askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
                    enabled = !locating
                ) {
                    if (locating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.MyLocation, contentDescription = "Tag where I am")
                    }
                }
            }
        )

        if (hits.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column {
                    hits.forEachIndexed { index, hit ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Merged before the tap, so a screen reader reads the name and the
                                // street as one row rather than two separate stops.
                                .semantics(mergeDescendants = true) {}
                                .clickable {
                                    typing = false
                                    hits = emptyList()
                                    note = null
                                    onValueChange(hit.label)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(hit.label, style = MaterialTheme.typography.bodyMedium)
                            // An address answered by the fallback is already the label; printing it
                            // twice would read as two places.
                            if (hit.street.isNotBlank() && hit.street != hit.label) {
                                Text(
                                    hit.street,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (saved.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                saved.take(8).forEach { place ->
                    AssistChip(
                        onClick = {
                            typing = false
                            hits = emptyList()
                            note = null
                            onValueChange(place.name)
                        },
                        label = { Text(place.name) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Place,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }

        note?.let { said ->
            Text(
                said,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }

    saveAt?.let { point ->
        PlaceDialog(
            initial = null,
            lat = point.lat,
            lng = point.lng,
            onSave = { place ->
                AppGraph.places.save(place)
                typing = false
                onValueChange(place.name)
                note = "Saved ${place.name}. A night here is tagged with it next time."
                saveAt = null
            },
            onDismiss = { saveAt = null }
        )
    }
}

/**
 * Naming a spot, or re-cutting the circle around one already named.
 *
 * The radius is a short list rather than a slider: the question is "how far still counts as here",
 * and the answers people give are a doorstep, a house, a block, a venue. Anything wider stops being
 * a place and starts being a neighbourhood.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceDialog(
    initial: Place?,
    lat: Double,
    lng: Double,
    onSave: (Place) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var radius by remember(initial?.id) { mutableStateOf(initial?.radiusM ?: PLACE_RADIUS_M) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Save this spot" else "Edit this place") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") },
                    placeholder = { Text("Home, Mum's, the pub…") }
                )
                Text("How far from the pin still counts as here", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PLACE_RADIUS_CHOICES.forEach { metres ->
                        FilterChip(
                            selected = radius == metres,
                            onClick = { radius = metres },
                            label = { Text(radiusWords(metres)) }
                        )
                    }
                }
                Text(
                    "A circle of about ${radiusWords(radius)} around the spot.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        Place(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name,
                            lat = lat,
                            lng = lng,
                            radiusM = radius
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
