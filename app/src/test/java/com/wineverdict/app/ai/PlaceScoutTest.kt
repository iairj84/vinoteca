package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three rules that make a place answer trustworthy, each tested on its own: the question is
 * recognised (and a cellar question is not), a closed place never survives the evidence, and the
 * reply is written from the fetched record rather than from anything the model remembers.
 */
class PlaceScoutTest {

    private fun hit(title: String, snippet: String = "", url: String = "https://example.com/x") =
        WebLookup.Hit(title = title, url = url, snippet = snippet)

    private fun venue(name: String, host: String = "example.com", closed: String = "") =
        PlaceScout.Venue(
            name = name,
            url = "https://$host/place",
            snippet = "A bar with a long wine list.",
            closed = closed
        )

    private fun place(question: String) = PlaceScout.ask(question)

    @Test
    fun thePortlandQuestionIsAPlaceQuestion() {
        val ask = place("Where should I go for dinner tonight in Portland with good wine?")
        assertNotNull(ask)
        assertEquals("Portland", ask!!.where)
        assertEquals(PlaceScout.Ask.Kind.RESTAURANT, ask.kind)
    }

    @Test
    fun openingABottleForDinnerStaysACellarQuestion() {
        assertNull(place("What should I open with chicken pot pie tonight?"))
        assertNull(place("What should I drink with dinner tonight?"))
        assertNull(place("Which bottle pairs with roast chicken at home?"))
    }

    @Test
    fun aWineBarWorthTryingIsAPlaceQuestion() {
        val ask = place("Any wine bars worth trying in Lisbon?")
        assertNotNull(ask)
        assertEquals("Lisbon", ask!!.where)
        assertEquals(PlaceScout.Ask.Kind.BAR, ask.kind)
    }

    @Test
    fun nearMeIsKeptForThePhoneToName() {
        val ask = place("Where can I go for a good wine bar near me?")
        assertNotNull(ask)
        assertTrue(ask!!.nearMe)
        assertTrue(ask.where.isBlank())
    }

    @Test
    fun aTownAndItsRegionStayTogether() {
        val ask = place("Where should I go for dinner in Portland, Oregon with good wine?")
        assertNotNull(ask)
        assertEquals("Portland, Oregon", ask!!.where)
    }

    @Test
    fun anAreaSurvivesAsTyped() {
        val ask = place("Where should I eat dinner in the Pearl District tonight?")
        assertNotNull(ask)
        assertEquals("the Pearl District", ask!!.where)
    }

    @Test
    fun theSearchesNameTheTown() {
        val ask = place("Any good wine bar in Lisbon?")!!
        assertTrue(PlaceScout.discoveryQueries(ask).all { it.contains("Lisbon") })
        assertTrue(PlaceScout.confirmQuery("Bar X", ask).contains("\"Bar X\""))
        assertTrue(PlaceScout.confirmQuery("Bar X", ask).contains("Lisbon"))
    }

    @Test
    fun aTitleIsReadDownToTheName() {
        assertEquals("Loyal Legion", PlaceScout.venueName("Loyal Legion - Portland, OR - Yelp"))
        assertEquals("Ava Gene's", PlaceScout.venueName("Ava Gene's | Portland, OR"))
        assertEquals("Franciska Wine Bar", PlaceScout.venueName("Yelp - Franciska Wine Bar - Portland, OR"))
        assertNull(PlaceScout.venueName("The 10 Best Wine Bars in Portland (Updated 2026)"))
        assertNull(PlaceScout.venueName("Wine Bars in Portland - Eater Portland"))
    }

    @Test
    fun namesAreHarvestedFromBusinessPagesAndDirectoryLists() {
        val ask = place("Any good wine bar in Portland?")!!
        val candidates = PlaceScout.candidates(
            listOf(
                hit("FRANK WINE BAR - Updated November 2026 - 81 Reviews - Yelp", "Portland, OR - a wine bar"),
                hit("THE BEST 10 WINE BARS IN PORTLAND - UPDATED NOVEMBER 2026 - Yelp", "Best  Wine   Bars  in  Portland , OR - Last Updated November 2026 - Division  Wines , Les Caves  Wine   Bar , Buvons, Arden , Portland Wi…"),
                hit("Loyal Legion - Portland, OR - Yelp"),
                hit("15 COZY Wine Bars in PORTLAND, Oregon | The Thirsty Local"),
                // Another city's result is not evidence about this town.
                hit("Star Bar & Grill - Boise, ID - Yelp", "A grill with a big wine list")
            ),
            ask
        )
        assertTrue(candidates.contains("FRANK WINE BAR"))
        assertTrue(candidates.contains("Loyal Legion"))
        assertTrue(candidates.contains("Division Wines"))
        assertTrue(candidates.contains("Les Caves Wine Bar"))
        assertTrue(candidates.none { it.startsWith("Portland Wi") })
        assertTrue(candidates.none { it.contains("COZY") })
        assertTrue(candidates.none { it.contains("Star Bar") })
    }

    @Test
    fun aClosureInTheConfirmationWinsAndHoursAreNotAClosure() {
        val ask = place("Where should I go for dinner in Portland with good wine?")!!
        val open = PlaceScout.confirmed(
            "Loyal Legion",
            listOf(hit("Loyal Legion - Portland, OR - Yelp", "104 taps, a long Oregon wine list")),
            ask
        )
        assertNotNull(open)
        assertTrue(open!!.closed.isBlank())

        val closed = PlaceScout.confirmed(
            "Bar Vivant",
            listOf(hit("Bar Vivant - CLOSED - Portland, OR - Yelp", "Permanently closed since 2023")),
            ask
        )
        assertNotNull(closed)
        assertTrue(closed!!.closed.isNotBlank())

        val sundays = PlaceScout.confirmed(
            "Coquine",
            listOf(hit("Coquine - Portland, OR", "Closed Sundays and Mondays, excellent wine list")),
            ask
        )
        assertNotNull(sundays)
        assertTrue(sundays!!.closed.isBlank())
    }

    @Test
    fun aPlaceInAnotherTownIsNotThePlaceAskedAbout() {
        val ask = place("Where should I go for dinner in Portland, Oregon with good wine?")!!
        assertEquals(
            null,
            PlaceScout.confirmed(
                "Bestia",
                listOf(hit("Bestia - Los Angeles, CA - Yelp", "Italian, with a serious wine list")),
                ask
            )
        )
    }

    @Test
    fun theRegionInTheQuestionIsRequiredToo() {
        val ask = place("Where should I go for dinner in Portland, Oregon with good wine?")!!
        assertEquals(
            null,
            PlaceScout.confirmed(
                "Fore Street",
                listOf(hit("Fore Street - Portland, ME - Yelp", "wood-fired cooking, a good wine list")),
                ask
            )
        )
    }

    @Test
    fun aNameNoPageConfirmsIsNotAPlace() {
        val ask = place("Where should I go for dinner in Portland with good wine?")!!
        assertNull(
            PlaceScout.confirmed(
                "Casa Fantasma",
                listOf(hit("Some other bar - Portland, OR", "a wine bar")),
                ask
            )
        )
    }

    @Test
    fun aClosedPlaceIsNeverAChoice() {
        val venues = listOf(
            venue("Loyal Legion", "loyallegion.com"),
            venue("Bar Vivant", closed = "permanently closed"),
            venue("Coquine", "coquine.com")
        )
        val raw = """
            {"picks":[{"line":2,"why":"great list"},{"line":1,"why":"the taps"},
             {"line":9,"why":"nowhere"},{"line":1,"why":"again"}],
             "skip":[{"line":3,"why":"closed Sundays"}],"note":"book ahead"}
        """.trimIndent()
        val picks = PlaceScout.parse(raw, venues)!!
        assertEquals(listOf(1), picks.picks.map { it.line })
        assertEquals(listOf(3), picks.skips.map { it.line })
        assertEquals("book ahead", picks.note)
    }

    @Test
    fun theAnswerUsesTheFetchedNamesAndCarriesTheDate() {
        val venues = listOf(
            venue("Loyal Legion", "loyallegion.com"),
            venue("Bar Vivant", closed = "permanently closed")
        )
        val ask = place("Where should I go for dinner in Portland with good wine?")!!
        val text = PlaceScout.answer(
            PlaceScout.Picks(listOf(PlaceScout.Pick(1, "a hundred Oregon taps"))),
            venues,
            ask,
            on = "2026-11-28"
        )
        assertTrue(text.contains("Loyal Legion (loyallegion.com)"))
        assertTrue(text.contains("2026-11-28"))
        assertFalse(text.contains("Bar Vivant"))
    }

    @Test
    fun anUnreadableAnswerFallsBackToTheEvidence() {
        val venues = listOf(venue("Coquine", "coquine.com"))
        val report = PlaceScout.Report(
            place("Where should I go for dinner in Portland?")!!,
            venues,
            picks = null
        )
        val text = report.text(on = "2026-11-28")
        assertTrue(text.contains("Coquine"))
        assertTrue(text.contains("coquine.com"))
    }

    @Test
    fun thePickPromptForbidsMemory() {
        val prompt = PlaceScout.prompt("I like restrained oak")
        assertTrue(prompt.contains("Never name a place that is not"))
        assertTrue(prompt.contains("I like restrained oak"))
    }
}
