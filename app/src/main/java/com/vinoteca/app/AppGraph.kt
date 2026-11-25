package com.vinoteca.app

import android.content.Context
import com.vinoteca.app.ai.AiClient
import com.vinoteca.app.data.CellarRepository
import com.vinoteca.app.data.ChatRepository
import com.vinoteca.app.data.NotesBackfill
import com.vinoteca.app.data.PalateStore
import com.vinoteca.app.data.ScanQueue
import com.vinoteca.app.data.SettingsStore
import com.vinoteca.app.data.TasteStore
import com.vinoteca.app.model.Wine
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
        scanQueue = ScanQueue(app, settings, taste, cellar, ai, scope)
        notes = NotesBackfill(ai, cellar, scope)
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