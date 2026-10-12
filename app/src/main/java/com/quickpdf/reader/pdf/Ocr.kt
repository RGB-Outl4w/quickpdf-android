package com.quickpdf.reader.pdf

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable

/** On-device text recognition for scanned pages (ML Kit via Google Play services, Latin script). */
class Ocr : Closeable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): PageText {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val sb = StringBuilder()
        val words = ArrayList<WordBox>()
        for (block in result.textBlocks) {
            if (sb.isNotEmpty()) sb.append('\n')
            for (line in block.lines) {
                for ((i, el) in line.elements.withIndex()) {
                    if (i > 0) sb.append(' ')
                    val start = sb.length
                    sb.append(el.text)
                    el.boundingBox?.let { r ->
                        words += WordBox(start, sb.length, r.left / w, r.top / h, r.right / w, r.bottom / h)
                    }
                }
                sb.append('\n')
            }
        }
        return PageText(sb.toString(), PageText.SOURCE_OCR, words)
    }

    override fun close() = recognizer.close()
}
