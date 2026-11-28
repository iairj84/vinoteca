package com.wineverdict.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Not now** is an answer about one release. Remembering it wrongly either nags every launch or
 * hides a new release for good, so which one it is is pinned here.
 */
class UpdateOfferTest {

    @Test
    fun nothingWasFoundSoThereIsNothingToOffer() {
        assertFalse(UpdateOffer.show(null, dismissedVersion = ""))
        assertFalse(UpdateOffer.show("", dismissedVersion = ""))
    }

    @Test
    fun aDismissedReleaseIsNotOfferedAgain() {
        assertFalse(UpdateOffer.show("1.2.46", dismissedVersion = "1.2.46"))
        assertFalse(UpdateOffer.show("1.2.46", dismissedVersion = " 1.2.46 "))
    }

    @Test
    fun aNewerReleaseIsANewQuestion() {
        assertTrue(UpdateOffer.show("1.2.46", dismissedVersion = "1.2.45"))
    }

    @Test
    fun aReleaseIsOfferedWhenNothingWasEverDismissed() {
        assertTrue(UpdateOffer.show("1.2.46", dismissedVersion = ""))
    }
}
