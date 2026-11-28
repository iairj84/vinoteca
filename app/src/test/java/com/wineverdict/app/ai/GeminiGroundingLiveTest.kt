package com.wineverdict.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The live proof for grounded discovery, run with the user's own key and never committing it.
 *
 *     GEMINI_API_KEY=… ./gradlew :app:testDebugUnitTest -Plive --tests "*GeminiGroundingLiveTest*"
 *
 * Without the variable the test reports itself as skipped rather than failing, so the ordinary suite
 * stays offline. What it prints is what the app would do: the pages Google cited, the hosts they
 * resolved to, and whether each is the venue's own site rather than a listicle.
 */
class GeminiGroundingLiveTest {

    @Test
    fun portlandCitesVenuePagesNotListicles() = runBlocking {
        val key = System.getenv("GEMINI_API_KEY").orEmpty().trim()
        assumeTrue("no GEMINI_API_KEY in this shell", key.isNotBlank())

        val ask = PlaceScout.ask("Where should I go for dinner tonight in Portland with good wine?")
        requireNotNull(ask) { "the question did not classify as a place question" }
        val settled = ask.copy(where = "Portland, Oregon")

        val grounding = GeminiGrounding(key)
        val venues = grounding.find(settled)

        val report = buildString {
            append("QUESTION  ").append(settled.subject).append('\n')
            append("MODEL     ").append(GeminiGrounding.MODEL).append('\n')
            append("VENUES    ").append(venues.size).append('\n')
            venues.forEach { venue ->
                append("  - ").append(venue.name).append("  ").append(venue.url).append('\n')
            }
            append("RESOLVED  ").append(venues.count { it.host.isNotBlank() }).append(" with a host\n")
        }
        File("build/reports/live-grounding.txt").apply { parentFile?.mkdirs() }.writeText(report)
        println(report)

        assertTrue("Google cited no venue pages for Portland", venues.isNotEmpty())
        assertTrue(
            "a citation resolved to Google's own redirector: ${venues.map { it.url }}",
            venues.all { GeminiGrounding.usableHost(it.host) }
        )
    }
}
