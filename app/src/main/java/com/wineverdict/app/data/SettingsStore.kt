package com.wineverdict.app.data

import android.content.Context
import com.wineverdict.app.ai.AiProvider

/**
 * Local settings. Keys never leave the device except as the Authorization header on a request the
 * user explicitly triggered.
 *
 * Everything a provider needs lives under its own name (`key.gemini`, `vision.groq`, …), so two
 * providers can be configured at once and either can lead.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("wineverdict_settings", Context.MODE_PRIVATE)

    init {
        // Pre-1.4 installs kept a single Groq key and model list; carry them over untouched.
        migrate(LEGACY_API_KEY, "key")
        migrate(LEGACY_VISION_MODEL, "vision")
        migrate(LEGACY_TEXT_MODEL, "text")
        migrate(LEGACY_MODEL_LIST, "models")
    }

    private fun migrate(legacyKey: String, slot: String) {
        val target = name(slot, AiProvider.GROQ)
        if (prefs.contains(target)) return
        val legacy = prefs.getString(legacyKey, "").orEmpty()
        if (legacy.isNotBlank()) prefs.edit().putString(target, legacy).apply()
    }

    var primaryProvider: AiProvider
        get() = AiProvider.from(prefs.getString(KEY_PRIMARY, null))
        set(value) = prefs.edit().putString(KEY_PRIMARY, value.id).apply()

    /**
     * Providers that can actually answer, primary first. A request that fails on the first one
     * moves down this list.
     */
    val configuredProviders: List<AiProvider>
        get() {
            val primary = primaryProvider
            return AiProvider.entries
                .filter { key(it).isNotBlank() }
                .sortedBy { if (it == primary) 0 else 1 }
        }

    val hasAnyKey: Boolean get() = AiProvider.entries.any { key(it).isNotBlank() }

    fun key(provider: AiProvider): String =
        prefs.getString(name("key", provider), "").orEmpty().trim()

    fun setKey(provider: AiProvider, value: String) {
        prefs.edit().putString(name("key", provider), value.trim()).apply()
    }

    fun baseUrl(provider: AiProvider): String =
        prefs.getString(name("base", provider), "").orEmpty().trim()
            .ifBlank { provider.defaultBaseUrl }
            .trimEnd('/')

    fun setBaseUrl(provider: AiProvider, value: String) {
        prefs.edit().putString(name("base", provider), value.trim()).apply()
    }

    fun visionModels(provider: AiProvider): List<String> =
        list(name("vision", provider), provider.defaultVisionModels)

    fun setVisionModels(provider: AiProvider, value: List<String>) {
        putList(name("vision", provider), value, provider.defaultVisionModels)
    }

    fun visionModel(provider: AiProvider): String = visionModels(provider).first()

    fun textModels(provider: AiProvider): List<String> =
        list(name("text", provider), provider.defaultTextModels)

    fun setTextModels(provider: AiProvider, value: List<String>) {
        putList(name("text", provider), value, provider.defaultTextModels)
    }

    fun textModel(provider: AiProvider): String = textModels(provider).first()

    fun requestsPerMinute(provider: AiProvider): Int =
        prefs.getInt(name("rpm", provider), provider.defaultRequestsPerMinute).coerceAtLeast(1)

    fun setRequestsPerMinute(provider: AiProvider, value: Int) {
        prefs.edit()
            .putInt(name("rpm", provider), value.coerceIn(1, 600))
            .apply()
    }

    fun lastModelList(provider: AiProvider): String =
        prefs.getString(name("models", provider), "").orEmpty()

    fun setLastModelList(provider: AiProvider, value: String) {
        prefs.edit().putString(name("models", provider), value).apply()
    }

    /**
     * Frames a minute the configured providers can carry together: each is capped by whichever of
     * its request pace and its token budget binds first.
     */
    val poolFramesPerMinute: Double
        get() = AiProvider.entries
            .filter { key(it).isNotBlank() }
            .sumOf { provider ->
                minOf(
                    requestsPerMinute(provider).toDouble(),
                    provider.tokenBudgetPerMinute.toDouble() / ScanQueue.TOKENS_PER_FRAME
                )
            }

    /**
     * Show the scan debug panel in a release build too. Debug builds always show it; this is the
     * switch for a phone running the sideload APK, where there is no adb to read a log from.
     */
    var showScanDetails: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SCAN_DETAILS, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SCAN_DETAILS, value).apply()

    var currency: String
        get() = prefs.getString(KEY_CURRENCY, "").orEmpty().ifBlank { DEFAULT_CURRENCY }
        set(value) = prefs.edit()
            .putString(KEY_CURRENCY, value.trim().uppercase().ifBlank { DEFAULT_CURRENCY })
            .apply()

    private fun name(slot: String, provider: AiProvider): String = "$slot.${provider.id}"

    private fun list(key: String, fallback: List<String>): List<String> =
        prefs.getString(key, "").orEmpty()
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { fallback }

    private fun putList(key: String, value: List<String>, fallback: List<String>) {
        val clean = value.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        prefs.edit().putString(key, clean.ifEmpty { fallback }.joinToString(", ")).apply()
    }

    companion object {
        private const val KEY_PRIMARY = "primary.provider"
        private const val KEY_CURRENCY = "currency"
        private const val KEY_SHOW_SCAN_DETAILS = "scan.details"

        private const val LEGACY_API_KEY = "groq_api_key"
        private const val LEGACY_VISION_MODEL = "vision_model"
        private const val LEGACY_TEXT_MODEL = "text_model"
        private const val LEGACY_MODEL_LIST = "model_list"

        const val DEFAULT_CURRENCY = "USD"
    }
}