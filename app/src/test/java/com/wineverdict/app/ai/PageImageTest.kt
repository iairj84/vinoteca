package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun theProductsOwnPictureBeatsTheShopsBanner() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","name":"Thesis Chardonnay","image":"https://cdn.example.net/thesis-bottle.jpg"}
            </script>
            <meta property="og:image" content="https://shop.example.com/uncork-life.png">
        """.trimIndent()
        val candidates = lookup.pictureCandidates(html, page, listOf("thesis"))
        assertEquals("https://cdn.example.net/thesis-bottle.jpg", candidates.first())
    }

    @Test
    fun anImageThatNamesTheWineBeatsTheCard() {
        val html = """
            <meta property="og:image" content="https://shop.example.com/banner.png">
            <img src="/img/19zgy-lg.png" alt="2019 Geyserville">
        """.trimIndent()
        val candidates = lookup.pictureCandidates(html, page, listOf("geyserville"))
        assertEquals("https://shop.example.com/img/19zgy-lg.png", candidates.first())
    }

    @Test
    fun aLogoIsNeverACandidate() {
        val html = """<img src="https://cdn.example.net/stoller-logo.webp" alt="Stoller logo">"""
        assertTrue(lookup.pictureCandidates(html, page, listOf("stoller")).isEmpty())
    }

    @Test
    fun aCorkIsNeverACandidate() {
        // What came back for a Barbaresco: the producer's cork, on a beige field.
        val html = """<img src="/wp-content/uploads/tappo-2.png" alt="Produttori del Barbaresco">"""
        assertTrue(lookup.pictureCandidates(html, page, listOf("barbaresco")).isEmpty())
    }

    @Test
    fun aBannerIsNeverACandidate() {
        val html = """<meta property="og:image" content="https://shop.example.com/banner.png">"""
        assertTrue(lookup.pictureCandidates(html, page).isEmpty())
    }

    @Test
    fun aLazilyLoadedProductPictureIsStillFound() {
        val html = """<img data-src="https://cdn.example.net/19zgy.png" alt="Geyserville">"""
        assertEquals(
            "https://cdn.example.net/19zgy.png",
            lookup.pictureCandidates(html, page, listOf("geyserville")).first()
        )
    }
}
