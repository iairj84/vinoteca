package com.wineverdict.app

import android.content.Context
import com.wineverdict.app.ai.AiClient
import com.wineverdict.app.data.CellarRepository
import com.wineverdict.app.data.ChatRepository
import com.wineverdict.app.data.NotesBackfill
import com.wineverdict.app.data.PalateFile
import com.wineverdict.app.data.PalateStore
import com.wineverdict.app.data.ScanQueue
import com.wineverdict.app.data.SettingsStore
import com.wineverdict.app.data.TasteStore
import com.wineverdict.app.data.Updater
import com.wineverdict.app.model.Wine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-rolled service locator: the app is small enough that a DI framework
 * would be more code than the app itself.
 */
object AppGraph {
    private var initialized = false

    lateinit var cellar: CellarRepository
        private set
    lateinit var chat: ChatRepository
        private set
    lateinit var taste: TasteStore
        private set
    lateinit var palate: PalateStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var ai: AiClient
        private set
    lateinit var scanQueue: ScanQueue
        private set
    lateinit var notes: NotesBackfill
        private set
    lateinit var updater: Updater
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        settings = SettingsStore(app)
        palate = PalateStore(app)
        taste = TasteStore(app)
        cellar = CellarRepository(app)
        chat = ChatRepository(app)
        ai = AiClient(settings)
        // Every model request now reads the short brief, generated from the stored profile and the
        // cellar rather than sliced out of the 1,800-word mytaste.md. The file stays the user's.
        taste.briefSource = {
            val profile = palate.profile.value
            val wines = cellar.data.value.wines
            PalateFile.brief(
                profile = profile,
                rules = palate.rules,
                loved = taste.lovedBottles(wines),
                disliked = wines.filter { (it.rating ?: 0) in 1..2 }
            )
        }
        scanQueue = ScanQueue(app, settings, taste, cellar, ai, scope)
        notes = NotesBackfill(ai, cellar, scope)
        updater = Updater(app)
        scope.launch {
            cellar.load()
            chat.load()
            taste.load()
            scanQueue.load()
            // Anything left from the last session carries on where it stopped.
            scanQueue.start()
        }
    }
}

/**
 * Carries a model-generated wine draft from the scan screen into the editor
 * without stuffing JSON into a navigation route.
 */
object ScanHandoff {
    private var draft: Wine? = null

    fun put(wine: Wine) {
        draft = wine
    }

    fun take(): Wine? {
        val current = draft
        draft = null
        return current
    }
}