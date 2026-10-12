package com.quickpdf.reader.data

import com.quickpdf.reader.pdf.sha1Hex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A highlight on page text [start, end) (offsets into that page's indexed text), optionally with a note. */
data class Annotation(
    val id: Long,
    val page: Int,
    val start: Int,
    val end: Int,
    val color: Int,
    val note: String,
    /** The highlighted text, kept for lists and export. */
    val text: String,
    val createdAt: Long,
)

/** Highlights and notes of one document, stored as a small JSON file keyed by its URI. */
class AnnotationStore(dir: File, uri: String) {
    private val file = File(dir, "annotations").apply { mkdirs() }.resolve(sha1Hex(uri) + ".json")

    fun load(): List<Annotation> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Annotation(
                id = o.getLong("id"),
                page = o.getInt("page"),
                start = o.getInt("start"),
                end = o.getInt("end"),
                color = o.optInt("color"),
                note = o.optString("note"),
                text = o.optString("text"),
                createdAt = o.optLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    fun save(list: List<Annotation>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("id", a.id).put("page", a.page).put("start", a.start).put("end", a.end)
                    .put("color", a.color).put("note", a.note).put("text", a.text).put("at", a.createdAt)
            )
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
