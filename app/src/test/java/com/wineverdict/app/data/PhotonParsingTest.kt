package com.wineverdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser, against answers actually captured from Photon.
 *
 * The payloads below are trimmed copies of real responses: four Loyal Legions near Beaverton, the
 * reverse answer at The French Laundry's door, and the reverse answer in a residential street that
 * must not become a night's place.
 */
class PhotonParsingTest {

    private val loyalLegions = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"osm_key":"amenity","osm_value":"pub","name":"Loyal Legion",
            "housenumber":"4500","street":"Southwest Watson Avenue","city":"Beaverton",
            "state":"Oregon","country":"United States"},
           "geometry":{"type":"Point","coordinates":[-122.8058156,45.486919]}},
          {"type":"Feature","properties":{"osm_key":"amenity","osm_value":"pub","name":"Loyal Legion",
            "housenumber":"710","street":"Southeast 6th Avenue","city":"Portland"},
           "geometry":{"type":"Point","coordinates":[-122.6594942,45.5177924]}},
          {"type":"Feature","properties":{"osm_key":"amenity","osm_value":"pub","name":"Loyal Legion",
            "housenumber":"1109","street":"Washington Street","city":"Vancouver"},
           "geometry":{"type":"Point","coordinates":[-122.672424,45.6300013]}},
          {"type":"Feature","properties":{"osm_key":"highway","osm_value":"residential",
            "name":"Southeast Loyal Court","city":"Happy Valley"},
           "geometry":{"type":"Point","coordinates":[-122.50548,45.4424428]}}
        ]}
    """.trimIndent()

    private val frenchLaundry = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"osm_key":"amenity","osm_value":"restaurant",
            "name":"The French Laundry","street":"Washington Street","city":"Yountville"},
           "geometry":{"type":"Point","coordinates":[-122.3647106,38.4042648]}}
        ]}
    """.trimIndent()

    private val house = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"osm_key":"place","osm_value":"house",
            "name":"The Old Rectory","housenumber":"6005","street":"Southwest 130th Avenue",
            "city":"Beaverton"},
           "geometry":{"type":"Point","coordinates":[-122.8109764,45.476019]}}
        ]}
    """.trimIndent()

    @Test
    fun fourBranchesComeBackAsFourRows() {
        val found = parsePlaces(loyalLegions, businessesOnly = true)
        assertEquals(3, found.size)
        assertEquals(
            listOf("Loyal Legion — Beaverton", "Loyal Legion — Portland", "Loyal Legion — Vancouver"),
            found.map { it.label }
        )
    }

    @Test
    fun theStreetAndThePositionComeWithTheName() {
        val first = parsePlaces(loyalLegions, businessesOnly = true).first()
        assertEquals("4500 Southwest Watson Avenue", first.street)
        assertEquals(45.486919, first.lat, 0.00001)
        assertEquals(-122.8058156, first.lng, 0.00001)
    }

    @Test
    fun aStreetIsNotAPlaceToDrink() {
        val everything = parsePlaces(loyalLegions, businessesOnly = false)
        assertEquals(4, everything.size)
        assertTrue(everything.any { it.name == "Southeast Loyal Court" })

        val businesses = parsePlaces(loyalLegions, businessesOnly = true)
        assertTrue(businesses.none { it.name == "Southeast Loyal Court" })
    }

    @Test
    fun aRestaurantIsNamedByItsOwnName() {
        val found = parsePlaces(frenchLaundry, businessesOnly = true)
        assertEquals(1, found.size)
        assertEquals("The French Laundry", found.first().name)
        assertEquals("The French Laundry — Yountville", found.first().label)
    }

    @Test
    fun aHouseIsNotABusiness() {
        assertTrue(parsePlaces(house, businessesOnly = true).isEmpty())
        assertEquals(1, parsePlaces(house, businessesOnly = false).size)
    }

    @Test
    fun anUnnamedHouseIsNothingAtAll() {
        // What Photon actually answered at a residential doorway: a number and a street, no name.
        val unnamed = """
            {"features":[{"properties":{"osm_key":"place","osm_value":"house",
              "housenumber":"6005","street":"Southwest 130th Avenue","city":"Beaverton"},
             "geometry":{"type":"Point","coordinates":[-122.8109764,45.476019]}}]}
        """.trimIndent()
        assertTrue(parsePlaces(unnamed, businessesOnly = false).isEmpty())
    }

    @Test
    fun anAnswerThatIsNotWhatItShouldBeIsEmptyRatherThanACrash() {
        assertTrue(parsePlaces("", businessesOnly = true).isEmpty())
        assertTrue(parsePlaces("not json at all", businessesOnly = true).isEmpty())
        assertTrue(parsePlaces("""{"type":"FeatureCollection"}""", businessesOnly = true).isEmpty())
        assertTrue(parsePlaces("""{"features":"no"}""", businessesOnly = true).isEmpty())
    }

    @Test
    fun aFeatureWithNoNameOrNoCoordinatesIsDropped() {
        val body = """
            {"features":[
              {"properties":{"osm_key":"amenity"},"geometry":{"coordinates":[1.0,2.0]}},
              {"properties":{"osm_key":"amenity","name":"Somewhere"},"geometry":{}},
              {"properties":{"osm_key":"amenity","name":"Somewhere"},"geometry":{"coordinates":[]}}
            ]}
        """.trimIndent()
        assertTrue(parsePlaces(body, businessesOnly = true).isEmpty())
    }

    @Test
    fun theLimitIsRespected() {
        assertEquals(2, parsePlaces(loyalLegions, businessesOnly = true, limit = 2).size)
    }
}
