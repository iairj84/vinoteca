package com.wineverdict.app.ai

/**
 * The services Wine Verdict can talk to. All of them speak the OpenAI-compatible chat-completions
 * shape, so switching is a base URL, a key and a model id — not a new client.
 *
 * Each provider keeps its own key, base URL, model ids and pace in Settings, so both can be
 * configured at once: the first one with a key leads, and the other is the fallback.
 */
enum class AiProvider(
    val id: String,
    val label: String,
    val shortLabel: String,
    val defaultBaseUrl: String,
    val defaultVisionModels: List<String>,
    val defaultTextModels: List<String>,
    val keyHint: String,
    val keyUrl: String,
    val keyPageHint: String,
    val defaultRequestsPerMinute: Int,
    val tokenBudgetPerMinute: Int,
    /** Groq reports remaining budget in headers; Gemini only implies it through its limits. */
    val reportsRateHeaders: Boolean,
    val notes: String
) {
    GEMINI(
        id = "gemini",
        label = "Google Gemini",
        shortLabel = "Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultVisionModels = listOf("gemini-3.1-flash-lite"),
        defaultTextModels = listOf("gemini-3.1-flash-lite"),
        keyHint = "AIza…",
        keyUrl = "https://aistudio.google.com/apikey",
        keyPageHint = "aistudio.google.com",
        defaultRequestsPerMinute = 10,
        tokenBudgetPerMinute = 250_000,
        reportsRateHeaders = false,
        notes = "Free tier is about 10 requests and 250,000 input tokens a minute on Flash-Lite, " +
            "with a few hundred requests a day. Google uses free-tier content to improve its " +
            "products; paid tiers do not. Photo tokens are cheap here: a 1536px frame is roughly " +
            "1,000 tokens."
    ),
    GROQ(
        id = "groq",
        label = "Groq",
        shortLabel = "Groq",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        defaultVisionModels = listOf("qwen/qwen3.8-27b"),
        defaultTextModels = listOf("openai/gpt-oss-120b"),
        keyHint = "gsk_…",
        keyUrl = "https://console.groq.com/keys",
        keyPageHint = "console.groq.com",
        defaultRequestsPerMinute = 30,
        tokenBudgetPerMinute = 8_000,
        reportsRateHeaders = true,
        notes = "Free tier is 30 requests and 8,000 tokens a minute, counted per model, with a " +
            "200,000-token daily cap. Groq reports the remaining budget on every response, so the " +
            "queue paces itself from real numbers when this provider is in use."
    ),
    OPENROUTER(
        id = "openrouter",
        label = "OpenRouter (free)",
        shortLabel = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultVisionModels = listOf("nvidia/nemotron-3.5-super-vl:free"),
        defaultTextModels = listOf("nvidia/nemotron-3.5-super-vl:free"),
        keyHint = "sk-or-v1-…",
        keyUrl = "https://openrouter.ai/keys",
        keyPageHint = "openrouter.ai",
        defaultRequestsPerMinute = 15,
        tokenBudgetPerMinute = 200_000,
        reportsRateHeaders = false,
        notes = "Free models cost nothing: about 15 requests a minute and 50 a day on this key, " +
            "rising to 1,000 a day once the OpenRouter account has bought 10 credits. The model " +
            "is fixed to a free vision model suited to wine lists, so there is nothing to choose " +
            "— if OpenRouter retires it, an app update points at the replacement."
    );

    companion object {
        val DEFAULT_PRIMARY: AiProvider = GEMINI

        fun from(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: DEFAULT_PRIMARY
    }
}