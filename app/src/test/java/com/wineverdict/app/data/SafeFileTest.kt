package com.wineverdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A save has to be all or nothing. The cellar file is the only copy of a cellar, and a half-written
 * one used to decode as an empty one — which the next save then made permanent.
 */
class SafeFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun target() = File(folder.root, "cellar.json")

    @Test
    fun aSecondWriteKeepsTheFirstAsTheBackup() {
        assertTrue(SafeFile.write(target(), """{"which":"first"}"""))
        assertTrue(SafeFile.write(target(), """{"which":"second"}"""))

        assertEquals("""{"which":"second"}""", target().readText())
        assertEquals("""{"which":"first"}""", File(folder.root, "cellar.json.bak").readText())
    }

    @Test
    fun aTruncatedFileIsReadFromTheBackupAndRepaired() {
        assertTrue(SafeFile.write(target(), """{"wines":[1]}"""))
        assertTrue(SafeFile.write(target(), """{"wines":[1,2]}"""))
        // What a phone dying mid-write left in the old, in-place version.
        target().writeText("""{"wines":[""")

        // The decoder is the test's stand-in for JSON: a truncated document is not a document.
        val read = SafeFile.read(target()) { text ->
            check(text.endsWith("}"))
            text
        }

        assertEquals("""{"wines":[1]}""", read)
        assertEquals("""{"wines":[1]}""", target().readText())
    }

    @Test
    fun aFileNothingCanReadIsNotAnEmptyOne() {
        target().writeText("not json at all")

        assertNull(SafeFile.read(target()) { text -> text.toInt() })
    }

    @Test
    fun aMissingFileIsNotAnError() {
        assertNull(SafeFile.read(target()) { it })
    }

    @Test
    fun anUnfinishedWriteIsReadRatherThanLost() {
        File(folder.root, "cellar.json.tmp").writeText("""{"wines":[3]}""")

        assertEquals("""{"wines":[3]}""", SafeFile.read(target()) { it })
        assertEquals("""{"wines":[3]}""", target().readText())
    }
}
