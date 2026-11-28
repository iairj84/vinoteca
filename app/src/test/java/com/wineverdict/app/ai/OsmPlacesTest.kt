package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map source: the query has to be scoped to the town, and the reply has to be read with the
 * address that proves which town it is. The fixture below is the shape Overpass returned for
 * Portland (34 elements, trimmed), including the Portland, **Connecticut** entry that made the
 * region check necessary.
 */
class OsmPlacesTest {

    private val body = """
        {"version":0.6,"generator":"Overpass API","elements":[
          {"type":"node","id":1,"lat":45.5,"lon":-122.6,"tags":{
            "name":"The Portland Wine Bar","amenity":"bar",
            "addr:housenumber":"526","addr:street":"SW Yamhill St",
            "addr:city":"Portland","addr:state":"OR",
            "website":"https://portlandwinebar.com/","opening_hours":"Mo-Su 14:30-21:00"}},
          {"type":"node","id":2,"lat":45.5,"lon":-122.6,"tags":{
            "name":"Enso Winery","amenity":"bar",
            "addr:city":"Portland","addr:state":"OR",
            "contact:website":"https://ensowinery.com/"}},
          {"type":"node","id":3,"lat":41.6,"lon":-72.6,"tags":{
            "name":"Portland Wine Bar","shop":"wine",
            "addr:city":"Portland","addr:state":"CT",
            "website":"https://example-ct.com/"}},
          {"type":"node","id":4,"lat":45.5,"lon":-122.6,"tags":{
            "name":"No Website Wine Bar","amenity":"bar",
            "addr:city":"Portland","addr:state":"OR"}},
          {"type":"node","id":5,"lat":45.5,"lon":-122.6,"tags":{
            "name":"Stem Wine Bar PDX","amenity":"bar",
            "addr:street":"N Mississippi Ave",
            "website":"https://www.stemwinebarpdx.com/"}}
        ]}
    """.trimIndent()

    @Test
    fun theQueryIsScopedToTheTownsBoundaryAndToWine() {
        val query = OsmPlaces.query("Portland", PlaceScout.Ask.Kind.RESTAURANT)
        assertTrue(query.contains("area[\"name\"=\"Portland\"][\"admin_level\"=\"8\"]"))
        assertTrue(query.contains("nwr[\"shop\"=\"wine\"]"))
        assertTrue(query.contains("nwr[\"craft\"=\"winery\"]"))
        assertTrue(query.contains("amenity\"~\"bar|pub|restaurant\""))
        assertTrue(query.contains("cuisine\"~\"wine\""))
    }

    @Test
    fun aShopQuestionDoesNotAskForRestaurants() {
        val query = OsmPlaces.query("Lisbon", PlaceScout.Ask.Kind.SHOP)
        assertTrue(query.contains("shop\"=\"wine"))
        assertFalse(query.contains("restaurant"))
    }

    @Test
    fun theTownIsEscapedRatherThanInjected() {
        val query = OsmPlaces.query("Ba\"d [Town]", PlaceScout.Ask.Kind.BAR)
        assertFalse(query.contains("Ba\"d"))
    }

    @Test
    fun theAddressIsWhatProvesTheTown() {
        val places = OsmPlaces.parse(body, town = "Portland", region = "Oregon")
        val names = places.map { it.name }
        assertEquals(listOf("The Portland Wine Bar", "Enso Winery", "Stem Wine Bar PDX"), names)
        val bar = places.first()
        assertEquals("526 SW Yamhill St, Portland, OR", bar.address)
        assertEquals("Mo-Su 14:30-21:00", bar.hours)
        assertEquals("https://portlandwinebar.com/", bar.website)
        // A contact:website is the website; a missing one is not recommendable.
        assertEquals("https://ensowinery.com/", places[1].website)
        assertFalse(names.contains("No Website Wine Bar"))
        // The same name in another state is not the asked town.
        assertFalse(names.any { it == "Portland Wine Bar" })
    }

    @Test
    fun aVenueWithNoStateIsKeptBecauseItsPageMustStillNameTheTown() {
        val places = OsmPlaces.parse(body, town = "Portland", region = "Oregon")
        assertEquals("https://www.stemwinebarpdx.com/", places.last().website)
        assertEquals("N Mississippi Ave", places.last().street)
    }

    @Test
    fun aRegionIsMatchedThroughItsTwoLetterState() {
        assertTrue(OsmPlaces.regionMatches("OR", "Oregon"))
        assertTrue(OsmPlaces.regionMatches("oregon", "OR"))
        assertTrue(OsmPlaces.regionMatches("", "Oregon"))
        assertFalse(OsmPlaces.regionMatches("CT", "Oregon"))
        assertFalse(OsmPlaces.regionMatches("CA", "Oregon"))
    }

    @Test
    fun aTownWithNoRegionStillChecksTheTown() {
        val places = OsmPlaces.parse(body, town = "Portland", region = "")
        val names = places.map { it.name }
        assertTrue(names.contains("The Portland Wine Bar"))
        assertTrue(names.contains("Portland Wine Bar"))
    }

    @Test
    fun aGarbageReplyIsNoPlacesRatherThanACrash() {
        assertEquals(emptyList<OsmPlaces.Place>(), OsmPlaces.parse("<html>busy</html>", "Portland", "Oregon"))
        assertEquals(emptyList<OsmPlaces.Place>(), OsmPlaces.parse("", "Portland", "Oregon"))
    }
}
