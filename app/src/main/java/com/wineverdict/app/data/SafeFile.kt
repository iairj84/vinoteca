package com.wineverdict.app.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One JSON document on disk, written whole or not at all.
 *
 * The cellar used to be rewritten in place: a phone that died half way through a save left a
 * truncated file, the next start decoded nothing and showed an empty cellar, and the save after that
 * made the emptiness permanent. Years of notes are the wrong thing to gamble on a power cut, so a
 * write now builds the new document beside the old one, flushes it to the disk and swaps it in with
 * a rename. The file it replaces is kept as `<name>.bak`, and [read] falls back to it, so a bad write
 * costs one start's worth of changes rather than the cellar.
 */
internal object SafeFile {

    private fun sibling(target: File, suffix: String) = File(target.parentFile, "${target.name}.$suffix")

    /**
     * Writes [text] to [target], keeping the previous contents as `<name>.bak`.
     *
     * False when the file could not be written at all — a full disk, a folder that cannot be made.
     * The caller decides what to say; what was already on the disk is left as it was.
     */
    fun write(target: File, text: String): Boolean {
        val dir = target.parentFile ?: return false
        val tmp = sibling(target, "tmp")
        return try {
            dir.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            // The copy is made whole under another name first, so a failure here leaves the two
            // files it was making exactly as it found them.
            if (target.isFile) {
                val bakTmp = sibling(target, "bak.tmp")
                target.copyTo(bakTmp, overwrite = true)
                replace(bakTmp, sibling(target, "bak"))
            }
            replace(tmp, target)
            true
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    /**
     * The first of the file, its `.bak` and an unfinished `.tmp` that [decode] accepts, or null when
     * none of them reads.
     *
     * A copy that answers is written back into place, so the next start reads the file itself rather
     * than walking the same three candidates again.
     */
    fun <T> read(target: File, decode: (String) -> T): T? {
        val candidates = listOf(target, sibling(target, "bak"), sibling(target, "tmp"))
        candidates.forEachIndexed { index, candidate ->
            if (!candidate.isFile) return@forEachIndexed
            val text = runCatching { candidate.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return@forEachIndexed
            val value = runCatching { decode(text) }.getOrNull() ?: return@forEachIndexed
            if (index > 0) write(target, text)
            return value
        }
        return null
    }

    /**
     * A rename inside one folder. Atomic where the file system supports it, which is every phone the
     * app runs on; a plain replace keeps it working where it does not.
     */
    private fun replace(from: File, to: File) {
        val options = arrayOf(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        try {
            Files.move(from.toPath(), to.toPath(), *options)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
