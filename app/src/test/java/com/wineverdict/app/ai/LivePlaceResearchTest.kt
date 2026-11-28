package com.wineverdict.app.ai

import com.wineverdict.app.data.Region
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The questions that made this feature necessary, asked live, through the production path.
 *
 * Three runs, because three things had to be true and each is easy to fake:
 *
 *  1. **"Portland" with no state** resolves through the region the phone reports, and the reply
 *     says which Portland it used and why. The map knows twelve; the phone settles it.
 *  2. **A name that really is ambiguous** (Vancouver: British Columbia and Washington) is settled
 *     the same way, and the alternative is printed so the choice is visible.
 *  3. **A map that refuses** produces the honest "I could not check anywhere" reply, inside the
 *     app's own timeout, naming nothing — and the exact question still works afterwards.
 *
 * It drives the same [PlaceScout.researchOrSay] and [PlaceScout.Sources] the chat uses (the map,
 * the app's page reader, the provider) and writes every step to
 * `app/build/reports/live-place-research.txt`. Excluded from the offline task; needs a Gemini key:
 * `./gradlew :app:testDebugUnitTest -Plive --tests "*LivePlaceResearchTest*"`.
 */
class LivePlaceResearchTest {

    private val transcript = StringBuilder()

    /** One file per case: JUnit makes a new instance per test, so one shared file would be rewritten. */
    private var reportFile = "live-place-research.txt"

    /** Page fetches this case spent, counted beside the map requests. */
    private var pages = 0

    private fun say(line: String) {
        println(line)
        transcript.appendLine(line)
        File("build/reports").mkdirs()
        File("build/reports/$reportFile").writeText(transcript.toString())
    }

    private fun rule(title: String) = say("\n=== $title ===")

    /** The same sources the chat hands the research, with each step printed as it happens. */
    private fun sources(region: Region, key: String) = object : PlaceScout.Sources {

        override suspend fun region(): Region? {
            say("DEVICE REGION  ${region.words.ifBlank { "(unknown)" }}  [${region.source.ifBlank { "none" }}]")
            return region
        }

        override suspend fun towns(ask: PlaceScout.Ask): List<PlaceScout.Town> {
            val started = System.currentTimeMillis()
            val towns = OsmPlaces.towns(ask.where)
            say("MAP TOWNS  \"${ask.where}\" -> ${towns.size} in ${System.currentTimeMillis() - started} ms")
            towns.forEach { say("  ${it.label}  [${it.country}]  box=${it.box}") }
            return towns
        }

        override suspend fun discover(ask: PlaceScout.Ask, box: String): List<PlaceScout.Venue> {
            say("SETTLED TOWN  ${ask.where}${if (ask.basis.isNotBlank()) " — ${ask.basis}" else ""}")
            val places = OsmPlaces.find(ask, box)
            say("MAP PLACES  ${places.size}")
            places.forEach {
                say("  ${it.name} | ${it.address} | ${it.hours.ifBlank { "hours: (none)" }} | ${it.website}")
            }
            return places.map(OsmPlaces::asVenue)
        }

        override suspend fun page(url: String): String {
            pages++
            val started = System.currentTimeMillis()
            val text = withTimeoutOrNull(8_000) {
                runCatching { WebLookup().pageText(url, 2_000) }.getOrNull()
            }.orEmpty()
            say("\nPAGE  $url  ${System.currentTimeMillis() - started} ms  ${text.length} chars")
            if (text.isNotBlank()) {
                say("  closed-verdict: '${PlaceScout.closedReason(text)}'")
                say("  page says: ${text.take(240)}")
            }
            return text
        }

        override suspend fun pick(system: String, evidence: String): String {
            say("\nEVIDENCE\n$evidence")
            val reply = LiveGemini.post(
                LiveGemini.payload(
                    system = system,
                    turn = evidence,
                    maxTokens = 900,
                    thinking = Thinking.LOW,
                    temperature = 0.2,
                    jsonMode = true
                ),
                key
            )
            say("\nMODEL  ${reply.code} finish=${reply.finish}\n${reply.content}")
            return reply.content
        }
    }

    private fun osRegion() = Region(
        city = "Portland",
        state = "Oregon",
        country = "United States",
        countryCode = "us",
        source = "your location"
    )

    @Test
    fun aBarePortlandUsesThePhonesRegionAndSaysSo() = runBlocking {
        val key = LiveGemini.key()
        assumeTrue("no Gemini key in ~/.freebuff/gemini.txt", key != null)

        reportFile = "live-place-1-portland.txt"
        rule("CASE 1 — the exact question, \"Portland\" with no state, phone in Oregon")
        val question = "Where should I go for dinner tonight in Portland with good wine?"
        val ask = PlaceScout.ask(question)!!
        say("INPUT  $question")
        say("CLASSIFIED  kind=${ask.kind} where=\"${ask.where}\" nearMe=${ask.nearMe}")

        OsmPlaces.resetRequests()
        val started = System.currentTimeMillis()
        val report = PlaceScout.research(ask, sources(osRegion(), key!!))
        val reply = report.text()
        val elapsed = System.currentTimeMillis() - started

        say("\nCALLS  ${OsmPlaces.requests} map requests, $pages page fetches, ${elapsed} ms")
        say("\nACCEPTED (every venue whose page passed the checks)")
        report.venues.forEach { venue ->
            say("  ${venue.name} | ${venue.host} | ${venue.address} | closed='${venue.closed}'")
            say("    quote: ${PlaceScout.pageQuote(venue)}")
        }
        val picked = report.picks?.picks.orEmpty()
            .mapNotNull { pick -> report.venues.getOrNull(pick.line - 1) }
        say("\nPICKED (the venues the reply recommends)")
        picked.forEach { venue -> say("  ${venue.name} — quote: ${PlaceScout.pageQuote(venue)}") }
        say("\nREPLY A USER WOULD READ\n$reply")
        say("\nTIMING  ${elapsed} ms")

        assertTrue("the bare town was not settled", report.ask.where.contains(","))
        assertEqualsValue("Portland, Oregon", report.ask.where)
        assertTrue("no venue survived the evidence checks", report.venues.size >= 2)
        assertTrue("the model's pick could not be read", report.picks != null)
        assertTrue("fewer than two venues picked", picked.size >= 2)
        report.venues.forEach { venue ->
            assertTrue("${venue.name} has no page text", venue.text.isNotBlank())
            assertTrue("${venue.name} is marked closed: ${venue.closed}", venue.closed.isBlank())
            assertTrue("${venue.name} is not evidenced in Oregon", PlaceScout.inTown(venue, report.ask))
        }
        // What the reply itself must show, venue by venue: name, host and the page's own words.
        picked.forEach { venue ->
            assertTrue("${venue.name} has no host", venue.host.isNotBlank())
            assertTrue("${venue.name} is not in the reply", reply.contains(venue.name))
            assertTrue("${venue.name}: the host is not in the reply", reply.contains(venue.host))
            assertTrue(
                "${venue.name}: the reply does not quote its page",
                reply.contains(PlaceScout.pageQuote(venue).take(40))
            )
        }
        assertTrue("the reply does not name the town used", reply.contains("Portland, Oregon"))
        assertTrue("the reply does not say why", reply.contains("your phone's region is Oregon"))
        assertTrue("too many map requests for one question: ${OsmPlaces.requests}", OsmPlaces.requests <= 12)
    }

    @Test
    fun anAmbiguousTownIsSettledByTheDevicesRegion() = runBlocking {
        val key = LiveGemini.key()
        assumeTrue("no Gemini key in ~/.freebuff/gemini.txt", key != null)

        reportFile = "live-place-2-vancouver.txt"
        rule("CASE 2 — \"Vancouver\": British Columbia or Washington, phone in Washington")
        val question = "Where should I go for dinner tonight in Vancouver with good wine?"
        val ask = PlaceScout.ask(question)
            ?: throw AssertionError("the classifier did not see a place question in: $question")
        say("INPUT  $question")
        say("CLASSIFIED  kind=${ask.kind} where=\"${ask.where}\"")

        val region = Region(
            city = "Seattle",
            state = "Washington",
            country = "United States",
            countryCode = "us",
            source = "your location"
        )
        OsmPlaces.resetRequests()
        val report = PlaceScout.research(ask, sources(region, key!!))
        val reply = report.text()
        say("\nREPLY A USER WOULD READ\n$reply")
        say("CALLS  ${OsmPlaces.requests} map requests, $pages page fetches")

        assertEqualsValue("Vancouver, Washington", report.ask.where)
        assertTrue("the reply does not name the town used", reply.contains("Vancouver, Washington"))
        assertTrue("the reply does not say why", reply.contains("your phone's region is Washington"))
    }

    @Test
    fun aRefusedMapSaysSoAndTheQuestionStillWorksAfterwards() = runBlocking {
        val key = LiveGemini.key()
        assumeTrue("no Gemini key in ~/.freebuff/gemini.txt", key != null)

        val ask = PlaceScout.ask("Where should I go for dinner tonight in Portland with good wine?")!!
        val liveNominatim = OsmPlaces.nominatim
        val liveOverpass = OsmPlaces.overpass
        try {
            reportFile = "live-place-3-refused.txt"
            rule("CASE 3 — the map refuses: dead hosts, nothing cached")
            OsmPlaces.nominatim = "http://127.0.0.1:1/nominatim"
            OsmPlaces.overpass = listOf("http://127.0.0.1:1/overpass")
            OsmPlaces.resetRequests()
            say("ENDPOINTS  ${OsmPlaces.nominatim}")
            say("ENDPOINTS  ${OsmPlaces.overpass}")

            val started = System.currentTimeMillis()
            val reply = PlaceScout.researchOrSay(ask, sources(osRegion(), key!!))
            val elapsed = System.currentTimeMillis() - started
            say("\nREPLY A USER WOULD READ\n$reply")
            say("\nCALLS  ${OsmPlaces.requests} map requests, $pages page fetches, ${elapsed} ms " +
                "(the app's own budget, not a hang)")

            assertTrue("not the honest refusal: $reply", reply.startsWith("I could not check anywhere in Portland"))
            assertTrue("the refusal invents a place", !reply.contains("Stem") && !reply.contains("Wine Bar"))
            assertTrue("the refusal took too long: ${elapsed}ms", elapsed < 60_000)
            assertTrue("no request was even attempted", OsmPlaces.requests > 0)
        } finally {
            OsmPlaces.nominatim = liveNominatim
            OsmPlaces.overpass = liveOverpass
        }

        reportFile = "live-place-3b-restored.txt"
        rule("CASE 3b — the same question again, live hosts restored")
        OsmPlaces.resetRequests()
        val report = PlaceScout.research(ask, sources(osRegion(), key!!))
        val reply = report.text()
        say("\nREPLY A USER WOULD READ\n$reply")
        say("CALLS  ${OsmPlaces.requests} map requests, $pages page fetches")

        assertTrue("the exact question stopped working", report.venues.size >= 2)
        assertTrue("the reply names no venue", reply.contains(report.venues.first().name))
        assertTrue(
            "a closed venue reached the reply",
            report.venues.none { it.closed.isNotBlank() }
        )
        assertTrue("the reply does not name the town", reply.contains("Portland, Oregon"))
    }

    private fun assertEqualsValue(expected: String, actual: String) {
        if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>")
    }
}
