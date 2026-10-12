package com.quickpdf.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class RecentDoc(
    val uri: String,
    val name: String,
    val pageCount: Int,
    val lastPage: Int,
    val openedAt: Long,
    val bookmarks: Set<Int>,
)

/** Recently opened documents, reading positions, bookmarks and reader preferences. */
class Library(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    private val _recents = MutableStateFlow(load())
    val recents: StateFlow<List<RecentDoc>> = _recents.asStateFlow()

    private val _nightPages = MutableStateFlow(prefs.getBoolean(KEY_NIGHT_PAGES, false))
    val nightPages: StateFlow<Boolean> = _nightPages.asStateFlow()

    fun setNightPages(enabled: Boolean) {
        _nightPages.value = enabled
        prefs.edit().putBoolean(KEY_NIGHT_PAGES, enabled).apply()
    }

    fun get(uri: Uri): RecentDoc? = _recents.value.firstOrNull { it.uri == uri.toString() }

    /** Registers [uri] as just opened, keeping its stored position and bookmarks. */
    fun touch(uri: Uri, name: String, pageCount: Int): RecentDoc {
        val existing = get(uri)
        val doc = existing?.copy(name = name, pageCount = pageCount, openedAt = System.currentTimeMillis())
            ?: RecentDoc(uri.toString(), name, pageCount, 0, System.currentTimeMillis(), emptySet())
        update(doc)
        return doc
    }

    /** Changes often while reading, so it's only kept in memory until the next save or [flush]. */
    fun setLastPage(uri: Uri, page: Int) {
        val doc = get(uri) ?: return
        if (doc.lastPage != page) {
            _recents.value = _recents.value.map { if (it.uri == doc.uri) doc.copy(lastPage = page) else it }
        }
    }

    fun flush() = save()

    fun toggleBookmark(uri: Uri, page: Int) {
        val doc = get(uri) ?: return
        val marks = if (page in doc.bookmarks) doc.bookmarks - page else doc.bookmarks + page
        update(doc.copy(bookmarks = marks))
    }

    fun remove(uri: String) {
        _recents.value = _recents.value.filterNot { it.uri == uri }
        save()
    }

    private fun update(doc: RecentDoc) {
        val others = _recents.value.filterNot { it.uri == doc.uri }
        _recents.value = (listOf(doc) + others).sortedByDescending { it.openedAt }.take(MAX_RECENTS)
        save()
    }

    private fun save() {
        val arr = JSONArray()
        for (d in _recents.value) {
            arr.put(
                JSONObject()
                    .put("uri", d.uri)
                    .put("name", d.name)
                    .put("pages", d.pageCount)
                    .put("last", d.lastPage)
                    .put("at", d.openedAt)
                    .put("marks", JSONArray(d.bookmarks.sorted()))
            )
        }
        prefs.edit().putString(KEY_RECENTS, arr.toString()).apply()
    }

    private fun load(): List<RecentDoc> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_RECENTS, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val marks = o.optJSONArray("marks")
            RecentDoc(
                uri = o.getString("uri"),
                name = o.getString("name"),
                pageCount = o.optInt("pages"),
                lastPage = o.optInt("last"),
                openedAt = o.optLong("at"),
                bookmarks = if (marks == null) emptySet() else (0 until marks.length()).map { marks.getInt(it) }.toSet(),
            )
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val KEY_RECENTS = "recents"
        private const val KEY_NIGHT_PAGES = "night_pages"
        private const val MAX_RECENTS = 50

        data class FileInfo(val name: String, val size: Long)

        fun queryFileInfo(context: Context, uri: Uri): FileInfo {
            var name: String? = null
            var size = -1L
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            }
            val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"
            return FileInfo(name ?: fallback, size)
        }
    }
}
