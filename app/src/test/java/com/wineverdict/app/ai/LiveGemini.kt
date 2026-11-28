package com.wineverdict.app.ai

import com.wineverdict.app.model.ChatMessage
import java.io.File

/**
 * The Gemini plumbing the live instruments need: the key file and the endpoint.
 *
 * Key lookup, in order: `-DgeminiKeyFile=…`, `GEMINI_KEY_FILE`, `~/.freebuff/gemini.txt`. The key
 * is read here and nowhere else, goes into the Authorization header, and is never printed.
 */
internal object LiveGemini {

    val model: String = AiProvider.GEMINI.defaultAdviceModels.first()

    private val endpoint = "${AiProvider.GEMINI.defaultBaseUrl}/chat/completions"

    private fun keyFile(): File? = sequenceOf(
        System.getProperty("geminiKeyFile"),
        System.getenv("GEMINI_KEY_FILE"),
        System.getProperty("user.home") + "/.freebuff/gemini.txt",
        System.getProperty("user.home") + "\\.freebuff\\gemini.txt"
    ).filterNotNull()
        .map { File(it) }
        .firstOrNull { it.isFile && it.length() > 0 }

    fun key(): String? = keyFile()?.readText()?.trim()?.takeIf { it.isNotBlank() }

    /**
     * The body the app itself sends for this job, built by [chatPayload] rather than by hand: the
     * thinking map and the output budget are the two fields a measurement has to keep honest.
     */
    fun payload(
        system: String,
        turn: String,
        maxTokens: Int,
        thinking: Thinking,
        temperature: Double = 0.4,
        jsonMode: Boolean = false
    ): String = chatPayload(
        target = Target(AiProvider.GEMINI, AiProvider.GEMINI.defaultBaseUrl, "", model),
        system = system,
        turns = listOf(ChatMessage(role = "user", text = turn)),
        images = emptyList(),
        jsonMode = jsonMode,
        thinking = thinking,
        temperature = temperature,
        maxTokens = maxTokens
    )

    fun post(payload: String, key: String): LiveReply = LiveGroq.post(payload, key, endpoint)
}
