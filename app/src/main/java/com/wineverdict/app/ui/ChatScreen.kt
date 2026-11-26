package com.wineverdict.app.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wineverdict.app.AppGraph
import com.wineverdict.app.ai.AiJob
import com.wineverdict.app.ai.Prompts
import com.wineverdict.app.ai.Thinking
import com.wineverdict.app.model.ChatMessage
import com.wineverdict.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val suggestions = listOf(
    "What should I open with chicken pot pie tonight?",
    "Which of my bottles are at their best right now?",
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
                        // The last few exchanges only: the previous answer is what the repetition
                        // rule needs, while the whole transcript would crowd the token budget.
                        val history = AppGraph.chat.messages.value
                            .takeLast(12)
                            .map { if (it.id == userMessage.id) it else it.copy(imageBase64 = null) }
                        val system = Prompts.chat(
                            AppGraph.taste.snapshot(),
                            AppGraph.cellar.data.value,
                            AppGraph.settings.currency,
                            AppGraph.palate.rules,
                            // A hundred bottles is a long digest. Gemini's free tier carries it;
                            // Groq's 8k tokens a minute does not, so it keeps the shortlist.
                            digestLimit = if (
                                AppGraph.settings.primaryProvider.tokenBudgetPerMinute >= 100_000
                            ) {
                                140
                            } else {
                                55
                            }
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
                        AppGraph.chat.append(ChatMessage(role = "assistant", text = answer.trim()))
                    } catch (e: Exception) {
                        error = e.message ?: "The assistant did not answer. Try again."
                    } finally {
                        sending = false
                    }
                }
            }
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
                        Text(
                            "${cellar.wines.sumOf { it.quantity }} bottles and your taste file in context",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                    MessageBubble(message)
                }

                if (sending) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.height(18.dp))
                            Text(
                                "  Reading your cellar…",
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
private fun MessageBubble(message: ChatMessage) {
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
            }
        }
    }
}