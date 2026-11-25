package com.wineverdict.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * The quick-start answers, kept as JSON next to the settings.
 *
 * The answers are the source of truth for the checkable thresholds; `mytaste.md` is the prose the
 * model reads. When an advanced user edits the file by hand the two can drift, which is why the Taste
 * screen shows both and says which one the app is checking.
 */
class PalateStore(context: Context) {

    private val prefs = context.getSharedPreferences("wineverdict_palate", Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(read())
    val profile: StateFlow<PalateProfile> = _profile.asStateFlow()

    val rules: PalateRules get() = PalateRules.from(_profile.value)

    /**
     * True once the file has been saved by hand in the advanced editor. The quick start then keeps
     * that file below the freshly generated one instead of replacing it.
     */
    var handEdited: Boolean
        get() = prefs.getBoolean(KEY_HAND_EDITED, false)
        set(value) = prefs.edit().putBoolean(KEY_HAND_EDITED, value).apply()

    fun markFileEdited() {
        handEdited = true
    }

    fun save(profile: PalateProfile) {
        val clean = profile.copy(version = PalateProfile.VERSION)
        runCatching { prefs.edit().putString(KEY, json.encodeToString(PalateProfile.serializer(), clean)).apply() }
        _profile.value = clean
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
        _profile.value = PalateProfile.EMPTY
    }

    private fun read(): PalateProfile {
        val raw = prefs.getString(KEY, "").orEmpty()
        if (raw.isBlank()) return PalateProfile.EMPTY
        return runCatching { json.decodeFromString(PalateProfile.serializer(), raw) }
            .getOrDefault(PalateProfile.EMPTY)
    }

    companion object {
        private const val KEY = "profile"
        private const val KEY_HAND_EDITED = "file.handEdited"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}