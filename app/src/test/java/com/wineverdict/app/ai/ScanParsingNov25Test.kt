package com.wineverdict.app.ai

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The function that assigned the wrong description at import is
 * [ScanParsing.applyJudgement]: it attaches the judgement's `what` to the
 * phone's row by menu number (`label.copy(wine = ... expertNotes = hit.what)`).
 * When the model echoes a neighbour's title inside `what`, the stored row is
 * merged before the renderer ever sees it. The Nov 25 payload below replays
 * exactly that: line 1 (Miquel Pons) carries Naveran's title-echo.
 */
class ScanParsingNov25Test {

    private fun phoneRow(producer: String, name: String, vintage: Int? = null): ScannedLabel =
        ScannedLabel(
            wine = Wine(producer = producer, name = name, vintage = vintage, type = WineType.SPARKLING),
            fitScore = null, fitReason = "", flavorTags = emptyList(),
            priceEstimate = null, confidence = "medium", readNotes = ""
        )

    private fun sixBottleRead(): SceneScan = SceneScan(
        scene = Scene.LIST,
        bottles = listOf(
            phoneRow("Miquel Pons", "Cava Brut", 2021),
            phoneRow("Bodegas Naveran", "Dama Brut", 2021),
            phoneRow("Castell d'Or", "Flama d'Or Brut Rosé"),
            phoneRow("Ameztoi", "Txakoli Blanco", 2021),
            phoneRow("Castell d'Or", "Flama Cava Brut", 2022),
            phoneRow("Columna", "Albariño", 2022)
        ),
        notes = ""
    )

    @Test
    fun storedMiquelPonsRowKeepsItsOwnDescription() {
        // What the phone stored for mp-brut before any judgement: its own name,
        // its own price, no description yet.
        val read = sixBottleRead()
        val mp = read.bottles[0].wine
        println("STORED ROW mp-brut before judgement: producer=${mp.producer} name=${mp.name} vintage=${mp.vintage} expertNotes=[${mp.expertNotes}]")
        assertEquals("Miquel Pons", mp.producer)
        assertEquals("Cava Brut", mp.name)
    }

    @Test
    fun foreignTitleEchoIsRefusedAtImport() {
        val read = sixBottleRead()
        // Nov 25 payload, verbatim shape: line 1 is the Miquel Pons slot but the
        // `what` echoes the Naveran title. Line 2 is a clean Naveran read.
        val raw = listOf(
            "1 | 2 | 3 | 2021 Dama Brut Chardonnay Bodegas Naveran, Penedes sparkling crisp Chardonnay | decent, not first",
            "2 | 1 | 4 | Penedes sparkling, estate-grown Macabeu blend, dry and mineral | best here"
        ).joinToString("\n")
        val judged = checkNotNull(ScanParsing.applyJudgement(read, raw)) { "judgement returned null" }
        val mpWhat = judged.bottles[0].wine.expertNotes
        val navWhat = judged.bottles[1].wine.expertNotes
        println("AFTER JUDGEMENT mp expertNotes=[$mpWhat]")
        println("AFTER JUDGEMENT naveran expertNotes=[$navWhat]")
        // The merged description must not land on the stored row.
        assertTrue(
            "mp row must not name Naveran, was: $mpWhat",
            !mpWhat.contains("Naveran", ignoreCase = true)
        )
        assertTrue(navWhat.isNotBlank())
    }

    @Test
    fun sameProducerSiblingCuveeIsRefusedAtImport() {
        val read = SceneScan(
            scene = Scene.LIST,
            bottles = listOf(
                phoneRow("Castell d'Or", "Flama d'Or Brut Rosé"),
                phoneRow("Castell d'Or", "Flama Cava Brut", 2022)
            ),
            notes = ""
        )
        // Slot 1 is the rosat, but the `what` describes the brut (CAVA is the
        // sibling's distinctive cuvee word). It must not attach.
        val raw = listOf(
            "1 | 1 | 4 | Cava brut, citrus, fine bubble, dry finish | best here",
            "2 | 2 | 3 | Trepat rose cava, strawberry, bone-dry | second"
        ).joinToString("\n")
        val judged = checkNotNull(ScanParsing.applyJudgement(read, raw)) { "judgement returned null" }
        val rosatWhat = judged.bottles[0].wine.expertNotes
        println("AFTER JUDGEMENT rosat expertNotes=[$rosatWhat]")
        assertTrue(
            "rosat row must not carry the brut cuvee word, was: $rosatWhat",
            !rosatWhat.contains("CAVA", ignoreCase = true)
        )
    }
}
