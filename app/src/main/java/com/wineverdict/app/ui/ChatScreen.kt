package com.wineverdict.app.ui

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wineverdict.app.AppGraph
import com.wineverdict.app.ai.AiException
import com.wineverdict.app.data.DeviceRegions
import com.wineverdict.app.ai.AiJob
import com.wineverdict.app.ai.ProposalParsing
import com.wineverdict.app.ai.PlaceScout
import com.wineverdict.app.ai.Prompts
import com.wineverdict.app.ai.Thinking
import com.wineverdict.app.ai.chatBudget
import com.wineverdict.app.data.PairingTable
import com.wineverdict.app.ai.chatHistory
import com.wineverdict.app.model.BottleUpdate
import com.wineverdict.app.model.ChatMessage
import com.wineverdict.app.model.MoveRequest
import com.wineverdict.app.data.describe
import com.wineverdict.app.data.plan
import com.wineverdict.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val suggestions = listOf(
    "What should I open with chicken pot pie tonight?",
    "Which of my bottles are at their best right now?",
    "Where should I go for dinner near me with good wine?",
    "Something under 25 for a Tuesday, from my cellar",
    "I am at the shop — is this bottle worth it?"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by AppGraph.chat.messages.collectAsStateWithLifecycle()
    val cellar by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Reading your cellar…") }
    var error by remember { mutableStateOf<String?>(null) }

    var cameraTarget by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) cameraTarget?.let { pendingUri = it }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) pendingUri = uri
    }

    val send: (String) -> Unit = { raw ->
        if (!sending) {
            val text = raw.trim()
            val photo = pendingUri
            if (text.isNotBlank() || photo != null) {
                sending = true
                status = "Reading your cellar…"
                error = null
                input = ""
                pendingUri = null
                scope.launch {
                    try {
                        val base64 = photo?.let {
                            withContext(Dispatchers.IO) {
                                Base64.encodeToString(ImageUtils.jpegBytes(context, it), Base64.NO_WRAP)
                            }
                        }
                        val userMessage = ChatMessage(
                            role = "user",
                            text = text.ifBlank { "What can you tell me about this?" },
                            imageBase64 = base64
                        )
                        AppGraph.chat.append(userMessage)
                        // Somewhere to go is a question about the world, and the world is not in
                        // mytaste.md: it is on pages fetched now. This branch answers it from those
                        // pages and never from the model's memory, which is what used to send the
                        // reader to a restaurant that closed two years earlier.
                        val ask = if (photo == null) PlaceScout.ask(text) else null
                        if (ask != null) {
                            status = "Looking up ${ask.subject} in " +
                                "${ask.place.ifBlank { "your area" }}…"
                            // The phone's own region settles a bare town name, so the question the
                            // user typed works: "Portland" becomes Portland, Oregon when the phone
                            // says Oregon, and comes back as a question when it cannot say.
                            val region = DeviceRegions.of(context, AppGraph.placeFinder)
                            // A lookup that is refused is an answer too: "cannot check right now"
                            // belongs in the conversation, not in the red error card, and it must
                            // never turn into a place named from memory. The branch is inside the
                            // production call so the live run exercises the same text.
                            val reply = AppGraph.ai.scoutReply(
                                ask,
                                AppGraph.taste.brief(),
                                region
                            )
                            AppGraph.chat.append(ChatMessage(role = "assistant", text = reply))
                            return@launch
                        }
                        // Sized from the thinnest provider that could answer, not the one that
                        // leads: the fallback gets the same body, and a wide window is a 413 on
                        // Groq rather than an answer.
                        val budget = chatBudget(AppGraph.settings.configuredProviders)
                        // A window closed on tokens, not on a count of messages: the previous
                        // answer is what the repetition rule needs, and twelve long answers were
                        // still more than the fallback's minute can carry. Only the newest
                        // message keeps its photo.
                        val history = chatHistory(
                            AppGraph.chat.messages.value
                                .map { if (it.id == userMessage.id) it else it.copy(imageBase64 = null) },
                            budgetTokens = budget.historyTokens
                        )
                        // The dish decides whether a 14.2% bottle passes with the food, so it has
                        // to reach the gate check and not only the prose. The question wins, then
                        // the dish named earlier in this conversation (a follow-up like "what
                        // about the second bottle" is still the same dinner), then the food habit.
                        val earlierQuestions = AppGraph.chat.messages.value
                            .filter { it.role == "user" && it.id != userMessage.id }
                            .map { it.text }
                        val dish = PairingTable.askDish(
                            text,
                            earlierQuestions,
                            AppGraph.palate.profile.value
                        )
                        val system = Prompts.chat(
                            AppGraph.taste.snapshot(),
                            AppGraph.cellar.data.value,
                            AppGraph.settings.currency,
                            AppGraph.palate.rules,
                            // A hundred bottles is a long digest. Gemini's free tier carries it;
                            // Groq's 8k tokens a minute does not, so it keeps the shortlist.
                            digestLimit = budget.digestLimit,
                            dish = dish,
                            // The record of what was actually opened, which is what "what did I
                            // drink yesterday" needs and what the cellar alone cannot say. Fewer
                            // lines on the budget that keeps a short cellar digest.
                            drinks = AppGraph.drinks.digest(
                                limit = if (budget.digestLimit >= 30) 20 else 8
                            ),
                            // The menus, the same way: what was ordered and what was refused where,
                            // which is the one thing neither the cellar nor the drink log holds.
                            menus = AppGraph.lists.digest(
                                AppGraph.cellar.data.value.wines,
                                limit = if (budget.digestLimit >= 30) 6 else 3
                            )
                        )
                        val answer = AppGraph.ai.complete(
                            system = system,
                            turns = history,
                            imageBase64 = base64,
                            vision = base64 != null,
                            // Advice model, chat order: Gemini leads because every turn resends
                            // the history and Groq's free minute cannot carry a conversation.
                            job = AiJob.CHAT,
                            // Prose advice, not JSON: this is the one request that wants the model
                            // to reason. Thinking shares the completion budget, so the cap is
                            // above what an answer alone needs.
                            thinking = Thinking.MEDIUM,
                            temperature = 0.5,
                            maxTokens = 1_600
                        )
                        // The move, if the answer asked for one, is a marker line rather than prose:
                        // it is stripped here so the transcript never carries the JSON and the next
                        // question's history does not teach the model to write it twice. Nothing is
                        // written to the cellar — the card proposes, and the user confirms.
                        val reply = ProposalParsing.split(answer)
                        AppGraph.chat.append(
                            ChatMessage(
                                role = "assistant",
                                text = reply.text,
                                moveRequest = reply.move,
                                update = reply.update
                            )
                        )
                    } catch (e: Exception) {
                        error = e.message ?: "The assistant did not answer. Try again."
                    } finally {
                        sending = false
                    }
                }
            }
        }
    }

    // The only place a chat move is written, and through the same repository call the bottle page
    // uses. A location the cellar does not have yet is created first, because the card said so.
    val applyMove: suspend (ChatMessage) -> Unit = { message ->
        val request = message.moveRequest
        val plan = request?.plan(AppGraph.cellar.data.value)
        if (request != null && plan != null && plan.wines.isNotEmpty()) {
            val targetId = plan.location?.id
                ?: AppGraph.cellar.addLocation(plan.locationName)?.id
            if (targetId != null) {
                AppGraph.cellar.moveMany(plan.wines.map { it.id }, targetId, request.slot.trim())
                AppGraph.chat.update(message.id) { it.copy(movedCount = plan.wines.size) }
            } else {
                error = "The location \"${plan.locationName}\" could not be created."
            }
        }
    }

    val dismissMove: (ChatMessage) -> Unit = { message ->
        AppGraph.chat.update(message.id) { it.copy(moveRequest = null) }
    }

    // A field change, written the way the bottle page writes one: price and count as values, the note
    // appended as a dated line rather than replacing what is already there.
    val applyUpdate: suspend (ChatMessage) -> Unit = { message ->
        val update = message.update
        val plan = update?.plan(AppGraph.cellar.data.value)
        if (update != null && plan != null && plan.wines.isNotEmpty()) {
            AppGraph.cellar.applyFields(
                ids = plan.wines.map { it.id },
                slot = update.slot?.trim(),
                price = update.price,
                quantity = update.quantity,
                vintage = update.vintage,
                note = update.note
            )
            AppGraph.chat.update(message.id) { it.copy(movedCount = plan.wines.size) }
        }
    }

    LaunchedEffect(messages.size, sending) {
        val target = messages.size + if (sending) 1 else 0
        if (target > 0) listState.scrollToItem(target - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ask", style = MaterialTheme.typography.titleLarge)
                        // The bar is a fixed height, so this line never wraps: at 1.3x it spilled a
                        // sliced second line, and at 2.0x even one line does not fit. The count is
                        // on the cellar page anyway.
                        if (LocalDensity.current.fontScale <= 1.6f) {
                            Text(
                                "${cellar.wines.sumOf { it.quantity }} bottles, your taste file and " +
                                    "what you have drunk in context",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Ask about dinner, about what is ready to drink, or photograph a shelf at the shop.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(suggestions) { suggestion ->
                        AssistChip(
                            onClick = { send(suggestion) },
                            label = { Text(suggestion) }
                        )
                    }
                    item {
                        Text(
                            "Your mytaste.md and a summary of your cellar are sent with every question.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message,
                        onApplyMove = applyMove,
                        onApplyUpdate = applyUpdate,
                        onDismiss = dismissMove
                    )
                }

                if (sending) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.height(18.dp))
                            Text(
                                "  $status",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                error?.let { message ->
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                message,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            if (pendingUri != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UriImage(
                        uri = pendingUri,
                        modifier = Modifier
                            .height(64.dp)
                            .widthIn(min = 64.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Text(
                        "  Photo attached",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = { pendingUri = null }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove photo")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = {
                        val uri = ImageUtils.newCaptureUri(context)
                        cameraTarget = uri
                        cameraLauncher.launch(uri)
                    }
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "Take a photo")
                }
                IconButton(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = "Choose a photo")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask about a bottle, a meal, a shelf…") },
                    maxLines = 4
                )
                IconButton(
                    onClick = { send(input) },
                    enabled = !sending
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/**
 * The prompt asks for plain text, but a model still slips in **bold** or *emphasis* now and then.
 * Drawing those two is the difference between an answer and a stray asterisk on the screen; the
 * rest of the markdown is left alone because the prompt tells the model not to use it.
 */
private fun emphasis(text: String): AnnotatedString = buildAnnotatedString {
    val marks = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*", RegexOption.DOT_MATCHES_ALL)
    var cursor = 0
    marks.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val bold = match.groupValues[1]
        if (bold.isNotEmpty()) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
        } else {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[2]) }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onApplyMove: suspend (ChatMessage) -> Unit,
    onApplyUpdate: suspend (ChatMessage) -> Unit,
    onDismiss: (ChatMessage) -> Unit
) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (message.imageBase64 != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "  photo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (message.text.isNotBlank()) {
                    Text(
                        emphasis(message.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
                message.moveRequest?.let { request ->
                    MoveCard(message, request, onApplyMove, onDismiss)
                }
                message.update?.let { update ->
                    UpdateCard(message, update, onApplyUpdate, onDismiss)
                }
            }
        }
    }
}

/**
 * The move an answer proposed, resolved against the cellar as it stands now.
 *
 * The card is drawn from the stored query, not from a list the model wrote, so it names every bottle
 * the app would actually move and the place it would put them — including a place that does not
 * exist yet, which confirming creates. Nothing is written until the button is tapped.
 */
@Composable
private fun MoveCard(
    message: ChatMessage,
    request: MoveRequest,
    onApply: suspend (ChatMessage) -> Unit,
    onDismiss: (ChatMessage) -> Unit
) {
    val scope = rememberCoroutineScope()
    val cellar by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val plan = remember(request, cellar) { request.plan(cellar) }
    var applying by remember(message.id) { mutableStateOf(false) }
    val place = request.location.trim()

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when {
                message.movedCount > 0 -> Text(
                    text = "Moved ${message.movedCount} " +
                        "${if (message.movedCount == 1) "bottle" else "bottles"} to $place.",
                    style = MaterialTheme.typography.titleSmall
                )

                plan == null -> Text(
                    text = "That move could not be read. Ask again, naming the place and the bottles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                plan.wines.isEmpty() -> {
                    Text("Nothing to move", style = MaterialTheme.typography.titleSmall)
                    if (plan.filterLabel.isNotBlank()) {
                        Text(
                            text = plan.filterLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (plan.alreadyThere.isEmpty()) {
                            "No bottle in your cellar matches that."
                        } else {
                            "All ${plan.alreadyThere.size} matching " +
                                "${if (plan.alreadyThere.size == 1) "bottle" else "bottles"} " +
                                "already live at $place."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    Text(
                        text = "Move ${plan.wines.size} " +
                            "${if (plan.wines.size == 1) "bottle" else "bottles"} to $place",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (plan.createsLocation) {
                        Text(
                            text = "No location is called \"$place\" yet — confirming creates it.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // The narrowing the ask asked for, said out loud: "the ones with no location"
                    // has to be visibly applied, or the card looks like it ignored half the sentence.
                    if (plan.filterLabel.isNotBlank()) {
                        Text(
                            text = plan.filterLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Every match, because this is the list the user is being asked to approve.
                    // It used to stop at twelve with "and N more", which asked them to approve
                    // bottles they could not see — and a big move is exactly when that matters.
                    // A tall card is just a tall card: the transcript scrolls like any message.
                    plan.wines.forEach { wine ->
                        Text(
                            text = "\u00b7  ${wine.title} — ${plan.describe(wine, cellar)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (plan.alreadyThere.isNotEmpty()) {
                        Text(
                            text = "${plan.alreadyThere.size} more already " +
                                "${if (plan.alreadyThere.size == 1) "lives" else "live"} there.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    applying = true
                                    onApply(message)
                                    applying = false
                                }
                            },
                            enabled = !applying
                        ) {
                            Text(if (applying) "Moving…" else "Move ${plan.wines.size}")
                        }
                        TextButton(onClick = { onDismiss(message) }, enabled = !applying) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The field change an answer proposed, resolved against the cellar as it stands now.
 *
 * Drawn from the stored query and the new values, so it names every bottle it would edit and every
 * field it would touch — with the old value, when the bottles agree on it. A field the model did not
 * mention is not shown, because nothing will be written to it.
 */
@Composable
private fun UpdateCard(
    message: ChatMessage,
    update: BottleUpdate,
    onApply: suspend (ChatMessage) -> Unit,
    onDismiss: (ChatMessage) -> Unit
) {
    val scope = rememberCoroutineScope()
    val cellar by AppGraph.cellar.data.collectAsStateWithLifecycle()
    val plan = remember(update, cellar) { update.plan(cellar) }
    var applying by remember(message.id) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when {
                message.movedCount > 0 -> Text(
                    text = "Changed ${message.movedCount} " +
                        "${if (message.movedCount == 1) "bottle" else "bottles"}.",
                    style = MaterialTheme.typography.titleSmall
                )

                plan == null -> Text(
                    text = "That change could not be read. Ask again, naming the value and the bottles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                plan.wines.isEmpty() -> {
                    Text("Nothing to change", style = MaterialTheme.typography.titleSmall)
                    if (plan.filterLabel.isNotBlank()) {
                        Text(
                            text = plan.filterLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "No bottle in your cellar matches that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    Text(
                        text = "Change ${plan.wines.size} " +
                            "${if (plan.wines.size == 1) "bottle" else "bottles"}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (plan.filterLabel.isNotBlank()) {
                        Text(
                            text = plan.filterLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // The values first: what will be written matters more than which rows take it.
                    plan.lines.forEach { line ->
                        Text(text = "\u00b7  $line", style = MaterialTheme.typography.bodySmall)
                    }
                    // Every match, the same as a move card: the values above are what changes, and
                    // these are the bottles it changes them on, however many there are.
                    plan.wines.forEach { wine ->
                        Text(
                            text = "\u00b7  ${wine.title} — ${wine.subtitle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    applying = true
                                    onApply(message)
                                    applying = false
                                }
                            },
                            enabled = !applying
                        ) {
                            Text(if (applying) "Applying…" else "Apply")
                        }
                        TextButton(onClick = { onDismiss(message) }, enabled = !applying) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}
