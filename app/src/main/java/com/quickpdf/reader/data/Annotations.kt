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

/**
 * Highlights and notes of one document, stored as a small JSON file keyed by the document's [docKey].
 * Before 1.2 they were keyed by URI; files under [legacyUris] are merged in and removed on load.
 */
class AnnotationStore(dir: File, key: String, private val legacyUris: Collection<String> = emptyList()) {
    private val folder = File(dir, "annotations").apply { mkdirs() }
    private val file = folder.resolve(sha1Hex("doc|$key") + ".json")

    fun load(): List<Annotation> {
        val legacy = legacyUris.map { folder.resolve(sha1Hex(it) + ".json") }.filter { it.exists() }
        if (legacy.isNotEmpty()) {
            save((read(file) + legacy.flatMap(::read)).distinctBy { it.id }.sortedWith(compareBy({ it.page }, { it.start })))
            legacy.forEach { it.delete() }
        }
        return read(file)
    }

    fun save(list: List<Annotation>) {
        val arr = JSONArray()
        for (a in list) arr.put(a.toJson())
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    private fun read(f: File): List<Annotation> = runCatching { annotationsFromJson(JSONArray(f.readText())) }.getOrDefault(emptyList())
}

fun Annotation.toJson(): JSONObject = JSONObject()
    .put("id", id).put("page", page).put("start", start).put("end", end)
    .put("color", color).put("note", note).put("text", text).put("at", createdAt)

fun annotationsFromJson(arr: JSONArray): List<Annotation> = (0 until arr.length()).map { i ->
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
