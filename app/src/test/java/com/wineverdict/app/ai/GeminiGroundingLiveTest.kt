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
 * stays offline. What it prints is what the app would do: the places Google named, the pages it cited
 * for them, and whether each citation is a venue's own site rather than Google's redirector.
 */
class GeminiGroundingLiveTest {

    @Test
    fun portlandNamesVenuesAndCitesTheirPages() = runBlocking {
        val key = System.getenv("GEMINI_API_KEY").orEmpty().trim()
        assumeTrue("no GEMINI_API_KEY in this shell", key.isNotBlank())

        val ask = PlaceScout.ask("Where should I go for dinner tonight in Portland with good wine?")
        requireNotNull(ask) { "the question did not classify as a place question" }
        val settled = ask.copy(where = "Portland, Oregon")

        val venues = GeminiGrounding(key).find(settled)

        val report = buildString {
            append("QUESTION  ").append(settled.subject).append('\n')
            append("MODEL     ").append(GeminiGrounding.MODEL).append('\n')
            append("PROPOSALS ").append(venues.size).append('\n')
            venues.forEach { venue ->
                append("  - ").append(venue.name)
                append("  | ").append(venue.town.ifBlank { "(no town)" })
                append("  | ").append(venue.url.ifBlank { "(no page: confirmed by search)" })
                append('\n')
            }
            append("CITED     ").append(venues.count { it.url.isNotBlank() }).append(" with a page\n")
        }
        File("build/reports/live-grounding.txt").apply { parentFile?.mkdirs() }.writeText(report)
        println(report)

        assertTrue("Google named nowhere in Portland", venues.isNotEmpty())
        assertTrue(
            "a citation resolved to Google's own redirector: ${venues.map { it.url }}",
            venues.filter { it.url.isNotBlank() }
                .all { GeminiGrounding.usableHost(runCatching { java.net.URI(it.url).host }.getOrDefault("")) }
        )
    }

    /**
     * The town from the complaint: one wine bar downtown, and the app said "nowhere".
     *
     * It prints what Google names and what it suggests one town over. It asserts only that some
     * places were named — the page rules are the research's job, and they run on whatever this
     * returns.
     */
    @Test
    fun tigardNamesItsWineBarRatherThanNothing() = runBlocking {
        val key = System.getenv("GEMINI_API_KEY").orEmpty().trim()
        assumeTrue("no GEMINI_API_KEY in this shell", key.isNotBlank())

        val ask = PlaceScout.ask("where should I go for wine tonight in Tigard, Oregon?")
        requireNotNull(ask) { "the question did not classify as a place question" }
        val grounding = GeminiGrounding(key)
        val venues = grounding.find(ask)

        val report = buildString {
            append("QUESTION  ").append(ask.subject).append(" in ").append(ask.where).append('\n')
            append("PROPOSALS ").append(venues.size).append('\n')
            venues.forEach { append("  - ").append(it.name).append("  | ").append(it.town) }
            append("\nNEARBY    ").append(grounding.nearby(ask).joinToString("; ")).append('\n')
        }
        File("build/reports/live-grounding-tigard.txt").apply { parentFile?.mkdirs() }.writeText(report)
        println(report)

        assertTrue("Google named nowhere in Tigard: $report", venues.isNotEmpty())
    }
}
