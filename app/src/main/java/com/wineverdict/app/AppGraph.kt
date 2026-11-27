package com.wineverdict.app

import android.content.Context
import com.wineverdict.app.ai.AiClient
import com.wineverdict.app.ai.PictureGate
import com.wineverdict.app.data.CellarRepository
import com.wineverdict.app.data.ChatRepository
import com.wineverdict.app.data.DetailsBackfill
import com.wineverdict.app.data.DrinkLog
import com.wineverdict.app.data.InsightStore
import com.wineverdict.app.data.PalateFile
import com.wineverdict.app.data.PalateProfile
import com.wineverdict.app.data.PalateRules
import com.wineverdict.app.data.PalateStore
import com.wineverdict.app.data.PhotoBackfill
import com.wineverdict.app.data.SavedListStore
import com.wineverdict.app.data.ScanQueue
import com.wineverdict.app.data.SettingsStore
import com.wineverdict.app.data.TasteStore
import com.wineverdict.app.data.TasteText
import com.wineverdict.app.data.Updater
import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import com.wineverdict.app.util.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    lateinit var lists: SavedListStore
        private set
    lateinit var drinks: DrinkLog
        private set
    lateinit var insights: InsightStore
        private set
    lateinit var details: DetailsBackfill
        private set
    lateinit var photos: PhotoBackfill
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
                disliked = wines.filter { Verdict.disliked(it) },
                // Whatever the user typed themselves. The generated half of the file is already
                // rendered above from the same profile, so this is the only text that would
                // otherwise be lost between the file and the brief.
                notes = TasteText.userNotes(taste.text.value)
            )
        }
        scanQueue = ScanQueue(app, settings, taste, cellar, ai, scope)
        lists = SavedListStore(app)
        drinks = DrinkLog(app)
        // Constructed after taste.briefSource is wired, so the lambda above is what an insight sees.
        insights = InsightStore(app, ai, cellar) { taste.brief() }
        // One job for everything a bottle is missing: published notes, flavour words and the ABV
        // the alcohol gate needs. One lookup already answers both, so a bottle is asked about once.
        details = DetailsBackfill(
            wines = { cellar.data.value.wines },
            lookup = { wine ->
                val found = ai.sourcedNotes(wine)
                // A producer's own page often skips the figure; a shop's listing almost always
                // prints it. Ask that question only when the notes pages did not answer it. A
                // failure here is thrown, not turned into "no ABV": the job leaves that bottle
                // unstamped and asks it again next time.
                if (found.alcohol != null) found else found.copy(alcohol = ai.abvOf(wine))
            },
            save = { id, abv, result -> cellar.applyDetails(id, abv, result) },
            scope = scope
        )
        // The same picture a search-added bottle gets, for the bottles already here: the pages that
        // name each one, and the photograph the first of them declares. No model is asked, so this
        // costs no quota — only the keyless search and the pages it names.
        photos = PhotoBackfill(
            wines = { cellar.data.value.wines },
            find = { wine -> ai.bottleImageFor(wine) },
            save = { id, bytes ->
                // A page's picture arrives full size; it is kept at the size a scanned label is,
                // and marked as the finder's so a later check may reconsider it.
                val small = ImageUtils.jpegBytes(bytes)
                val name = if (small == null) "" else {
                    cellar.saveLabelPhoto(id, small, fromWeb = true)
                }
                if (name.isNotBlank()) cellar.attachPhoto(id, name)
            },
            scope = scope,
            stored = { wine ->
                withContext(Dispatchers.IO) { cellar.labelPhoto(wine)?.readBytes() }
            },
            looksLikeTheBottle = { bytes -> PictureGate.acceptable(bytes) },
            fromThePages = { wine -> CellarRepository.isWebPhoto(wine.photo) },
            drop = { id -> cellar.clearPhoto(id) }
        )
        updater = Updater(app)
        scope.launch {
            cellar.load()
            chat.load()
            taste.load()
            insights.load()
            scanQueue.load()
            lists.load()
            drinks.load()
            // Anything left from the last session carries on where it stopped.
            scanQueue.start()
            // mytaste.md is a projection of the stored answers, and Ask sends that file. An upgrade
            // that changes the rendered shape must rewrite it rather than leave the old gate table
            // on disk — the questions never have to be answered again. A file saved by hand in the
            // advanced editor is the user's, and stays exactly as it is.
            val profile = palate.profile.value
            if (profile.isComplete && !palate.handEdited && taste.text.value.contains(PalateFile.MARKER)) {
                savePalateProfile(profile)
            }
        }
    }

    /**
     * Saves a palate change and rewrites `mytaste.md` from it, in one place.
     *
     * The file is generated prose about the same thresholds the app checks, so saving a ceiling or a
     * note without regenerating it left the Taste screen showing a file that said "no gate is set"
     * beside a gate the app was enforcing. A file the user wrote or imported is kept below the new
     * one, exactly as the quick start does it.
     */
    fun savePalateProfile(profile: PalateProfile) {
        palate.save(profile)
        val wines = cellar.data.value.wines
        val body = PalateFile.build(
            profile = profile,
            rules = PalateRules.from(profile),
            ratings = wines.count { it.rating != null }
        )
        val current = taste.text.value
        val keep = palate.handEdited || !current.contains(PalateFile.MARKER)
        taste.writeFromQuickStart(body, keepExisting = keep)
        taste.rebuildLovedSection(wines)
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

    /** Whether the editor was opened with a scan's draft, which is what makes a save a confirmation. */
    fun hasDraft(): Boolean = draft != null

    fun take(): Wine? {
        val current = draft
        draft = null
        return current
    }
}