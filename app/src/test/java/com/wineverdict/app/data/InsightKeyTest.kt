package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The insight cache is keyed on the wine, not on the record: the same bottle read twice, or met
 * later on a shelf, has to hit the same entry even when the model spelled it differently.
 */
class InsightKeyTest {

    private fun wine(producer: String, name: String, vintage: Int? = 2018) =
        Wine(producer = producer, name = name, vintage = vintage)

    @Test
    fun accentsAndCaseAreTheSameWine() {
        assertEquals(
            InsightStore.key(wine("Viña Olabarri", "Tempranillo Reserva")),
            InsightStore.key(wine("VINA OLABARRI", "tempranillo reserva"))
        )
    }

    @Test
    fun punctuationAndSpacingAreTheSameWine() {
        assertEquals(
            InsightStore.key(wine("Raúl Pérez", "Ultreia Mencía")),
            InsightStore.key(wine("Raul  Perez", "Ultreia-Mencia"))
        )
    }

    @Test
    fun aDifferentVintageIsADifferentAnswer() {
        assertNotEquals(
            InsightStore.key(wine("Pecina", "Tempranillo Crianza", 2017)),
            InsightStore.key(wine("Pecina", "Tempranillo Crianza", 2018))
        )
    }

    @Test
    fun aMissingVintageHasItsOwnEntry() {
        assertNotEquals(
            InsightStore.key(wine("Pecina", "Tempranillo Crianza", null)),
            InsightStore.key(wine("Pecina", "Tempranillo Crianza", 2017))
        )
    }

    @Test
    fun aDifferentWineIsADifferentAnswer() {
        assertNotEquals(
            InsightStore.key(wine("Pecina", "Tempranillo Crianza")),
            InsightStore.key(wine("Pecina", "Tempranillo Reserva"))
        )
    }
}
