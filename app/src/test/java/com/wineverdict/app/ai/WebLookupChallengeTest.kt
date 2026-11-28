package com.wineverdict.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refusal is not an empty result.
 *
 * DuckDuckGo answers a burst with HTTP 202 and an anomaly page. Read as results, that page says
 * "the web had nothing", which blames the bottle, the town or the connection for something the
 * search engine did — and place research asks five searches in a row, so it meets that page often.
 */
class WebLookupChallengeTest {

    private val lookup = WebLookup()

    @Test
    fun anAnomalyPageIsAChallenge() {
        val page = "<html><head><title>DuckDuckGo</title></head><body>" +
            "<div class=\"anomaly-modal__title\">Unfortunately, bots use DuckDuckGo too.</div>" +
            "<p>Please complete the following challenge to confirm this search was made by a human.</p>" +
            "</body></html>"
        assertTrue(lookup.challenged(page))
    }

    @Test
    fun aResultsPageIsNotAChallenge() {
        val page = "<a class=\"result-link\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com\">" +
            "A bar with a wine list</a>"
        assertFalse(lookup.challenged(page))
    }

    @Test
    fun aSnippetAboutCaptchasIsStillResults() {
        val page = "<a href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com\">" +
            "How captchas and anomaly detection work</a>"
        assertFalse(lookup.challenged(page))
    }

    @Test
    fun anHonestlyEmptyPageIsAnEmptyResult() {
        assertFalse(lookup.challenged("<html><body>No results.</body></html>"))
    }
}
