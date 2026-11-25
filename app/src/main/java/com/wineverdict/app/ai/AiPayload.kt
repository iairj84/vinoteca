package com.wineverdict.app.ai

import com.wineverdict.app.model.ChatMessage
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One resolved endpoint: provider, base URL, key and model. */
internal data class Target(
    val provider: AiProvider,
    val baseUrl: String,
    val key: String,
    val model: String
)

/**
 * How hard the model is asked to think, per provider.
 *
 * Groq accepts only `low`, `medium` and `high`; asking for `"none"` is a 400. The old code sent
 * exactly that for [Thinking.OFF] and leaned on the 400-retry to drop the field — which left the
 * model reasoning at its default, spending 798 of an 800-token list judgement on thinking and
 * returning no answer at all. Measured on `openai/gpt-oss-120b`, 26-wine list: 0 characters,
 * `finish_reason=length`. `low` is the closest Groq has to off, so that is what OFF maps to.
 * Gemini's compatibility layer already uses `low` for both, and OpenRouter's free models take the
 * plain OpenAI shape with no field at all.
 */
internal fun reasoningValue(provider: AiProvider, thinking: Thinking): String? = when (provider) {
    AiProvider.GROQ -> when (thinking) {
        Thinking.OFF, Thinking.LOW -> "low"
        Thinking.MEDIUM -> "medium"
    }
    AiProvider.GEMINI -> when (thinking) {
        Thinking.OFF, Thinking.LOW -> "low"
        Thinking.MEDIUM -> "medium"
    }
    AiProvider.OPENROUTER -> null
}

/**
 * The `max_tokens` actually sent.
 *
 * Reasoning is billed against the same budget as the answer. Asking for an 800-token answer and
 * sending 800 leaves a model that thinks first with nothing to write — a 400-token fine-print read
 * came back empty for exactly that reason. The reserve is a ceiling, not a spend: a model that
 * thinks less stops earlier and pays nothing for the room.
 */
internal fun outputBudget(maxTokens: Int, thinking: Thinking?): Int {
    if (thinking == null) return maxTokens
    val reserve = when (thinking) {
        Thinking.OFF -> 600
        Thinking.LOW -> 900
        Thinking.MEDIUM -> 1_800
    }
    return (maxTokens + reserve).coerceAtMost(MAX_OUTPUT_BUDGET)
}

/** No request here needs more than this, and an absurd ceiling is a provider error waiting to happen. */
private const val MAX_OUTPUT_BUDGET = 8_000

/**
 * The chat-completions body, exactly as sent.
 *
 * Pure and internal so a test can pin it: the `reasoning_effort` value and the output budget are
 * the two fields whose wrong values returned an empty answer, and neither is visible from the UI.
 */
internal fun chatPayload(
    target: Target,
    system: String,
    turns: List<ChatMessage>,
    images: List<String>,
    jsonMode: Boolean,
    thinking: Thinking?,
    temperature: Double,
    maxTokens: Int
): String {
    val messages = buildJsonArray {
        addJsonObject {
            put("role", "system")
            put("content", system)
        }
        turns.forEachIndexed { index, turn ->
            val attachImages = images.isNotEmpty() && index == turns.lastIndex && turn.role == "user"
            if (attachImages) {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", turn.text.ifBlank { "What can you tell me about this?" })
                        }
                        images.forEach { base64 ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:image/jpeg;base64,$base64")
                                }
                            }
                        }
                    }
                }
            } else {
                addJsonObject {
                    put("role", turn.role)
                    put("content", turn.text)
                }
            }
        }
    }
    val root = buildJsonObject {
        put("model", target.model)
        put("messages", messages)
        put("temperature", temperature)
        put("max_tokens", outputBudget(maxTokens, thinking))
        if (jsonMode) {
            putJsonObject("response_format") { put("type", "json_object") }
        }
        thinking?.let { level ->
            reasoningValue(target.provider, level)?.let { put("reasoning_effort", it) }
        }
    }
    return root.toString()
}
