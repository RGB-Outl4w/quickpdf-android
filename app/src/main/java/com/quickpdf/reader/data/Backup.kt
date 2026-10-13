package com.quickpdf.reader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Recent documents with their reading positions and bookmarks, plus highlights and notes, as one JSON file.
 * Restoring adds to what's there. Documents are matched by file ([docKey]), so after a restore on another device
 * a document gets its bookmarks and notes back once it is opened again from the file picker.
 */
object Backup {
    private const val APP = "QuickPDF"
    private const val FORMAT = 1

    fun write(context: Context, library: Library, out: OutputStream) {
        val docs = library.recents.value
        val notes = JSONObject()
        for (d in docs) {
            val list = AnnotationStore(context.filesDir, d.key, listOf(d.uri)).load()
            if (list.isNotEmpty()) notes.put(d.key, JSONArray(list.map { it.toJson() }))
        }
        val json = JSONObject()
            .put("app", APP)
            .put("format", FORMAT)
            .put("recents", JSONArray(docs.map { it.toJson() }))
            .put("annotations", notes)
        out.writer().use { it.write(json.toString()) }
    }

    /** Returns the number of documents in the backup; throws if [input] isn't a QuickPDF backup. */
    fun read(context: Context, library: Library, input: InputStream): Int {
        val json = JSONObject(input.reader().use { it.readText() })
        require(json.optString("app") == APP && json.optInt("format") in 1..FORMAT) { "Not a QuickPDF backup" }
        val docs = recentsFromJson(json.getJSONArray("recents"))
        val notes = json.optJSONObject("annotations") ?: JSONObject()
        for (key in notes.keys()) {
            val store = AnnotationStore(context.filesDir, key)
            val merged = (store.load() + annotationsFromJson(notes.getJSONArray(key))).distinctBy { it.id }
            store.save(merged.sortedWith(compareBy({ it.page }, { it.start })))
        }
        return library.import(docs)
    }
}
