package com.wineverdict.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.wineverdict.app.model.DrinkStatus
import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.drinkStatus
import com.wineverdict.app.model.formatMoney
import com.wineverdict.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun UriImage(
    uri: Uri?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = uri?.let {
            runCatching {
                withContext(Dispatchers.IO) { ImageUtils.previewBitmap(context, it) }
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun DrinkBadge(status: DrinkStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val background = when (status) {
        DrinkStatus.PEAK -> scheme.primaryContainer
        DrinkStatus.READY -> scheme.secondaryContainer
        DrinkStatus.HOLD -> scheme.surfaceVariant
        DrinkStatus.PAST -> scheme.errorContainer
        DrinkStatus.NONE -> scheme.surfaceVariant
    }
    val foreground = when (status) {
        DrinkStatus.PEAK -> scheme.onPrimaryContainer
        DrinkStatus.READY -> scheme.onSecondaryContainer
        DrinkStatus.PAST -> scheme.onErrorContainer
        else -> scheme.onSurfaceVariant
    }
    Surface(color = background, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            text = status.label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = foreground
        )
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Surface(
        color = color ?: MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The four words, one tap each. A five-star count asked for a precision the app never read — every
 * threshold treated the number as a bucket — and an unlabelled scale made 1★ and 2★ the same
 * answer without saying so. Tapping the chosen word again clears it, which is how a bottle is
 * unrated. Words wrap rather than shrink: "Not for me" is the longest and chip text does not
 * ellipsise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VerdictChips(rating: Int?, modifier: Modifier = Modifier, onRating: (Int?) -> Unit) {
    val current = Verdict.of(rating)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Verdict.values().forEach { verdict ->
            val chosen = verdict == current
            FilterChip(
                selected = chosen,
                onClick = { onRating(if (chosen) null else verdict.rating) },
                label = { Text(verdict.label) },
                shape = RoundedCornerShape(50),
                // The chosen word is the claret stamp, exactly as the filters read.
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = chosen,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

/**
 * What was it like — the shared second half of **Drank one**.
 *
 * The bottle has already left the inventory by the time this opens, on the cellar's card and on the
 * bottle's own page alike: that tap is the fact, and the count must not wait on a dialog nobody may
 * finish. What is asked here is the part only the drinker knows — the four words and one line — and
 * closing it says nothing about the bottle, which is an answer too: it was drunk, and that is
 * recorded.
 */
@Composable
fun DrankDialog(wine: Wine, onSave: (Int?, String) -> Unit, onClose: () -> Unit) {
    var rating by remember(wine.id) { mutableStateOf(wine.rating) }
    var note by remember(wine.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("How was it?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(wine.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            wine.quantity <= 0 -> "The last bottle is out of the cellar."
                            wine.quantity == 1 -> "One bottle left in the cellar."
                            else -> "${wine.quantity} bottles left in the cellar."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // The same four words the bottle page and the editor offer, so the answer given
                // here is the answer every filter, sort and brief already reads.
                VerdictChips(rating) { rating = it }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("One line about it") },
                    placeholder = { Text("Strawberry, cold, worth it") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(rating, note) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Skip") }
        }
    )
}

@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        // A hairline, not a shadow: the cards are pages of one ledger instead of floating tiles.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

@Composable
fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1.2f)
        )
    }
}

@Composable
fun WineRow(
    wine: Wine,
    locationName: String,
    onClick: () -> Unit,
    onDrank: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = wine.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (wine.favorite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Loved",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            Text(
                text = wine.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Pill(if (wine.quantity > 0) "${wine.quantity} btl" else "none left")
                Text(
                    text = locationName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DrinkBadge(wine.drinkStatus())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (wine.quantity > 0 && wine.price != null) {
                        "Paid ${formatMoney(wine.price, wine.currency)}"
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                // The one thing done to a bottle often enough to earn a tap on the list itself.
                // Opening the bottle is the fact and the verdict is the follow-up, so the count
                // moves first and the question comes after, where it can be skipped.
                if (onDrank != null && wine.quantity > 0) {
                    TextButton(
                        onClick = onDrank,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text("Drank one", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun FilterChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(options) { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option) },
                shape = RoundedCornerShape(50),
                // The chosen filter is the claret stamp; the rest are ruled outlines on paper.
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = option == selected,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
fun AiDisclaimer() {
    Text(
        text = "Written by the AI model from general wine knowledge, not a published critic. Check it before you trust it.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline
    )
}