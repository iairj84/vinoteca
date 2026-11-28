package com.wineverdict.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wineverdict.app.ai.OsmPlaces
import com.wineverdict.app.ai.PlaceScout
import com.wineverdict.app.ai.WebLookup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The region reader on the runtime it actually runs on.
 *
 * Every other test of the place question hands [DeviceRegions] a region, which proves the reasoning
 * and proves nothing about the reader: the locale parsing, the Home fallback, the order between
 * them. This one runs inside Android, with the app's own `PlaceStore` and `PlaceFinder`, no injected
 * region anywhere, and prints what the runtime reported.
 *
 * What each case establishes:
 *
 *  - **locale only**: a phone with no fix and no Home reports its locale's country, says so, and
 *    honestly has no state;
 *  - **Home**: a saved place called Home is reverse-geocoded and its state becomes the region,
 *    which is how a phone that has never granted location can still settle "Portland";
 *  - **settled**: a bare "Portland", through `PlaceScout.research` and the real map, becomes
 *    Portland, Oregon and the reply says which town and why;
 *  - **unknown**: a runtime with no usable region makes the same question ask which Portland,
 *    and never defaults to Oregon.
 *
 * Run with an emulator attached: `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class DeviceRegionsTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The same wiring `AppGraph` uses, over the app's real place file. */
    private fun finder(store: PlaceStore): PlaceFinder =
        PlaceFinder(PhotonClient(), AndroidAddressFinder(context)) { store.places.value }

    private fun store(): PlaceStore = PlaceStore(context).also { it.load() }

    private fun portlandAsk(): PlaceScout.Ask =
        PlaceScout.ask("Where should I go for dinner tonight in Portland with good wine?")
            ?: throw AssertionError("the classifier lost the question")

    /**
     * No location permission, so the fix step is genuinely absent rather than answered by whatever
     * the emulator last knew. Revoking is allowed for this package from the shell it runs as.
     */
    @Before
    fun noLocationPermission() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION"
        ).forEach { permission ->
            automation.executeShellCommand(
                "pm revoke com.wineverdict.app $permission"
            ).close()
        }
    }

    @Test
    fun theRuntimeReportsItsOwnLocaleRegion() = runBlocking {
        assumeTrue("the runtime has no locale country", Locale.getDefault().country.isNotBlank())
        val places = store()
        places.replaceAll(emptyList())

        val region = DeviceRegions.of(context, finder(places))
        println(
            "RUNTIME(locale) words='${region.words}' city='${region.city}' state='${region.state}' " +
                "countryCode='${region.countryCode}' source='${region.source}' known=${region.known}"
        )

        assertEquals("your phone's region", region.source)
        assertEquals(Locale.getDefault().country, region.countryCode)
        assertTrue("the locale cannot name a state", region.state.isBlank())
    }

    @Test
    fun aSavedHomePlaceGivesTheStateFromTheMap() = runBlocking {
        val places = store()
        places.replaceAll(listOf(Place(name = "Home", lat = 45.5231, lng = -122.6765)))
        try {
            val region = DeviceRegions.of(context, finder(places))
            println(
                "RUNTIME(home) words='${region.words}' city='${region.city}' state='${region.state}' " +
                    "source='${region.source}' known=${region.known}"
            )

            assertEquals("your Home place", region.source)
            assertEquals("Oregon", region.state)
            assertEquals("Portland", region.city)
        } finally {
            places.replaceAll(emptyList())
        }
    }

    @Test
    fun aBarePortlandIsSettledByTheRuntimeRegion() = runBlocking {
        val places = store()
        places.replaceAll(listOf(Place(name = "Home", lat = 45.5231, lng = -122.6765)))
        try {
            val region = DeviceRegions.of(context, finder(places))
            val report = PlaceScout.research(portlandAsk(), ProductionSources(region))
            val reply = report.text()
            println("SETTLED '${report.ask.where}' basis='${report.ask.basis}' venues=${report.venues.size}")
            // One line: logcat keeps only the first line of a multi-line println.
            println("REPLY ${reply.replace("\n", " | ")}")

            if (report.venues.isEmpty()) {
                // Diagnostics only: why this runtime produced nothing, said out loud.
                val found = OsmPlaces.find(report.ask)
                println("DIAG map places=${found.size} first=${found.take(2).map { it.name }}")
                found.take(2).forEach { place ->
                    val page = WebLookup().pageText(place.website, 500)
                    println("DIAG page ${place.website} chars=${page.length} belongs=${PlaceScout.pageBelongs(page, OsmPlaces.asVenue(place))}")
                }
            }
            assertEquals("Portland, Oregon", report.ask.where)
            assertTrue("the reply does not name the town", reply.contains("Portland, Oregon"))
            assertTrue("the reply does not say why", reply.contains("your phone's region is Oregon"))
            assertFalse("a closed venue reached the reply", report.venues.any { it.closed.isNotBlank() })
        } finally {
            places.replaceAll(emptyList())
        }
    }

    @Test
    fun withoutAUsableRegionItAsksInsteadOfDefaultingOregon() = runBlocking {
        val places = store()
        places.replaceAll(emptyList())
        val previous = Locale.getDefault()
        try {
            @Suppress("DEPRECATION")
            Locale.setDefault(Locale("en"))
            val region = DeviceRegions.of(context, finder(places))
            println("RUNTIME(none) known=${region.known} words='${region.words}' source='${region.source}'")
            assertFalse("this runtime still reports a region", region.known)

            val report = PlaceScout.research(portlandAsk(), ProductionSources(region))
            val reply = report.text()
            println("REPLY ${reply.replace("\n", " | ")}")
            assertTrue("it guessed a town: $reply", reply.contains("I found more than one Portland"))
            assertFalse("it defaulted to Oregon: $reply", reply.contains("I used Portland, Oregon"))
        } finally {
            Locale.setDefault(previous)
            places.replaceAll(emptyList())
        }
    }

    /**
     * The three real sources the chat uses — the map, the page reader and the provider — with the
     * provider stubbed because a device test has no API key. The region is not stubbed: it is what
     * the runtime reported.
     */
    private class ProductionSources(private val region: Region) : PlaceScout.Sources {
        override suspend fun region() = region

        override suspend fun towns(ask: PlaceScout.Ask) = OsmPlaces.towns(ask.where)

        override suspend fun discover(ask: PlaceScout.Ask, box: String) =
            OsmPlaces.find(ask, box).map(OsmPlaces::asVenue)

        override suspend fun page(url: String) = WebLookup().pageText(url, 2_000)

        override suspend fun pick(system: String, evidence: String) =
            """{"picks":[{"line":1,"why":"page names it and calls it a wine bar"},""" +
                """{"line":2,"why":"page names it and lists wine"}],"note":""}"""
    }
}
