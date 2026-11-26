package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsFactsTest {

    @Test
    fun aRangeIsTheTwoYearsPrinted() {
        assertEquals(2027 to 2038, DetailsFacts.window("2027-2038"))
        assertEquals(2027 to 2038, DetailsFacts.window("2027–2038"))
        assertEquals(2027 to 2038, DetailsFacts.window("2027 to 2038"))
        assertEquals(2027 to 2038, DetailsFacts.window("Drink 2027 — 2038"))
    }

    @Test
    fun oneEndOnly() {
        assertEquals(null to 2031, DetailsFacts.window("until 2031"))
        assertEquals(2029 to null, DetailsFacts.window("best from 2029"))
        assertEquals(2029 to null, DetailsFacts.window("2029-"))
    }

    @Test
    fun aLoneYearIsOneYearNotARange() {
        assertEquals(2028 to 2028, DetailsFacts.window("2028"))
    }

    @Test
    fun noWindowWhenNoYearIsPrinted() {
        assertEquals(null to null, DetailsFacts.window(""))
        assertEquals(null to null, DetailsFacts.window("drink young"))
        assertEquals(null to null, DetailsFacts.window("5-10 years"))
        assertEquals(null to null, DetailsFacts.window("unknown"))
    }

    @Test
    fun aMuddledOrReversedPairIsNoWindowAtAll() {
        assertEquals(null to null, DetailsFacts.sane(2038, 2027))
        assertEquals(null to null, DetailsFacts.sane(1850, 1900))
        assertEquals(2027 to 2038, DetailsFacts.sane(2027, 2038))
        assertEquals(null to 2038, DetailsFacts.sane(null, 2038))
    }

    @Test
    fun fillerIsNotAName() {
        assertEquals("", DetailsFacts.place("", 40))
        assertEquals("", DetailsFacts.place("unknown", 40))
        assertEquals("", DetailsFacts.place("-", 40))
        assertEquals("", DetailsFacts.place("a place name that is really a sentence about the wine", 40))
        assertEquals("France", DetailsFacts.place(" France. ", 40))
        assertEquals("Northern Rhône", DetailsFacts.place("Northern  Rhône", 60))
    }

    @Test
    fun grapesLoseTheFillerAndKeepTheirOrder() {
        assertEquals(listOf("Syrah", "Grenache"), DetailsFacts.grapes("Syrah, Grenache"))
        assertEquals(listOf("Syrah", "Grenache"), DetailsFacts.grapes("Syrah / Grenache"))
        assertEquals(emptyList<String>(), DetailsFacts.grapes("unknown"))
        assertEquals(emptyList<String>(), DetailsFacts.grapes(""))
    }
}
