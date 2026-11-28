package com.wineverdict.app.data

import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.Wine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The to-buy flag was added to a file that already exists on people's phones. A cellar written
 * before it must load with nothing flagged, and a bottle put on the list must still be on it after
 * the next save — otherwise the flag is a switch that forgets.
 */
class ToBuyFlagTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun aCellarWrittenBeforeTheFlagLoadsWithNothingWanted() {
        val old = """
            {"wines":[{"id":"w1","producer":"Ridge","name":"Zinfandel","quantity":1,"favorite":true}],
             "locations":[]}
        """.trimIndent()
        val loaded = json.decodeFromString<CellarData>(old)
        assertFalse(loaded.wines.single().toBuy)
    }

    @Test
    fun aBottlePutOnTheListIsStillOnItAfterASave() {
        val data = CellarData(wines = listOf(Wine(id = "w1", name = "Barolo", toBuy = true)))
        val back = json.decodeFromString<CellarData>(json.encodeToString(data))
        assertTrue(back.wines.single().toBuy)
    }

    @Test
    fun aBottleNeverFlaggedSavesAsNotWanted() {
        val data = CellarData(wines = listOf(Wine(id = "w1", name = "Barolo")))
        val text = json.encodeToString(data)
        assertTrue(text.contains("\"toBuy\":false"))
    }
}
