package com.wineverdict.app.data

import android.content.Context
import com.wineverdict.app.ai.ScanParsing
import com.wineverdict.app.ai.SceneScan
import java.io.File

/**
 * A finished list scan read from app storage, for debug builds only.
 *
 * The advice block cannot be reached without a provider: the read is a vision call and the advice
 * is a text call, so a machine with no key never renders it. This reads two files the app can reach
 * with `run-as` — `debug-scan.json` in the same shape the vision reply has, and `debug-advice.txt`
 * in the PICK/WHY shape — and puts them through [ScanParsing] itself, so what is on screen is what
 * a live answer would put there, validators included.
 *
 * Nothing here ships a behaviour: [com.wineverdict.app.ui.ScanScreen] asks for it only under
 * `BuildConfig.DEBUG`, nothing is written, and both files are absent on a phone.
 */
object DebugScan {
    private const val SCAN = "debug-scan.json"
    private const val ADVICE = "debug-advice.txt"

    fun load(context: Context, currency: String): SceneScan? {
        val read = runCatching {
            ScanParsing.parseScene(File(context.filesDir, SCAN).readText(), currency)
        }.getOrNull() ?: return null
        if (read.bottles.isEmpty()) return null
        val advice = runCatching {
            File(context.filesDir, ADVICE).takeIf { it.isFile }?.readText()
        }.getOrNull()
        return advice?.let { ScanParsing.applyAdvice(read, it) } ?: read
    }
}
