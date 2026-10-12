package com.quickpdf.reader.pdf

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** On-device text recognition for scanned pages. Not thread-safe: recognize one page at a time. */
interface Ocr : Closeable {
    suspend fun recognize(bitmap: Bitmap): PageText
}

/** ML Kit via Google Play services, Latin script. Fast; the model isn't bundled in the APK. */
class MlKitOcr : Ocr {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): PageText {
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

/**
 * Tesseract for Cyrillic books (ML Kit has no Cyrillic model). Slower than ML Kit.
 * Create it off the main thread: loading the models takes a moment.
 */
class TesseractOcr(dataPath: File) : Ocr {
    private val api = TessBaseAPI()

    init {
        if (!api.init(dataPath.path, TessData.LANGUAGES)) {
            api.recycle()
            error("Couldn't load the text recognition models")
        }
    }

    override suspend fun recognize(bitmap: Bitmap): PageText = withContext(Dispatchers.Default) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val sb = StringBuilder()
        val words = ArrayList<WordBox>()
        api.setImage(bitmap)
        api.getUTF8Text() // runs recognition
        val iter = api.resultIterator
        if (iter != null) {
            try {
                iter.begin()
                do {
                    val word = iter.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    if (word.isNullOrBlank()) continue
                    if (sb.isNotEmpty()) sb.append(if (iter.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) '\n' else ' ')
                    val start = sb.length
                    sb.append(word)
                    val r = iter.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    words += WordBox(start, sb.length, r.left / w, r.top / h, r.right / w, r.bottom / h)
                } while (iter.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
            } finally {
                iter.delete()
            }
        }
        api.clear()
        PageText(sb.toString(), PageText.SOURCE_OCR, words)
    }

    override fun close() = api.recycle()
}
