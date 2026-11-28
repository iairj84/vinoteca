package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The primary map source, on the reply it actually gave for Portland.
 *
 * The fixture is the shape Nominatim returned for `q=wine bar` inside Portland's bounding box,
 * trimmed to five entries — including a self-storage company and a Vancouver, Washington winery,
 * both of which had to be dropped before the question could be answered honestly.
 */
class OsmPlacesNominatimTest {

    private val town = """
        [{"place_id":1,"name":"Portland","display_name":"Portland, Multnomah County, Oregon, United States",
          "boundingbox":["45.4325360","45.6528812","-122.8367489","-122.4720252"],
          "address":{"city":"Portland","state":"Oregon","country":"United States"}}]
    """.trimIndent()

    private val places = """
        [{"name":"Stem Wine Bar PDX","category":"amenity","type":"bar",
          "address":{"city":"Portland","state":"Oregon","road":"N Mississippi Ave","house_number":"1210"},
          "extratags":{"website":"https://www.stemwinebarpdx.com/","opening_hours":"Mo,Tu 17:00-22:00; We,Th 16:30-22:00"}},
         {"name":"Portland Wine Storage","category":"company","type":"company",
          "address":{"city":"Portland","state":"Oregon"},
          "extratags":{"website":"https://portlandwine.com/"}},
         {"name":"Airfield Estates Winery","category":"craft","type":"winery",
          "address":{"city":"Vancouver","state":"Washington"},
          "extratags":{"website":"https://example-wa.com/"}},
         {"name":"Enso Winery","category":"craft","type":"winery",
          "address":{"city":"Portland","state":"Oregon"},
          "extratags":{"website":"https://ensowinery.com/"}},
         {"name":"No Web Wine Bar","category":"amenity","type":"bar",
          "address":{"city":"Portland","state":"Oregon"},"extratags":{}}]
    """.trimIndent()

    @Test
    fun theTownBoxIsInViewboxOrder() {
        val box = OsmPlaces.townBox(town, "Oregon")
        assertEquals("-122.8367489,45.6528812,-122.4720252,45.4325360", box)
    }

    @Test
    fun aTownThatResolvedToAnotherStateHasNoBox() {
        assertNull(OsmPlaces.townBox(town, "Maine"))
    }

    @Test
    fun theAddressAndHoursAreReadFromTheReply() {
        val parsed = OsmPlaces.parseNominatim(places, "Portland", "Oregon")
        assertEquals(listOf("Stem Wine Bar PDX", "Enso Winery"), parsed.map { it.name })
        assertEquals("1210 N Mississippi Ave, Portland, Oregon", parsed.first().address)
        assertEquals("Mo,Tu 17:00-22:00; We,Th 16:30-22:00", parsed.first().hours)
        assertEquals("https://ensowinery.com/", parsed.last().website)
    }

    @Test
    fun aStorageCompanyAndAnotherStateAreNotDinner() {
        val names = OsmPlaces.parseNominatim(places, "Portland", "Oregon").map { it.name }
        assertFalse(names.contains("Portland Wine Storage"))
        assertFalse(names.contains("Airfield Estates Winery"))
        assertFalse(names.contains("No Web Wine Bar"))
    }

    @Test
    fun theAskingUrlsAreBoundedAndEncoded() {
        val url = OsmPlaces.searchUrl("wine bar", "-122.8,45.6,-122.4,45.4")
        assertTrue(url.contains("q=wine+bar"))
        assertTrue(url.contains("bounded=1"))
        assertTrue(url.contains("extratags=1"))
        assertTrue(url.contains("viewbox=-122.8%2C45.6%2C-122.4%2C45.4"))
        assertTrue(OsmPlaces.townUrl("Portland, Oregon").contains("q=Portland%2C+Oregon"))
    }

    @Test
    fun threeQueriesIsTheWholeWineBudget() {
        assertTrue(OsmPlaces.terms(PlaceScout.Ask.Kind.RESTAURANT).size <= 3)
        assertTrue(OsmPlaces.terms(PlaceScout.Ask.Kind.BAR).contains("wine bar"))
        assertTrue(OsmPlaces.terms(PlaceScout.Ask.Kind.SHOP).contains("wine shop"))
    }
}
