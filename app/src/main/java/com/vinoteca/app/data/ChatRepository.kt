package com.vinoteca.app.data

import android.content.Context
import com.vinoteca.app.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ChatRepository(context: Context) {

    private val file = File(context.filesDir, "chat.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    fun load() {
        if (!file.exists()) return
        runCatching { json.decodeFromString<List<ChatMessage>>(file.readText()) }
            .onSuccess { _messages.value = it }
    }

    fun append(message: ChatMessage) {
        val next = (_messages.value + message).takeLast(MAX_MESSAGES)
        _messages.value = next
        runCatching { file.writeText(json.encodeToString(next)) }
    }

    fun clear() {
        _messages.value = emptyList()
        runCatching { file.delete() }
    }

    private companion object {
        // Photos are not persisted, only the text of the conversation.
        const val MAX_MESSAGES = 60
    }
}