package com.wineverdict.app.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The question that failed, asked live, through the production path.
 *
 * The sommelier once answered "Where should I go for dinner tonight in Portland with good wine?"
 * with three restaurants that had been shut for years. The fix is only a fix if this run shows a
 * reply built from pages fetched while it ran, so this instrument drives the same
 * [PlaceScout.research] the chat calls, with the same three sources the chat uses — the map for
 * discovery, the app's page reader for evidence, the provider for the pick — and prints every step:
 * the query, the map's addresses, each page's length and closure verdict, the numbered evidence, the
 * model's raw answer, and the reply a user would read.
 *
 * It asserts the promise rather than the wording: at least two venues, each with a page from this
 * run, none marked closed, each placed in Portland, Oregon by its own evidence, and a reply that
 * names them. It is excluded from the offline test task and needs a key (`~/.freebuff/gemini.txt`):
 * run `./gradlew :app:testDebugUnitTest -Plive --tests "*LivePlaceResearchTest*"`.
 */
class LivePlaceResearchTest {

    private val transcript = StringBuilder()

    private fun say(line: String) {
        println(line)
        transcript.appendLine(line)
        // Written as it goes: the interesting part of a refusal is the failure, and a test that
        // throws before its last line would otherwise keep no record of it.
        File("build/reports").mkdirs()
        File("build/reports/live-place-research.txt").writeText(transcript.toString())
    }

    @Test
    fun portlandDinnerIsReal() = runBlocking {
        val key = LiveGemini.key()
        assumeTrue("no Gemini key in ~/.freebuff/gemini.txt", key != null)

        val question = "Where should I go for dinner tonight in Portland with good wine?"
        val parsed = PlaceScout.ask(question)
        assumeTrue("the question was not classified as a place question", parsed != null)
        // The region the device would settle on if it were standing in the town it was asked
        // about; the harness pins it rather than pretending the build machine is in Oregon.
        val ask = parsed!!.where.let {
            parsed.copy(where = if (it.contains(',')) it else PlaceScout.townWithRegion(it, "Oregon"))
        }

        say("QUESTION  $question")
        say("CLASSIFIED kind=${ask.kind} where=${ask.where} nearMe=${ask.nearMe}")
        val town = ask.where.substringBefore(',').trim()
        say("MAP QUERY  town: ${OsmPlaces.townUrl(ask.where)}")
        say("MAP QUERY  terms: ${OsmPlaces.terms(ask.kind).joinToString(", ")} (bounded to the town's box)")
        say("MAP QUERY  fallback: ${OsmPlaces.query(town, ask.kind).lines().first()}")

        val venues = mutableListOf<PlaceScout.Venue>()
        val sources = object : PlaceScout.Sources {
            override suspend fun discover(ask: PlaceScout.Ask): List<PlaceScout.Venue> {
                val started = System.currentTimeMillis()
                val places = try {
                    OsmPlaces.find(ask)
                } catch (e: AiException) {
                    say("\nMAP REFUSED: ${e.message}")
                    e.suppressed.forEach { say("  attempt: ${it.message}") }
                    throw e
                }
                say("\nMAP  ${places.size} places in ${System.currentTimeMillis() - started} ms")
                places.forEach {
                    say("  ${it.name} | ${it.address} | ${it.hours.ifBlank { "hours: (none)" }} | ${it.website}")
                }
                return places.map(OsmPlaces::asVenue)
            }

            override suspend fun page(url: String): String {
                val started = System.currentTimeMillis()
                val text = withTimeoutOrNull(8_000) {
                    runCatching { WebLookup().pageText(url, 2_000) }.getOrNull()
                }.orEmpty()
                say("\nPAGE  $url  ${System.currentTimeMillis() - started} ms  ${text.length} chars")
                if (text.isNotBlank()) {
                    say("  closed-verdict: '${PlaceScout.closedReason(text)}'")
                    say("  page says: ${text.take(260)}")
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
                    key!!
                )
                say("\nMODEL  ${reply.code} finish=${reply.finish}\n${reply.content}")
                return reply.content
            }
        }

        val report = PlaceScout.research(ask, "I like dry Riesling and restrained oak", sources)
        venues += report.venues
        val reply = report.text()

        say("\nACCEPTED")
        report.venues.forEach { venue ->
            say("  ${venue.name} | ${venue.host} | ${venue.address} | closed='${venue.closed}'")
        }
        say("\nREPLY A USER WOULD READ\n$reply")

        assertTrue("no venue survived the evidence checks", report.venues.size >= 2)
        assertTrue("the model's pick could not be read", report.picks != null)
        assertTrue("fewer than two picks", report.picks!!.picks.size >= 2)
        report.venues.forEach { venue ->
            assertTrue("${venue.name} has no page text", venue.text.isNotBlank())
            assertTrue("${venue.name} is marked closed: ${venue.closed}", venue.closed.isBlank())
            assertTrue("${venue.name} is not evidenced in ${ask.where}", PlaceScout.inTown(venue, ask))
        }
        val named = report.venues.count { reply.contains(it.name) }
        assertTrue("the reply names $named venues, not two", named >= 2)
    }
}
