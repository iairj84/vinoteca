package com.wineverdict.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery through Google's own search grounding.
 *
 * The shape of a real grounded response, copied from the API docs: `groundingMetadata.groundingChunks`
 * with a `web.uri` per citation, each wrapped in Google's redirect. What matters here is that only
 * the venue's own page survives: a citation to Google's redirector, a duplicate site, or a social
 * page is not evidence about a restaurant, and the research that follows would be built on nothing.
 */
class GeminiGroundingTest {

    private class Fake(
        private val response: String?,
        private val pages: Map<String, String> = emptyMap()
    ) : GeminiGrounding.Transport {
        var posted = 0
        var lastBody = ""
        override suspend fun post(url: String, key: String, body: String): String? {
            posted++
            lastBody = body
            return response
        }

        override suspend fun resolve(url: String): String? = pages[url]
    }

    private val ask = PlaceScout.ask("where should I go in Portland, Oregon for good wine tonight?")!!

    private fun grounded(vararg chunks: Pair<String, String>): String {
        val parts = chunks.joinToString(",") { (title, uri) ->
            """{"web":{"uri":"$uri","title":"$title"}}"""
        }
        return """{"candidates":[{"content":{"parts":[{"text":"Here are some"}]},
            "groundingMetadata":{"webSearchQueries":["wine bars Portland"],
            "groundingChunks":[$parts]}}]}"""
    }

    @Test
    fun theCitedVenuePagesBecomeTheCandidates() = runBlocking {
        val transport = Fake(
            grounded(
                "Stem Wine Bar PDX | Vietnamese Food & Wine" to "https://vertexaisearch.cloud.google.com/r/1",
                "Wine & Growl | Portland" to "https://vertexaisearch.cloud.google.com/r/2"
            ),
            pages = mapOf(
                "https://vertexaisearch.cloud.google.com/r/1" to "https://www.stemwinebarpdx.com/",
                "https://vertexaisearch.cloud.google.com/r/2" to "https://wineandgrowl.com/"
            )
        )
        val venues = GeminiGrounding("k", transport = transport).find(ask)

        assertEquals(listOf("Stem Wine Bar PDX", "Wine & Growl"), venues.map { it.name })
        // The resolved address, not the redirect: this is the page the research fetches and the
        // host the reply prints.
        assertEquals("https://www.stemwinebarpdx.com/", venues[0].url)
        assertTrue(transport.lastBody.contains("google_search"))
    }

    @Test
    fun aRedirectThatLandsOnGooglesOwnHostsIsNotAVenue() = runBlocking {
        val transport = Fake(
            grounded(
                "Some listicle" to "https://vertexaisearch.cloud.google.com/r/1",
                "A bar" to "https://vertexaisearch.cloud.google.com/r/2"
            ),
            pages = mapOf(
                "https://vertexaisearch.cloud.google.com/r/1" to "https://support.google.com/legal",
                "https://vertexaisearch.cloud.google.com/r/2" to "https://abar.example/"
            )
        )
        val venues = GeminiGrounding("k", transport = transport).find(ask)
        assertEquals(listOf("A bar"), venues.map { it.name })
    }

    @Test
    fun oneSiteCitedTwiceIsOneVenue() = runBlocking {
        val transport = Fake(
            grounded(
                "Le Pigeon" to "https://vertexaisearch.cloud.google.com/r/1",
                "Le Pigeon menu" to "https://vertexaisearch.cloud.google.com/r/2"
            ),
            pages = mapOf(
                "https://vertexaisearch.cloud.google.com/r/1" to "https://lepigeon.com/",
                "https://vertexaisearch.cloud.google.com/r/2" to "https://lepigeon.com/menu"
            )
        )
        assertEquals(1, GeminiGrounding("k", transport = transport).find(ask).size)
    }

    @Test
    fun proseThatIsNotAListIsNoAnswerRatherThanAMemoryList() = runBlocking {
        // No citations and no list shape: the model is remembering, and a sentence read as a list
        // would invent one place called "Try Le Pigeon and Nostrana".
        val memory = """{"candidates":[{"content":{"parts":[{"text":"Try Le Pigeon and Nostrana."}]}}]}"""
        assertTrue(GeminiGrounding.citations(memory).isEmpty())
        assertTrue(GeminiGrounding.list(memory).isEmpty())
        assertTrue(GeminiGrounding("k", transport = Fake(memory)).find(ask).isEmpty())
    }

    @Test
    fun aListShapedNameIsACandidateEvenWithNoCitation() = runBlocking {
        // The town's one wine bar has no website, so a prompt that demanded one hid it. A name is
        // enough to search for; the research confirms it with a page before anyone sees it.
        val answer = """{"candidates":[{"content":{"parts":[{"text":"1. Sip D'Vine | Tigard, Oregon\n2. Beaverton Wine Bar | Beaverton, Oregon"}]}}]}"""
        val found = GeminiGrounding("k", transport = Fake(answer)).find(ask)
        assertEquals(listOf("Sip D'Vine", "Beaverton Wine Bar"), found.map { it.name })
        assertEquals("Tigard, Oregon", found[0].town)
        assertTrue(found.all { it.url.isBlank() })
    }

    @Test
    fun theTownOneOverIsAskedForWhenTheTownHasNothing() = runBlocking {
        // Asked `Town | Venue` rather than `Venue | Town`: the town is what the research needs.
        val answer = """{"candidates":[{"content":{"parts":[{"text":"Beaverton, Oregon | Bar Diane"}]}}]}"""
        val transport = Fake(answer)
        assertEquals(listOf("Beaverton, Oregon"), GeminiGrounding("k", transport = transport).nearby(ask))
        assertTrue(transport.lastBody.contains("google_search"))
        assertTrue(transport.lastBody.contains("Portland, Oregon"))
    }

    @Test
    fun theReviewersClauseRidesAlongWithTheName() {
        // The answer to "where should I go" is the reason, not just the name: it is what the reply
        // quotes back, and it has to survive parsing with its quotes and full stop off.
        val proposal = GeminiGrounding.lineOf("Sip D'Vine | Tigard, Oregon | praised for its by-the-glass pours.")
        assertEquals("Sip D'Vine", proposal?.name)
        assertEquals("Tigard, Oregon", proposal?.town)
        assertEquals("praised for its by-the-glass pours", proposal?.why)
        // A line without the third field is still a candidate; it simply has nothing to quote.
        assertEquals("", GeminiGrounding.lineOf("Sip D'Vine | Tigard")?.why)
    }

    @Test
    fun thePromptAsksWhatReviewersSayAndNotWhetherTheListSuitsSomeone() = runBlocking {
        val answer = """{"candidates":[{"content":{"parts":[{"text":"Sip D'Vine | Tigard, Oregon | a bottle shop with a bar in it"}]}}]}"""
        val transport = Fake(answer)
        GeminiGrounding("k", transport = transport).find(ask)
        assertTrue(transport.lastBody.contains("what reviewers say about it"))
        assertTrue(transport.lastBody.contains("never invent praise"))
    }

    @Test
    fun aGuideOrANumberedParagraphIsNotAPlace() {
        assertEquals("Sip D'Vine", GeminiGrounding.lineOf("- Sip D'Vine | Tigard")?.name)
        assertEquals("Sip D'Vine", GeminiGrounding.lineOf("Sip D'Vine — Tigard")?.name)
        assertNull(GeminiGrounding.lineOf("The 10 Best Wine Bars in Portland (Updated 2026)"))
        assertNull(GeminiGrounding.lineOf("Here are a few places you might like."))
        assertNull(GeminiGrounding.lineOf("https://example.com | Portland"))
    }

    @Test
    fun aFailedCallFallsThroughQuietly() = runBlocking {
        assertTrue(GeminiGrounding("k", transport = Fake(null)).find(ask).isEmpty())
        // No key is no request at all: a provider the user has not configured cannot be billed.
        val silent = Fake(grounded("Stem" to "https://vertexaisearch.cloud.google.com/r/1"))
        assertTrue(GeminiGrounding("", transport = silent).find(ask).isEmpty())
        assertEquals(0, silent.posted)
    }

    @Test
    fun aPageTitleLosesTheSitesOwnSuffix() {
        assertEquals("Stem Wine Bar PDX", GeminiGrounding.nameFrom("Stem Wine Bar PDX | Vietnamese Food"))
        assertEquals("Wine & Growl", GeminiGrounding.nameFrom("Wine & Growl - Portland, OR"))
        assertEquals("Nostrana", GeminiGrounding.nameFrom("Nostrana"))
        assertFalse(GeminiGrounding.usableHost("vertexaisearch.cloud.google.com"))
        assertFalse(GeminiGrounding.usableHost("www.tripadvisor.com"))
        assertTrue(GeminiGrounding.usableHost("stemwinebarpdx.com"))
    }
}
