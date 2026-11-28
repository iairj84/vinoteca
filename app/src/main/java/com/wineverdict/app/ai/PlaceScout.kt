package com.wineverdict.app.ai

import com.wineverdict.app.data.Search
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.util.Locale

/**
 * The sommelier's research when the question is about somewhere out in the world, not about a bottle.
 *
 * A question like "where should I go for dinner in Portland with good wine?" used to be answered
 * from the model's memory of restaurants, and memory is exactly the wrong source: the last three
 * places it named had been closed for years. Nothing in the app could catch that, because the reply
 * was prose and the app had fetched nothing.
 *
 * So a place question is now answered the way a bottle question is: the app does the looking, and
 * the model is only allowed to choose between numbered lines the app fetched. Three things are
 * code, not prompt:
 *
 *  - [ask] decides whether the question is about a place at all, and where. A cellar question that
 *    happens to say "dinner" is not sent to the web.
 *  - [venues] builds the list from search results and [closedReason] removes anything a page marks
 *    closed — permanently, for good, no longer in business — before the model sees it.
 *  - [answer] writes the reply from the venue records: the name, the host and the source are copied
 *    from the page, never from the model, so the model cannot smuggle in a restaurant it remembers.
 */
object PlaceScout {

    /** How many numbered places the pick may choose from: enough to choose, few enough to read. */
    const val MAX_VENUES = 10

    /**
     * Names worth one confirmation search each. Three is a table's worth of options, and the whole
     * question stays at five searches — a keyless engine refuses a longer burst.
     */
    const val MAX_CANDIDATES = 3

    /** Two or three is advice; more is a directory. */
    const val MAX_PICKS = 3

    /** Places whose pages are read before one is picked: four is a table's worth of choices. */
    const val MAX_CHOICES = 4

    /** Page requests one question may spend when some of them come back dead or closed. */
    const val MAX_PAGES = 6

    /** Somewhere to sit down, as the map's kind field spells it. */
    private val SIT_DOWN = listOf("bar", "pub", "restaurant", "cafe", "bistro", "lounge")

    /** The words a wine venue's own page uses; a page without one is not a wine venue's page. */
    private val PAGE_WINE_WORDS = listOf(
        "wine", "winery", "vineyard", "tasting", "cellar", "sommelier", "pinot", "riesling",
        "chardonnay", "bottle", "glass"
    )

    /** Words that say what kind of business it is, not which one: too common to identify a page. */
    private val GENERIC = setOf(
        "wine", "bar", "shop", "house", "cellar", "company", "merchant", "winery", "tasting",
        "room", "lounge", "the", "and", "pdx", "street", "avenue"
    )

    /** A place question, once the words have been read. */
    data class Ask(
        /** The town or area as the user typed it, without the surrounding sentence. */
        val where: String,
        /** "near me": [where] is empty until the phone can name the town it is standing in. */
        val nearMe: Boolean = false,
        val kind: Kind = Kind.ANY,
        val question: String = ""
    ) {
        enum class Kind { ANY, RESTAURANT, BAR, SHOP }

        val place: String get() = where.trim()

        /** What a person would call the thing being looked for. */
        val subject: String
            get() = when (kind) {
                Kind.RESTAURANT -> "restaurants with a good wine list"
                Kind.BAR -> "wine bars"
                Kind.SHOP -> "wine shops"
                Kind.ANY -> "wine bars and restaurants with a good list"
            }
    }

    /** One place the research found, with the words that named it. */
    data class Venue(
        val name: String,
        val url: String,
        val title: String = "",
        val snippet: String = "",
        /** A window of the place's own page, cut around its name. */
        val text: String = "",
        /** Non-blank when a page says this place is closed; the words that said it. */
        val closed: String = "",
        /** How often the result talks about wine: the only ranking the app can honestly do. */
        val wine: Int = 0,
        /** The map's address, which is what proves the town: "526 SW Yamhill St, Portland, OR". */
        val address: String = "",
        /** The map's opening hours, when it has them; never estimated from anything else. */
        val hours: String = ""
    ) {
        val host: String
            get() = runCatching {
                java.net.URI(url).host.orEmpty().removePrefix("www.")
            }.getOrDefault("")
    }

    data class Pick(val line: Int, val why: String)

    data class Picks(
        val picks: List<Pick> = emptyList(),
        val skips: List<Pick> = emptyList(),
        val note: String = ""
    )

    /** What the research produced, and the reply it earns. */
    data class Report(val ask: Ask, val venues: List<Venue>, val picks: Picks?) {
        fun text(on: String = today()): String = when {
            // Nothing survived the evidence checks: say that, rather than describe a place.
            venues.isEmpty() -> noEvidence(ask)
            // The model's answer could not be read: the evidence still is, so it is printed as it
            // stands rather than turned into an apology.
            picks == null -> fallback(ask, venues, on)
            picks.picks.isEmpty() -> nothing(ask, on)
            else -> answer(picks, venues, ask, on)
        }
    }

    fun today(): String = LocalDate.now().toString()

    /** The one fold used for names and towns, so "Bar Diane" and "bar diane" are one place. */
    fun fold(text: String): String = Search.fold(text)

    /** What the short-lived cache is filed under: the town and the kind of place. */
    fun cacheKey(ask: Ask): String = "${ask.kind.name}|${fold(ask.where)}"

    /**
     * A small expiring cache for one town's answer.
     *
     * It exists so a second question at the same table ("what about something cheaper?") does not
     * ask the map again or re-read four pages, while a closure published this afternoon is still
     * picked up an hour later. Expiry is checked on read, so nothing has to sweep it.
     */
    class Cache<T>(private val ttlMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
        private val items = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, T>>()

        fun get(key: String): T? {
            val hit = items[key] ?: return null
            if (clock() - hit.first >= ttlMs) {
                items.remove(key)
                return null
            }
            return hit.second
        }

        fun put(key: String, value: T) {
            items[key] = clock() to value
        }
    }

    /**
     * Whether the town the phone is standing in is the one the question named.
     *
     * Only then can the phone's own region settle an ambiguous name. "Portland" asked from
     * Portland is "Portland, Oregon"; "Lisbon" asked from Portland is still just Lisbon.
     */
    fun sameTown(city: String, asked: String): Boolean {
        val here = fold(city)
        val there = fold(asked)
        if (here.isBlank() || there.isBlank()) return false
        return here == there || here.startsWith("$there ") || there.startsWith("$here ")
    }

    /** "Portland, Oregon" when the phone knows the region, "Portland" when it does not. */
    fun townWithRegion(city: String, region: String): String =
        if (region.isBlank()) city else "$city, $region"

    // ---------------------------------------------------------------- reading the question

    /** A noun that names somewhere you go. "Bar" alone could be a barcode, so it is a word. */
    private val VENUE = Regex(
        "(?i)\\b(restaurants?|bars?|wine bars?|wine shops?|wine stores?|bottle shops?|" +
            "tasting rooms?|wineries|bistros?|taverns?|brasseries?|tapas)\\b"
    )

    /** Eating out. Weaker than [VENUE]: a dinner at home is a cellar question. */
    private val OUTING = Regex("(?i)\\b(dinner|lunch|brunch|supper|eat out|eating out|night out|meal out)\\b")

    /** The question's shape: it asks the app to look for somewhere, not to pour something. */
    private val ASKING = Regex(
        "(?i)(\\bwhere\\b|\\brecommend|\\bsuggest|\\bany good|\\banywhere|\\bsomewhere|" +
            "\\bplace to|\\bworth (trying|a visit|going)|\\bbest\\b|\\bideas? for|\\blooking for|" +
            "\\bi want|\\bi need|\\btake me|\\bin the mood for)"
    )

    /** Opening, pouring and pairing: about a bottle, which is the cellar's job and not the web's. */
    private val BOTTLE = Regex(
        "(?i)\\b(open|opening|drink|drinking|pour|decant|cellar|shelf|rack|pair|pairing|" +
            "bottle|glass|decanting)\\b"
    )

    /**
     * Whether this question is about somewhere to go, and where.
     *
     * A false positive is the expensive mistake: it would send "what do I open with dinner" to the
     * web and come back with restaurants. So the question needs a venue noun, or an outing plus a
     * place-seeking word, and a bottle verb without a venue noun means it is the cellar's question.
     */
    fun ask(text: String): Ask? {
        val question = text.trim()
        if (question.isBlank()) return null
        val venue = VENUE.containsMatchIn(question)
        val outing = OUTING.containsMatchIn(question)
        val bottle = BOTTLE.containsMatchIn(question)
        if (!venue && !outing) return null
        // A dinner question is only about a place when it also asks where, and says nothing about
        // opening a bottle: "what should I drink with dinner" is the cellar.
        if (!venue && bottle) return null
        val asking = ASKING.containsMatchIn(question) ||
            (venue && question.contains('?') && !bottle)
        if (!asking) return null
        val (where, nearMe) = where(question)
        return Ask(
            where = where,
            nearMe = nearMe,
            kind = kind(question),
            question = question.take(200)
        )
    }

    private fun kind(text: String): Ask.Kind = when {
        Regex("(?i)\\b(wine bars?|bars?)\\b").containsMatchIn(text) -> Ask.Kind.BAR
        Regex("(?i)\\b(wine shops?|wine stores?|bottle shops?)\\b").containsMatchIn(text) -> Ask.Kind.SHOP
        Regex("(?i)\\b(restaurants?|bistros?|taverns?|brasseries?|dinner|lunch|brunch|meal out)").containsMatchIn(text) ->
            Ask.Kind.RESTAURANT
        else -> Ask.Kind.ANY
    }

    /** Words that end a place name rather than continue it. */
    private val WHERE_STOP = setOf(
        "with", "for", "and", "that", "tonight", "tomorrow", "this", "weekend", "near", "to",
        "on", "or", "but", "which", "while", "after", "before", "a", "an", "good", "great",
        "decent", "some", "any", "if", "when", "where", "right", "now", "please", "recommend"
    )

    /**
     * The place named after in/near/around/at, and whether the user meant "here".
     *
     * Search only needs a town, so the sentence is cut at the first stop word: "in Portland with
     * good wine" is "Portland". A lowercase town is kept as typed — the search does not care, and
     * rewriting it would be the app deciding what the user meant.
     */
    fun where(text: String): Pair<String, Boolean> {
        if (Regex("(?i)\\b(near|around|by)\\s+(me|here)\\b").containsMatchIn(text)) {
            return "" to true
        }
        for (lead in listOf("near", "in", "around", "at")) {
            // A comma inside the place is part of it: "in Portland, Oregon" is one town and a
            // state, and cutting at the comma asked the web about a bare "Portland" — which is
            // also Portland, Maine.
            val match = Regex("(?i)\\b$lead\\s+([^.!?;]+)").find(text) ?: continue
            val words = match.groupValues[1].trim().split(Regex("\\s+"))
            val kept = words.take(5)
                .takeWhile { it.lowercase(Locale.US).trim(',', '?', '!') !in WHERE_STOP }
                .joinToString(" ")
                .trim()
                .trim('"', '\'', '.', ',', ' ')
            if (kept.isNotBlank()) return kept to false
        }
        return "" to false
    }

    /**
     * The two searches that find *names* of places, not articles about them.
     *
     * "wine bars Portland" returns essays and directories, which are titles rather than venues; a
     * live run came back with "The Best Wine Bars in Portland" and "Portland Restaurant Closings"
     * and no place at all. The same search with a directory word appended returns the businesses'
     * own pages — "FRANK WINE BAR - 81 Reviews - Yelp", "The Portland Wine Bar - Downtown" — and
     * the directory's own description line enumerates the names it lists. Both are harvested below;
     * every name is then confirmed with its own search before it is offered.
     */
    fun discoveryQueries(ask: Ask): List<String> {
        val where = ask.where.trim()
        val tail = if (where.isBlank()) "" else " $where"
        val base = when (ask.kind) {
            Ask.Kind.BAR -> "wine bar$tail"
            Ask.Kind.SHOP -> "wine shop$tail"
            Ask.Kind.RESTAURANT -> "restaurants with great wine lists$tail"
            Ask.Kind.ANY -> "wine bar$tail"
        }
        // One query, not a burst: a live measurement of "wine bar Portland, Oregon" returned the
        // city's guides, the bars' own pages and a directory whose description enumerates six more
        // names — enough for three candidates. Every extra query is one more chance the keyless
        // engine refuses the rest of the minute, and place research already asks one per name.
        return listOf(base.trim()).filter { it.isNotBlank() }
    }

    /**
     * The names the discovery results contain.
     *
     * Two shapes only, because a loose harvester invents restaurants: a result whose title starts
     * with the place's name (a business page or its own site), and the list a directory prints in
     * its description — "Last Updated November 2026 - Division Wines, Les Caves Wine Bar, Buvons".
     * A name harvested wrongly is not fatal: [confirmed] searches for it, and a place that does not
     * exist has no results.
     */
    fun candidates(hits: List<WebLookup.Hit>, ask: Ask): List<String> {
        val weight = LinkedHashMap<String, Int>()
        val display = LinkedHashMap<String, String>()
        fun offer(name: String, points: Int) {
            val key = Search.fold(name)
            if (key.isBlank()) return
            weight[key] = (weight[key] ?: 0) + points
            display.putIfAbsent(key, name)
        }
        // A result that does not name the town is not evidence about the town. Measured: the
        // fallback engine happily answered a Portland restaurant query with another state's bars,
        // and those titles have to be spent before the harvest, not discovered afterwards.
        val parts = ask.where.split(',').map { Search.fold(it) }.filter { it.isNotBlank() }
        for (hit in hits) {
            if (parts.isNotEmpty()) {
                val text = Search.fold("${hit.title} ${hit.snippet}")
                if (!parts.all { text.contains(it) }) continue
            }
            // A name that is a result's own title is the stronger fact: a business page or the
            // place's site. A name in a directory's list is a mention, worth one point.
            venueName(hit.title)?.let { offer(it, 2) }
            listed(hit.snippet).forEach { offer(it, 1) }
        }
        return weight.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value })
            .mapNotNull { display[it.key] }
    }

    /** The names after a directory's "Last Updated <month> <year> - " prefix, which is a real list. */
    private val LIST_TAIL = Regex(
        "(?i)(?:last updated|updated)\\s+[a-z]+\\s+\\d{4}\\s*[-–—]\\s*(.+)$"
    )

    fun listed(snippet: String): List<String> {
        val tail = LIST_TAIL.find(snippet.replace(Regex("\\s+"), " "))?.groupValues?.get(1) ?: return emptyList()
        val truncated = tail.trimEnd().endsWith("…") || tail.trimEnd().endsWith("...")
        val parts = tail.split(',').map { it.trim() }
        return parts.mapIndexedNotNull { index, part ->
            // The last piece of an ellipsized snippet is half a name: it is not a candidate.
            if (truncated && index == parts.lastIndex) return@mapIndexedNotNull null
            val name = part.trim(' ', '"', '\'', '.', ',', '-')
            if (name.length !in 2..60) return@mapIndexedNotNull null
            if (name.split(Regex("\\s+")).size > 5) return@mapIndexedNotNull null
            if (name.count { it.isLetter() } < 2) return@mapIndexedNotNull null
            if (NOT_A_VENUE.containsMatchIn(name) || GENERIC_NAME.matches(name)) return@mapIndexedNotNull null
            name
        }
    }

    /**
     * The search that settles one name: does it still exist, and where does it live now.
     *
     * Quoted, because the name is the question; the town keeps a chain from answering for another
     * city. The results are also the closure check: a business page that says CLOSED, or a news
     * story about it closing, is the fact that memory gets wrong.
     */
    fun confirmQuery(name: String, ask: Ask): String =
        listOf("\"$name\"", ask.where.trim(), "wine").filter { it.isNotBlank() }.joinToString(" ")

    /**
     * One place, after its own search came back.
     *
     * Null when the results never mention the name: a harvested name that no page confirms is not
     * a place, and a place the model would have invented is exactly what this must not pass on. A
     * closure anywhere in those results wins over a directory that has not caught up.
     */
    fun confirmed(name: String, hits: List<WebLookup.Hit>, ask: Ask): Venue? {
        val key = Search.fold(name)
        if (key.isBlank()) return null
        val about = hits.filter { Search.fold("${it.title} ${it.snippet}").contains(key) }
        if (about.isEmpty()) return null
        // The town is part of the identity, not decoration: a search engine that ignores the place
        // answers "Bestia" with a Los Angeles restaurant, and a name alone would pass it. Every
        // part of the place is required, so "Portland, Oregon" rejects a Portland, Maine bar that
        // a town-only check would have kept.
        val parts = ask.where.split(',')
            .map { Search.fold(it) }
            .filter { it.isNotBlank() }
        val here = if (parts.isEmpty()) {
            about
        } else {
            about.filter { hit ->
                val text = Search.fold("${hit.title} ${hit.snippet}")
                parts.all { text.contains(it) }
            }
        }
        if (here.isEmpty()) return null
        val own = here.firstOrNull { Search.fold(it.title).startsWith(key) } ?: here.first()
        val closed = here.firstNotNullOfOrNull { hit ->
            closedReason(hit.title, hit.snippet).ifBlank { null }
        }.orEmpty()
        return Venue(
            name = name,
            url = own.url,
            title = own.title,
            snippet = own.snippet.trim().take(320),
            closed = closed,
            wine = wineMentions(here.joinToString(" ") { "${it.title} ${it.snippet}" })
        )
    }

    // ---------------------------------------------------------------- reading the results

    /** Where a search result's title stops being a name: "Loyal Legion - Portland, OR - Yelp". */
    private val TITLE_CUT = Regex("\\s*(?:\\||–|—|·|::)\\s*|\\s+-\\s+")

    /** A title that is an article, a directory page or a menu rather than one place. */
    private val NOT_A_VENUE = Regex(
        "(?i)\\b(best|top ?\\d*|guide|guides|list|lists|where to|wine bars|restaurants|" +
            "reviews?|blog|news|magazine|recipe|recipes|events?|directory|updated|near me|" +
            "open now|delivery|takeout|hours|location|directions|visit us|contact|about|book|booking|" +
            "reservations|gallery|photos)\\b"
    )

    private val GENERIC_NAME = Regex(
        "(?i)^(yelp|tripadvisor|opentable|resy|google|facebook|instagram|home|menu|menus|wine|" +
            "restaurant|restaurants|bar|bars|the|a|an)$"
    )

    private val CLOSED_PREFIX = Regex("(?i)^\\s*(permanently\\s+)?closed\\s*[:\\-–—]\\s*")

    /** "Yelp - Franciska Wine Bar": the directory's own label is not part of the place's name. */
    private val DIRECTORY_PREFIX = Regex(
        "(?i)^\\s*(yelp|tripadvisor|opentable|google maps|the infatuation|eater)\\s*[:\\-–—]\\s*"
    )

    /**
     * The confirmed places as the model reads them: open ones first, then the ones whose pages
     * actually talk about wine.
     */
    fun ranked(venues: List<Venue>): List<Venue> = venues
        .filter { it.name.isNotBlank() && it.url.startsWith("http") }
        .distinctBy { Search.fold(it.name) }
        .sortedWith(compareBy<Venue> { it.closed.isNotBlank() }.thenByDescending { it.wine })
        .take(MAX_VENUES)

    /** The name at the head of a search result's title, or null when it is not one place. */
    fun venueName(title: String): String? {
        val raw = CLOSED_PREFIX.replace(DIRECTORY_PREFIX.replace(title.trim(), ""), "")
        val first = TITLE_CUT.split(raw).firstOrNull()?.trim().orEmpty()
        val name = first.trim(' ', '"', '\'', '.', ',', '-', ':')
        if (name.length !in 2..70) return null
        if (name.split(Regex("\\s+")).size > 8) return null
        if (name.count { it.isLetter() } < 2) return null
        if (NOT_A_VENUE.containsMatchIn(name)) return null
        if (GENERIC_NAME.matches(name)) return null
        return name
    }

    /** Phrases that mean the place no longer exists. Deliberately narrow: "closed Sundays" is open. */
    private val CLOSED_PHRASES = listOf(
        "permanently closed",
        "closed permanently",
        "closed for good",
        "now closed",
        "has closed",
        "closed its doors",
        "shut its doors",
        "closed down",
        "no longer open",
        "no longer in business",
        "out of business"
    )

    /**
     * The words that say a place is closed, or "" when nothing does.
     *
     * "Closed on Mondays", "closed Sundays", "closed until Thursday" are hours, not a closure, and
     * a filter that cannot tell them apart would empty every small town on a Monday. A bare
     * "CLOSED" is only trusted in the result's own title, where directories print it as a label.
     */
    fun closedReason(vararg parts: String): String {
        val title = parts.firstOrNull().orEmpty()
        val text = parts.joinToString(" ").lowercase(Locale.US)
        if (text.isBlank()) return ""
        CLOSED_PHRASES.firstOrNull { text.contains(it) }?.let { return it }
        if (Regex("\\bclosed\\s+(in|since)\\s+(19|20)\\d{2}\\b").containsMatchIn(text)) {
            return "closed"
        }
        val label = Regex("(?i)(^|[\\s\\-–—|:])closed([\\s\\-–—|:]|$)")
        if (label.containsMatchIn(title)) return "closed"
        return ""
    }

    /** How much a result talks about wine, which is what makes it worth reading first. */
    private fun wineMentions(text: String): Int {
        val low = text.lowercase(Locale.US)
        return WINE_WORDS.sumOf { word -> Regex("\\b$word").findAll(low).count() }
    }

    private val WINE_WORDS = listOf(
        "wine", "wines", "wine list", "sommelier", "by the glass", "corkage", "cellar",
        "bottle list", "vintage", "champagne"
    )

    /** A page worth opening: the big directories answer a phone with a wall or a script. */
    fun readable(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val low = url.lowercase(Locale.US)
        return BLOCKED_HOSTS.none { low.contains(it) }
    }

    private val BLOCKED_HOSTS = listOf(
        "yelp.", "tripadvisor.", "facebook.", "instagram.", "google.", "opentable.",
        "resy.com", "doordash", "ubereats", "youtube.", "tiktok.", "mapquest"
    )

    /** The part of a page around the place's name: the sentence that says what it is. */
    fun pageDigest(text: String, name: String): String {
        if (text.isBlank()) return ""
        val at = text.indexOf(name, ignoreCase = true)
        if (at < 0) return text.take(360).trim()
        val start = if (at > 120) at - 120 else 0
        return text.substring(start, minOf(text.length, start + 420)).trim()
    }

    // ---------------------------------------------------------------- asking the model

    /**
     * The pick, from numbered evidence only.
     *
     * The model is not asked to write typos-free prose about a place; it is asked which number it
     * would choose and why, because the number is something the app can check. A name the model
     * remembers cannot pass this shape.
     */
    fun prompt(brief: String): String {
        val taste = brief.trim().ifBlank { "(my taste file is empty — judge the wine lists on their own)" }
        return """
        You are a sommelier choosing where this person should drink or eat tonight. You may use ONLY
        the pages the app fetched a moment ago, below. Your memory is the thing that has already
        failed this reader: restaurants you remember warmly can have been closed for years, and a
        name you add from memory is a taxi ride to a shuttered door.

        THE RULE, no exceptions:
        - Every place you choose must be one of the numbered entries. Never name a place that is not
          in the list, not even as an aside, an alternative or a "you might also like".
        - An entry marked CLOSED is not an option and is never chosen or named.
        - Everything you say about a place must come from its snippet or its page. If neither
          mentions wine, say what it does say — the food, the neighbourhood, the hours — and do not
          invent a wine programme, a wine list, a sommelier, a by-the-glass price or a score.
        - If a page prints hours, a price or a booking note, quote it; if not, do not guess one.
        - Prefer the places whose pages actually talk about wine, and connect a pick to MY TASTE
          below when the page gives you something to connect it to.
        - Two or three picks is an answer; one is enough when one is all the evidence supports.
          If nothing in the list is worth the trip, choose nothing: {"picks":[]} — an honest empty
          answer is the point of this exercise.

        Reply with JSON only, nothing before or after:
        {"picks":[{"line":1,"why":"one or two sentences, at most 35 words"},{"line":3,"why":"..."}],
         "skip":[{"line":4,"why":"one clause: what rules it out for me"}],
         "note":"one optional sentence, for example a booking or a by-the-glass tip"}

        - "line" is the number in WHAT THE APP FOUND. No names in the JSON.
        - "why" is what the page says and why it suits me: the wine, the list, the price, the food.
        - "skip" is for a place on the list that looks tempting and is wrong for me, with the
          reason: a closed entry is already out, so skip is about fit, not existence.

        MY TASTE
        $taste
        """.trimIndent()
    }

    /** The numbered evidence, closed entries included so the model can see why they are out. */
    fun evidence(ask: Ask, venues: List<Venue>, on: String = today()): String = buildString {
        append("WHAT THE APP FOUND — pages fetched ").append(on).append('\n')
        append("Asked for: ").append(ask.question.ifBlank { ask.subject }).append('\n')
        append("Where: ").append(ask.place.ifBlank { "(not stated)" }).append('\n')
        venues.forEachIndexed { index, venue ->
            append('\n').append(index + 1).append(". ")
            if (venue.closed.isNotBlank()) append("CLOSED — ")
            append(venue.name)
            if (venue.host.isNotBlank()) append(" (").append(venue.host).append(')')
            append('\n')
            if (venue.address.isNotBlank()) append("   address: ").append(venue.address).append('\n')
            if (venue.hours.isNotBlank()) append("   hours: ").append(venue.hours).append('\n')
            if (venue.snippet.isNotBlank()) append("   search says: ").append(venue.snippet).append('\n')
            if (venue.text.isNotBlank()) append("   page says: ").append(venue.text).append('\n')
            if (venue.closed.isNotBlank()) {
                append("   NOT AN OPTION: this place is marked closed (").append(venue.closed).append(").\n")
            }
        }
    }

    /**
     * The chosen numbers, checked against the evidence.
     *
     * A number that is not on the list is dropped, and so is a closed place and a second pick of
     * the same line: the model's freedom is the choice, never the facts. Null only when the reply
     * could not be read as JSON at all, which the caller answers with the evidence itself.
     */
    fun parse(raw: String, venues: List<Venue>): Picks? {
        val root = runCatching { AiJson.objectFrom(raw) }.getOrNull() ?: return null
        fun entries(key: String): List<Pick> = (root[key] as? JsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it as? JsonObject }
            .mapNotNull { entry ->
                val line = entry.intOrNull("line") ?: return@mapNotNull null
                val venue = venues.getOrNull(line - 1) ?: return@mapNotNull null
                if (venue.closed.isNotBlank()) return@mapNotNull null
                val why = entry.str("why").replace(Regex("\\s+"), " ").trim().take(240)
                if (why.isBlank()) return@mapNotNull null
                Pick(line, why)
            }
        val picks = entries("picks").distinctBy { it.line }.take(MAX_PICKS)
        val skips = entries("skip")
            .filter { skip -> picks.none { it.line == skip.line } }
            .distinctBy { it.line }
            .take(4)
        return Picks(picks, skips, root.str("note").replace(Regex("\\s+"), " ").trim().take(240))
    }

    // ---------------------------------------------------------------- the one pipeline

    /**
     * Everything the research needs from outside, so the chat and the live harness run one path.
     *
     * The chat passes Overpass for discovery, the app's own page reader and the provider; a harness
     * that wants to prove the path works passes the same three and nothing else changes.
     */
    interface Sources {
        /** Places in the town. Throws [AiException] with `retryable` when the source is busy. */
        suspend fun discover(ask: Ask): List<Venue>

        /** The readable text of a page, or "" when it cannot be used. */
        suspend fun page(url: String): String

        /** The model's pick, from the system prompt and the numbered evidence. */
        suspend fun pick(system: String, evidence: String): String
    }

    /**
     * Discovery, page evidence, closure and town checks, then the pick.
     *
     * The order is the promise: a place is only numbered for the model after its own page loaded,
     * said nothing about being closed, and agreed that it is in the town that was asked about. A
     * place with no page is not recommended — the evidence is the page, not the map's memory of it.
     */
    suspend fun research(ask: Ask, brief: String, sources: Sources): Report {
        val found = rank(ranked(sources.discover(ask)))
        if (found.isEmpty()) return Report(ask, emptyList(), Picks())
        // Four with a page is a whole answer; six attempts is the cap, because every page is a
        // request and a chat that reads ten to keep three is a chat that takes half a minute.
        val open = mutableListOf<Venue>()
        val hosts = mutableSetOf<String>()
        var attempts = 0
        for (venue in found) {
            if (open.size >= MAX_CHOICES || attempts >= MAX_PAGES) break
            if (!readable(venue.url)) continue
            // One address per place: two records of the same winery are one recommendation, and
            // the second page would be the same page read twice.
            if (!hosts.add(fold(venue.host))) continue
            attempts++
            val text = sources.page(venue.url)
            if (text.isBlank()) continue
            // A venue's website can lapse and be taken over: a live run found one Portland wine
            // merchant's domain serving a crypto-casino page. The map still says "wine", the page
            // does not — and a page that never names the place is not evidence of the place.
            if (!pageBelongs(text, venue)) continue
            val checked = venue.copy(
                text = pageDigest(text, venue.name),
                closed = closedReason(text).ifBlank { venue.closed }
            )
            if (checked.closed.isNotBlank()) continue
            if (!inTown(checked, ask)) continue
            if (open.none { fold(it.name) == fold(checked.name) }) open += checked
        }
        if (open.isEmpty()) return Report(ask, emptyList(), Picks())
        val raw = sources.pick(prompt(brief), evidence(ask, open))
        return Report(ask, open, parse(raw, open))
    }

    /**
     * The order pages are worth reading in: the place with hours and a bar's kind first.
     *
     * The evidence budget is four pages, so the order decides what the model ever sees. Hours mean
     * the map says when it is open — which is what "tonight" needs — and a bar, pub, restaurant or
     * cafe is a place to sit down, where a wine shop with no hours is not dinner.
     */
    fun rank(venues: List<Venue>): List<Venue> = venues.withIndex()
        .sortedWith(compareByDescending<IndexedValue<Venue>> { venueScore(it.value) }.thenBy { it.index })
        .map { it.value }

    /** What makes a place a better answer to "dinner tonight": open hours, and somewhere to sit. */
    fun venueScore(venue: Venue): Int {
        var score = 0
        if (venue.hours.isNotBlank()) score += 2
        val kind = fold(venue.snippet)
        if (SIT_DOWN.any { kind.contains(it) }) score += 1
        return score
    }

    /**
     * Whether the page read is about this place, and about wine.
     *
     * A lapsed domain that now sells something else is a real hazard on a map built by volunteers:
     * the record says wine bar, the page says crypto casino. The page has to name the place (one
     * word of its name longer than three letters) and use one wine word. A page that fails either
     * check is not evidence, so the venue is dropped rather than trusted.
     */
    fun pageBelongs(text: String, venue: Venue): Boolean {
        val page = fold(text)
        if (page.isBlank()) return false
        if (!PAGE_WINE_WORDS.any { page.contains(it) }) return false
        val words = fold(venue.name).split(' ', '\'', '-', '&')
            .filter { it.length > 3 && it !in GENERIC }
        return words.isEmpty() || words.any { page.contains(it) }
    }

    /**
     * Whether the venue's own evidence places it in the asked town and region.
     *
     * The address is what the map knows and the page is what the venue says, so either can carry
     * the town. The region is accepted as its two-letter form *in the address only*: "Portland, OR"
     * is a fact, while the word "or" anywhere in a paragraph is not.
     */
    fun inTown(venue: Venue, ask: Ask): Boolean {
        val parts = ask.where.split(',').map { fold(it) }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return true
        val address = fold(venue.address)
        val page = fold(venue.text)
        return parts.all { part ->
            page.contains(part) || address.contains(part) ||
                (part.length > 2 && Regex("\\b${Regex.escape(part.take(2))}\\b").containsMatchIn(address))
        }
    }

    // ---------------------------------------------------------------- writing the reply

    /**
     * The reply, written here rather than by the model.
     *
     * Every name, host and source is copied from the record the app fetched, so the answer cannot
     * contain a restaurant that is not in the evidence. What the model contributed is the order and
     * the reason, which is the part a person actually wants.
     */
    fun answer(picks: Picks, venues: List<Venue>, ask: Ask, on: String = today()): String {
        val chosen = picks.picks.mapNotNull { pick -> venues.getOrNull(pick.line - 1)?.let { it to pick } }
        if (chosen.isEmpty()) return nothing(ask, on)
        return buildString {
            append(chosen.size)
            append(if (chosen.size == 1) " place" else " places")
            append(" in ").append(placeWords(ask)).append(", from pages read today:")
            chosen.forEach { (venue, pick) ->
                append("\n\n- ").append(venue.name)
                if (venue.host.isNotBlank()) append(" (").append(venue.host).append(')')
                append(" — ").append(pick.why)
            }
            if (picks.skips.isNotEmpty()) {
                picks.skips.forEach { skip ->
                    venues.getOrNull(skip.line - 1)?.let { venue ->
                        append("\n\nSkip: ").append(venue.name).append(" — ").append(skip.why)
                    }
                }
            }
            val note = cleanNote(picks.note)
            if (note.isNotBlank()) append("\n\n").append(note)
            append("\n\nChecked against pages fetched ").append(on)
            append(". Hours and closures change: call before you go.")
        }
    }

    /**
     * The model's closing prose, without numbers it only half saw.
     *
     * A live run's page text was cut mid-telephone-number and the note offered "call (503)-4" — a
     * phone number nobody can dial, presented as fact. Hours ("until 22:00") are two digits a time
     * and survive; a run of three digits or more is a partial phone number, an address fragment or a
     * price the composer never verified, and its sentence is dropped.
     */
    fun cleanNote(note: String): String =
        note.split(Regex("(?<=[.!?])\\s+"))
            .filter { !Regex("\\d{3,}").containsMatchIn(it) }
            .joinToString(" ")
            .trim()

    /** The read failed, so the evidence is printed unranked: the names are still real. */
    fun fallback(ask: Ask, venues: List<Venue>, on: String = today()): String {
        val open = venues.filter { it.closed.isBlank() }.take(MAX_PICKS)
        if (open.isEmpty()) return nothing(ask, on)
        return buildString {
            append("I found these in ").append(placeWords(ask))
            append(" just now but could not rank them, so here they are as the pages describe them:")
            open.forEach { venue ->
                append("\n\n- ").append(venue.name)
                if (venue.host.isNotBlank()) append(" (").append(venue.host).append(')')
                if (venue.snippet.isNotBlank()) append(" — ").append(venue.snippet.take(150))
            }
            append("\n\nChecked ").append(on).append(". Hours and closures change: call before you go.")
        }
    }

    /** The search worked and the evidence was there, but nothing in it was worth the trip. */
    fun nothing(ask: Ask, on: String = today()): String =
        "I read the pages for ${placeWords(ask)} today and none of the places they still describe is " +
            "a wine place I would send you to tonight, so I am not going to name one from memory. " +
            "A narrower area, a neighbourhood name, or asking again tomorrow is a better question " +
            "than a guess. (Pages fetched $on.)"

    /** Looked, but nothing named a place that could be confirmed open, in town, with a page. */
    fun noEvidence(ask: Ask): String =
        "I looked for ${ask.subject} in ${placeWords(ask)} just now and found no place I could " +
            "confirm, from its own page, was open and in ${placeWords(ask)}. I will not answer from " +
            "memory — that is how you end up at a restaurant that closed two years ago.\n\n" +
            "Naming the neighbourhood usually helps (\"wine bars in the Pearl District\"), and if " +
            "the phone had no connection, ask again when it does."

    /**
     * The lookup was refused: say so plainly, and say nothing about anywhere.
     *
     * This is the reply the whole feature exists to make possible — the alternative is the answer
     * this app used to give, which was three restaurants that had closed years earlier.
     */
    fun cannotConfirm(ask: Ask, why: String = ""): String =
        "I could not check anywhere in ${placeWords(ask)} just now, so I have not named a place. " +
            (why.ifBlank { "The lookup is busy and did not answer." }) +
            " Nothing was guessed. Ask again in a minute and it will read the pages properly."

    /** The question was about a place, but nowhere was named and the phone cannot say where it is. */
    fun askWhere(ask: Ask): String =
        "I can look that up properly — real pages, fetched now — but I need the town or the " +
            "neighbourhood: ask again with it (\"${ask.subject} in Portland\") and I will read what " +
            "the pages say tonight instead of answering from memory."

    private fun placeWords(ask: Ask): String = ask.place.ifBlank { "your area" }
}
