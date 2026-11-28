package com.wineverdict.app.ai

import com.wineverdict.app.data.Region
import kotlinx.coroutines.runBlocking
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
    fun thePipelineDropsAClosedVenueAndOneInAnotherTown() = runBlocking {
        val ask = place("Where should I go for dinner in Portland, Oregon with good wine?")!!
        val venues = listOf(
            PlaceScout.Venue("Bar Diane", "https://bardiane.com/", address = "Powell Blvd, Portland, OR"),
            PlaceScout.Venue("Closed Room", "https://closed.example/", address = "1 SW A St, Portland, OR"),
            PlaceScout.Venue("Maine Bar", "https://maine.example/", address = "1 Main St, Portland, ME"),
            PlaceScout.Venue("Stem", "https://stem.example/", address = "N Mississippi Ave, Portland, OR")
        )
        val pages = mapOf(
            "https://bardiane.com/" to "Bar Diane is a natural wine bar in Portland, Oregon. Open nightly.",
            "https://closed.example/" to "This bar has permanently closed. Thank you for 20 years.",
            "https://maine.example/" to "A wine bar in Portland, Maine, with a long list.",
            "https://stem.example/" to "Stem Wine Bar on N Mississippi in Portland, by the glass."
        )
        val sources = object : PlaceScout.Sources {
            override suspend fun discover(ask: PlaceScout.Ask, box: String) = venues
            override suspend fun page(url: String) = pages[url].orEmpty()
            override suspend fun pick(system: String, evidence: String): String {
                assertTrue(evidence.indexOf("Bar Diane") < evidence.indexOf("Stem"))
                assertTrue(evidence.contains("address: Powell Blvd, Portland, OR"))
                assertFalse(evidence.contains("Closed Room"))
                assertFalse(evidence.contains("Maine Bar"))
                return """{"picks":[{"line":1,"why":"a natural-wine bar on Powell"},""" +
                    """{"line":2,"why":"by the glass on Mississippi"}],"note":"both open late"}"""
            }
        }
        val report = PlaceScout.research(ask, "I like restrained oak", sources)
        assertEquals(listOf("Bar Diane", "Stem"), report.venues.map { it.name })
        val text = report.text(on = "2026-11-28")
        assertTrue(text.contains("Bar Diane (bardiane.com)"))
        assertTrue(text.contains("Stem (stem.example)"))
        assertFalse(text.contains("Closed Room"))
        assertFalse(text.contains("Maine Bar"))
    }

    @Test
    fun aVenueWithNoReadablePageIsNotRecommended() = runBlocking {
        val ask = place("Where should I go for dinner in Portland with good wine?")!!
        val sources = object : PlaceScout.Sources {
            override suspend fun towns(ask: PlaceScout.Ask) = listOf(town("Portland", "Oregon"))
            override suspend fun discover(ask: PlaceScout.Ask, box: String) = listOf(
                PlaceScout.Venue("Bar Diane", "https://bardiane.com/", address = "Portland, OR")
            )
            override suspend fun page(url: String) = ""
            override suspend fun pick(system: String, evidence: String) = error("the model must not be asked")
        }
        val report = PlaceScout.research(ask, "", sources)
        assertTrue(report.venues.isEmpty())
        assertTrue(report.text().contains("found no place I could confirm"))
    }

    @Test
    fun aRefusedLookupSaysSoWithoutNamingAnywhere() {
        val ask = place("Where should I go for dinner in Portland, Oregon with good wine?")!!
        val text = PlaceScout.cannotConfirm(ask, "The place map is at capacity right now.")
        assertTrue(text.contains("could not check anywhere in Portland, Oregon"))
        assertTrue(text.contains("Nothing was guessed"))
        assertFalse(text.contains("Bar Diane"))
    }

    @Test
    fun aHijackedDomainIsNotEvidenceOfAWineBar() {
        // Seen live: this Portland merchant's domain served a crypto-casino page in Russian while
        // the map still described a wine bar.
        val venue = PlaceScout.Venue(
            "Amphora Wine Merchant",
            "https://www.amphorawinemerchantpdx.com/",
            address = "401 Northeast Holladay Street, Portland, Oregon"
        )
        val spam = "Крипто-казино в 2026 году: лучшие crypto casinos без KYC. Топ Быстрые выплаты"
        assertFalse(PlaceScout.pageBelongs(spam, venue))
        assertTrue(
            PlaceScout.pageBelongs(
                "Amphora Wine Merchant is a wine bar and bottle shop on Holladay in Portland.",
                venue
            )
        )
    }

    @Test
    fun placesWithHoursAndSomewhereToSitAreReadFirst() {
        val shop = PlaceScout.Venue("E&R Wine Shop", "https://www.erwineshop.com/", snippet = "shop/wine")
        val bar = PlaceScout.Venue(
            "Stem Wine Bar PDX",
            "https://www.stemwinebarpdx.com/",
            snippet = "amenity/bar · Mo,Tu 17:00-22:00",
            hours = "Mo,Tu 17:00-22:00"
        )
        assertEquals(listOf(bar, shop), PlaceScout.rank(listOf(shop, bar)))
    }

    // ------------------------------------------------------------ which town a bare name means

    private fun town(name: String, state: String, country: String = "United States", code: String = "us") =
        PlaceScout.Town(name, state, country, code, box = "-122.8,45.6,-122.4,45.4")

    private fun region(state: String, country: String = "United States", code: String = "us", source: String = "your location") =
        Region(state = state, country = country, countryCode = code, source = source)

    @Test
    fun aBareTownIsSettledByThePhonesOwnState() {
        val portlands = listOf(
            town("Portland", "Oregon"),
            town("Portland", "Maine"),
            town("Portland", "Texas")
        )
        val choice = PlaceScout.chooseTown("Portland", region("Oregon"), portlands)
        assertTrue(choice is PlaceScout.Choice.One)
        val one = choice as PlaceScout.Choice.One
        assertEquals("Portland, Oregon", one.town.label)
        assertTrue(one.basis.contains("Oregon"))
    }

    @Test
    fun anAmbiguousTownWithNoRegionAsksInsteadOfPicking() {
        val choice = PlaceScout.chooseTown(
            "Portland",
            null,
            listOf(town("Portland", "Oregon"), town("Portland", "Maine"))
        )
        assertTrue(choice is PlaceScout.Choice.Many)
        assertEquals(2, (choice as PlaceScout.Choice.Many).options.size)
    }

    @Test
    fun aRegionThatMatchesNoneOfThemAlsoAsks() {
        // A phone in California cannot settle Portland, Oregon from Portland, Maine.
        val choice = PlaceScout.chooseTown(
            "Portland",
            region("California"),
            listOf(town("Portland", "Oregon"), town("Portland", "Maine"))
        )
        assertTrue(choice is PlaceScout.Choice.Many)
    }

    @Test
    fun aCountryOnlyRegionSettlesATownUniqueInThatCountry() {
        val vancouvers = listOf(
            town("Vancouver", "British Columbia", "Canada", "ca"),
            town("Vancouver", "Washington")
        )
        val choice = PlaceScout.chooseTown("Vancouver", region("", "United States", "us", "your phone's region"), vancouvers)
        assertTrue(choice is PlaceScout.Choice.One)
        assertEquals("Vancouver, Washington", (choice as PlaceScout.Choice.One).town.label)
        assertTrue(choice.basis.contains("United States"))
    }

    @Test
    fun aTownTheMapDoesNotKnowIsNotAPlace() {
        assertTrue(
            PlaceScout.chooseTown("Nowhereville", region("Oregon"), listOf(town("Portland", "Oregon")))
                is PlaceScout.Choice.None
        )
    }

    @Test
    fun theAmbiguousReplyNamesTheTownsAndTheRegionAndDoesNotPick() {
        val text = PlaceScout.whichTown(
            "Portland",
            listOf(town("Portland", "Oregon"), town("Portland", "Maine")),
            region("California")
        )
        assertTrue(text.contains("Portland, Oregon"))
        assertTrue(text.contains("Portland, Maine"))
        assertTrue(text.contains("California"))
        assertTrue(text.contains("will not guess"))
    }

    @Test
    fun thePipelineTurnsABarePortlandIntoOregonAndSaysWhy() = runBlocking {
        val ask = place("Where should I go for dinner tonight in Portland with good wine?")!!
        var askedWhere = ""
        var askedBox = ""
        val venue = PlaceScout.Venue(
            "Stem Wine Bar PDX",
            "https://stemwinebarpdx.com/",
            address = "3920 North Mississippi Avenue, Portland, Oregon"
        )
        val sources = object : PlaceScout.Sources {
            override suspend fun region() = region("Oregon")
            override suspend fun towns(ask: PlaceScout.Ask) = listOf(
                town("Portland", "Oregon"),
                town("Portland", "Maine")
            )
            override suspend fun discover(ask: PlaceScout.Ask, box: String): List<PlaceScout.Venue> {
                askedWhere = ask.where
                askedBox = box
                return listOf(venue)
            }
            override suspend fun page(url: String) =
                "Stem Wine Bar on N Mississippi in Portland, Oregon. Vietnamese food and wine."
            override suspend fun pick(system: String, evidence: String) =
                """{"picks":[{"line":1,"why":"dinner and a glass"}]}"""
        }
        val report = PlaceScout.research(ask, "", sources)
        assertEquals("Portland, Oregon", askedWhere)
        assertTrue("the map box for the settled town", askedBox.isNotEmpty())
        assertTrue(report.text().contains("in Portland, Oregon"))
        assertTrue(report.text().contains("your phone's region is Oregon"))
    }

    @Test
    fun aPageQuoteIsThePagesWordsCutAtAWordBoundary() {
        val venue = PlaceScout.Venue(
            "Stem Wine Bar PDX",
            "https://stemwinebarpdx.com/",
            text = "Vietnamese Food & Wine on N Mississippi | Friendship Kitchen & Stem. " +
                "Stem Wine Bar on N Mississippi Avenue in Portland."
        )
        val quote = PlaceScout.pageQuote(venue)
        assertTrue(quote.startsWith("Vietnamese Food & Wine on N Mississippi"))
        assertTrue(quote.length <= 161)
        // The quote is the page's own words, so it keeps the venue's name where the page put it.
        assertTrue(quote.contains("Friendship Kitchen & Stem"))

        val long = PlaceScout.Venue("Bar Diane", "https://bardiane.com/", text = "wine ".repeat(80))
        assertTrue(PlaceScout.pageQuote(long).endsWith("…"))
        assertTrue(PlaceScout.pageQuote(long).length <= 161)
    }

    @Test
    fun aQuoteIsProseAndNotMarkupOrEscapedJson() {
        // Both shapes came off live pages: Squarespace emits a header tag after the stripper, and
        // its data block escapes its quotes.
        val floyds = PlaceScout.Venue(
            "Floyd's Coffee House & Wine Bar",
            "https://www.floyds.coffee/",
            text = "Floyd's Coffee House & Wine Bar 0 <header data-test=\"header\" id=\"header\" " +
                "class=\" header theme-col--primary \"> Our wine bar pours by the glass until late."
        )
        val quote = PlaceScout.pageQuote(floyds)
        assertFalse(quote.contains("<header"))
        assertFalse(quote.contains("data-test"))
        assertTrue(quote.contains("Our wine bar pours by the glass"))

        val willful = PlaceScout.Venue(
            "Willful Wine CO",
            "https://willfulwine.com/",
            text = "Willful Wine Co - Homepage Featured Products 2021 Walla Walla Red Blend " +
                "\\\"silken textures and a contrast of ripe berry fruits..\" and more wine."
        )
        assertFalse(PlaceScout.pageQuote(willful).contains("\\\""))
    }

    @Test
    fun theRefusalSaysNothingWasGuessedOnlyOnce() {
        val ask = place("Where should I go for dinner in Portland, Oregon?")!!
        val text = PlaceScout.cannotConfirm(ask, OsmPlaces.BUSY)
        assertEquals(1, Regex("Nothing was guessed").findAll(text).count())
        assertEquals(1, Regex("Ask again in a minute").findAll(text).count())
    }

    @Test
    fun aPageThatClosesOnSundaysIsNotAClosedVenue() {
        // Real restaurant wording: the day it shuts is not the day it shut for good.
        assertEquals(
            "",
            PlaceScout.closedReason("Open Tuesday to Saturday, closed Sundays and Mondays. Wine list on the site.")
        )
        assertEquals(
            "",
            PlaceScout.closedReason("We are closed for a private event on 12 December. Join us the week after.")
        )
        assertTrue(
            PlaceScout.closedReason("After 22 years, our tasting room has closed permanently.").isNotBlank()
        )
    }

    @Test
    fun aHalfReadPhoneNumberNeverReachesTheReply() {
        // Seen live: the page text was cut mid-number and the note said "call (503)-4".
        val note = "For Wine & Growl, you can call (503)-4 to make a reservation."
        assertEquals("", PlaceScout.cleanNote(note))
        assertEquals(
            "Stem is open until 22:00 most nights. Check the schedule before you go.",
            PlaceScout.cleanNote(
                "Stem is open until 22:00 most nights. For Pairings, call (503) 555-1212. " +
                    "Check the schedule before you go."
            )
        )
    }

    @Test
    fun aTownsAnswerIsReusedUntilItExpires() {
        var now = 1_000L
        val cache = PlaceScout.Cache<String>(ttlMs = 20 * 60_000L, clock = { now })
        cache.put("BAR|portland oregon", "Stem")
        assertEquals("Stem", cache.get("BAR|portland oregon"))
        now += 19 * 60_000L
        assertEquals("Stem", cache.get("BAR|portland oregon"))
        now += 2 * 60_000L
        assertNull(cache.get("BAR|portland oregon"))
    }

    @Test
    fun theCacheKeyIsTheTownAndTheKind() {
        val ask = place("Any good wine bar in Portland, Oregon?")!!
        assertEquals("BAR|portland oregon", PlaceScout.cacheKey(ask))
    }

    @Test
    fun thePickPromptForbidsMemory() {
        val prompt = PlaceScout.prompt("I like restrained oak")
        assertTrue(prompt.contains("Never name a place that is not"))
        assertTrue(prompt.contains("I like restrained oak"))
    }
}
