package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The picture a page declares for itself.
 *
 * A wrong answer here is a thumbnail that never appears on the bottle page, so the shapes real
 * shops and producers use are covered: absolute, relative, protocol-relative, the Twitter card
 * when there is no Open Graph one, and the declarations that must never be turned into a file.
 */
class PageImageTest {

    private val lookup = WebLookup()

    private val page = "https://shop.example.com/wines/stoller-rose-2025"

    @Test
    fun openGraphImageIsTaken() {
        val html = """<head><meta property="og:image" content="https://shop.example.com/i.jpg"></head>"""
        assertEquals("https://shop.example.com/i.jpg", lookup.imageUrl(html, page))
    }

    @Test
    fun attributeOrderDoesNotMatter() {
        val html = """<meta content="/img/bottle.jpg" property="og:image">"""
        assertEquals("https://shop.example.com/img/bottle.jpg", lookup.imageUrl(html, page))
    }

    @Test
    fun aRelativePathIsResolvedAgainstThePage() {
        val html = """<meta property="og:image" content="img/bottle.jpg">"""
        assertEquals("https://shop.example.com/wines/img/bottle.jpg", lookup.imageUrl(html, page))
    }

    @Test
    fun aProtocolRelativePathIsAskedForOverHttps() {
        val html = """<meta property="og:image" content="//cdn.example.net/bottle.jpg">"""
        assertEquals("https://cdn.example.net/bottle.jpg", lookup.imageUrl(html, page))
    }

    @Test
    fun anHttpPictureIsAskedForOverHttps() {
        val html = """<meta property="og:image" content="http://cdn.example.net/bottle.jpg">"""
        assertEquals("https://cdn.example.net/bottle.jpg", lookup.imageUrl(html, page))
    }

    @Test
    fun theTwitterCardAnswersWhenThereIsNoOpenGraph() {
        val html = """<meta name="twitter:image" content="https://cdn.example.net/bottle.jpg">"""
        assertEquals("https://cdn.example.net/bottle.jpg", lookup.imageUrl(html, page))
    }

    @Test
    fun openGraphWinsOverTheTwitterCard() {
        val html = """
            <meta name="twitter:image" content="https://cdn.example.net/twitter.jpg">
            <meta property="og:image" content="https://cdn.example.net/open-graph.jpg">
        """.trimIndent()
        assertEquals("https://cdn.example.net/open-graph.jpg", lookup.imageUrl(html, page))
    }

    @Test
    fun aDataUriIsNotAPictureToKeep() {
        val html = """<meta property="og:image" content="data:image/jpeg;base64,AAAA">"""
        assertNull(lookup.imageUrl(html, page))
    }

    @Test
    fun anotherOfThePagesCardsIsNotThePicture() {
        val html = """<meta property="og:site_name" content="Example Wines">"""
        assertNull(lookup.imageUrl(html, page))
    }

    @Test
    fun aPageThatDeclaresNoPictureHasNone() {
        assertNull(lookup.imageUrl("<html><head><title>Wine</title></head></html>", page))
    }
}
