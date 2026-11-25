package com.vinoteca.app.ai

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device preview for #2: ML Kit Latin text recognition runs in ~1s with no
 * network, so a wine list shows its lines before any provider answers. The
 * result is provisional — it is what the phone can read, not a ranking — and
 * the AI read replaces it. Failures (model not downloaded yet, blurry frame)
 * return empty rather than failing the scan.
 */
object LocalOcr {

    suspend fun previewLines(context: Context, uri: Uri): List<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val image = InputImage.fromFilePath(context, uri)
                val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val task = client.process(image)
                // Blocking wait on IO is fine; the caller already bounds the whole scan.
                val result = com.google.android.gms.tasks.Tasks.await(task, 10, java.util.concurrent.TimeUnit.SECONDS)
                client.close()
                result.textBlocks
                    .flatMap { block -> block.lines.map { it.text.trim() } }
                    .map { it.replace(Regex("\\s+"), " ").trim() }
                    .filter { it.length >= 3 }
                    .distinct()
                    .take(24)
            }.getOrElse { emptyList() }
        }
}
