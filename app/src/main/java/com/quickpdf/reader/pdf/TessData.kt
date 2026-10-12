package com.quickpdf.reader.pdf

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Tesseract language models (tessdata_fast, Apache-2.0), downloaded once on first use. */
object TessData {
    const val LANGUAGES = "rus+eng"

    // Pinned to one commit; every file is checked against its git blob SHA-1 from that commit.
    private const val BASE_URL =
        "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/"

    private class Model(val name: String, val size: Long, val gitSha1: String)

    private val MODELS = listOf(
        Model("rus.traineddata", 3_861_738, "b146cb2263acbc6383f8e92ea0ce759537687bb8"),
        Model("eng.traineddata", 4_113_088, "bbef4675053b5b468cdb477053e28b1c698ba08e"),
    )

    val totalBytes: Long = MODELS.sumOf { it.size }

    /** Downloads any missing model and returns the data path for [TesseractOcr]. */
    suspend fun ensure(context: Context, onProgress: (doneBytes: Long) -> Unit): File = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "tesseract")
        val dir = File(root, "tessdata").apply { mkdirs() }
        var done = 0L
        for (m in MODELS) {
            val file = File(dir, m.name)
            if (file.length() != m.size) {
                val before = done
                download(m, file) { onProgress(before + it) }
            }
            done += m.size
            onProgress(done)
        }
        root
    }

    private suspend fun download(m: Model, dest: File, onBytes: (Long) -> Unit) {
        val tmp = File(dest.path + ".part")
        val digest = MessageDigest.getInstance("SHA-1").apply { update("blob ${m.size}\u0000".toByteArray()) }
        val conn = URL(BASE_URL + m.name).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        total += n
                        onBytes(total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (tmp.length() != m.size || sha != m.gitSha1) {
            tmp.delete()
            throw IOException("${m.name} is damaged, please try again")
        }
        if (!tmp.renameTo(dest)) throw IOException("Couldn't save ${m.name}")
    }
}
