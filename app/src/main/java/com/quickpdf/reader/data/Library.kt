package com.quickpdf.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import org.json.JSONArray
import org.json.JSONObject

data class RecentDoc(
    val uri: String,
    val name: String,
    val pageCount: Int,
    val lastPage: Int,
    val openedAt: Long,
    val bookmarks: Set<Int>,
    /** File size in bytes, part of the search index key; -1 if unknown (documents opened before 1.1). */
    val size: Long = -1,
    /** Pinned documents stay at the top of the list, in the order the user arranged them. */
    val pinned: Boolean = false,
) {
    val key: String get() = docKey(uri, name, size)
}

/**
 * Identifies a document by its file rather than by the URI it was opened through: the same PDF opened from the
 * file picker, a file manager or a share sheet arrives under different URIs. Falls back to the URI when the size
 * is unknown.
 */
fun docKey(uri: String, name: String, size: Long): String = if (size >= 0) "$name|$size" else uri

/** Pinned documents first in their stored order, then the rest, most recently opened first. */
fun arrangeRecents(docs: List<RecentDoc>, maxUnpinned: Int): List<RecentDoc> {
    val (pinned, rest) = docs.partition { it.pinned }
    return pinned + rest.sortedByDescending { it.openedAt }.take(maxUnpinned)
}

/** How pages are colored: as is, or remapped to paper and ink colors (night inverts). */
enum class PageTint { ORIGINAL, SEPIA, NIGHT, NIGHT_SEPIA }

/** Light or dark app theme, or follow the system. */
enum class AppTheme { SYSTEM, LIGHT, DARK }

data class Settings(
    val tint: PageTint = PageTint.ORIGINAL,
    /** Swipe page by page instead of scrolling continuously. */
    val paged: Boolean = false,
    /** Continuous mode: scale each page to fit the screen instead of its width. */
    val fitPage: Boolean = false,
    /** Page-by-page mode: two pages side by side on wide screens. */
    val spreads: Boolean = true,
    val keepScreenOn: Boolean = false,
    val checkUpdates: Boolean = true,
    val theme: AppTheme = AppTheme.SYSTEM,
)

/** Recently opened documents, reading positions, bookmarks and settings. */
class Library(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    private val _recents = MutableStateFlow(arrange(load()))
    val recents: StateFlow<List<RecentDoc>> = _recents.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun updateSettings(change: (Settings) -> Settings) {
        val s = _settings.updateAndGet(change)
        prefs.edit()
            .putString(KEY_TINT, s.tint.name)
            .putBoolean(KEY_PAGED, s.paged)
            .putBoolean(KEY_FIT_PAGE, s.fitPage)
            .putBoolean(KEY_SPREADS, s.spreads)
            .putBoolean(KEY_KEEP_ON, s.keepScreenOn)
            .putBoolean(KEY_CHECK_UPDATES, s.checkUpdates)
            .putString(KEY_THEME, s.theme.name)
            .apply()
    }

    fun get(uri: String): RecentDoc? = _recents.value.firstOrNull { it.uri == uri }

    /** Entries for the file at [uri]: the one under that URI and any opened through another URI. */
    fun sameDocument(uri: Uri, name: String, size: Long): List<RecentDoc> {
        val u = uri.toString()
        val key = docKey(u, name, size)
        return _recents.value.filter { it.uri == u || it.key == key }
    }

    /**
     * Registers [uri] as just opened, keeping its stored position, bookmarks and pin. If the file is already listed
     * under other URIs, those entries are merged into one, keeping a URI that still opens after a restart.
     */
    fun touch(uri: Uri, name: String, pageCount: Int, size: Long): RecentDoc {
        val u = uri.toString()
        val same = sameDocument(uri, name, size)
        val persisted = runCatching { context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet() }
            .getOrDefault(emptySet())
        val keepUri = if (u in persisted) u else same.firstOrNull { it.uri in persisted }?.uri ?: u
        val latest = same.maxByOrNull { it.openedAt }
        val doc = RecentDoc(
            uri = keepUri,
            name = name,
            pageCount = pageCount,
            lastPage = latest?.lastPage ?: 0,
            openedAt = System.currentTimeMillis(),
            bookmarks = same.flatMap { it.bookmarks }.toSet(),
            size = size,
            pinned = same.any { it.pinned },
        )
        replace(same.map { it.uri }.toSet(), doc)
        return doc
    }

    /** Changes often while reading, so it's only kept in memory until the next save or [flush]. */
    fun setLastPage(uri: String, page: Int) {
        val doc = get(uri) ?: return
        if (doc.lastPage != page) {
            _recents.value = _recents.value.map { if (it.uri == doc.uri) doc.copy(lastPage = page) else it }
        }
    }

    fun flush() = save()

    fun toggleBookmark(uri: String, page: Int) {
        val doc = get(uri) ?: return
        val marks = if (page in doc.bookmarks) doc.bookmarks - page else doc.bookmarks + page
        replace(setOf(doc.uri), doc.copy(bookmarks = marks))
    }

    /** Removes [uri] from the list and returns it, for [restore]. Its highlights and notes stay on disk. */
    fun remove(uri: String): RecentDoc? {
        val doc = get(uri) ?: return null
        _recents.value = _recents.value.filterNot { it.uri == uri }
        save()
        return doc
    }

    /** Puts back a document taken out with [remove], in its old place if it was pinned. */
    fun restore(doc: RecentDoc, index: Int) {
        if (get(doc.uri) != null) return
        _recents.value = arrange(_recents.value.toMutableList().apply { add(index.coerceIn(0, size), doc) })
        save()
    }

    /** Pins [uri] below the other pinned documents, or unpins it. */
    fun setPinned(uri: String, pinned: Boolean) {
        val doc = get(uri) ?: return
        val others = _recents.value.filterNot { it.uri == uri }
        val pinnedCount = others.count { it.pinned }
        _recents.value = arrange(others.toMutableList().apply { add(pinnedCount, doc.copy(pinned = pinned)) })
        save()
    }

    /** Sets the order of the pinned documents to [uris]. */
    fun reorderPinned(uris: List<String>) {
        val byUri = _recents.value.filter { it.pinned }.associateBy { it.uri }
        val ordered = uris.mapNotNull { byUri[it] }
        val missed = byUri.values.filterNot { it.uri in uris }
        _recents.value = ordered + missed + _recents.value.filterNot { it.pinned }
        save()
    }

    /**
     * Adds documents from a backup. Documents already listed (by file, see [docKey]) get the backup's bookmarks
     * added to theirs; the rest are added as they were. Returns how many documents were in the backup.
     */
    fun import(docs: List<RecentDoc>): Int {
        val list = _recents.value.toMutableList()
        for (d in docs) {
            val i = list.indexOfFirst { it.uri == d.uri || it.key == d.key }
            if (i >= 0) {
                val cur = list[i]
                list[i] = cur.copy(bookmarks = cur.bookmarks + d.bookmarks, pinned = cur.pinned || d.pinned)
            } else {
                list += d
            }
        }
        _recents.value = arrange(list)
        save()
        return docs.size
    }

    /** Replaces the entries for [uris] with [doc], at the place of the first of them (that's where a pinned doc stays). */
    private fun replace(uris: Set<String>, doc: RecentDoc) {
        val list = _recents.value
        val at = list.indexOfFirst { it.uri in uris }
        val rest = list.filterNot { it.uri in uris }.toMutableList()
        rest.add(if (at < 0) 0 else at.coerceAtMost(rest.size), doc)
        _recents.value = arrange(rest)
        save()
    }

    private fun arrange(docs: List<RecentDoc>) = arrangeRecents(docs, MAX_RECENTS)

    private fun save() {
        val arr = JSONArray()
        for (d in _recents.value) arr.put(d.toJson())
        prefs.edit().putString(KEY_RECENTS, arr.toString()).apply()
    }

    private fun load(): List<RecentDoc> = runCatching {
        recentsFromJson(JSONArray(prefs.getString(KEY_RECENTS, "[]")))
    }.getOrDefault(emptyList())

    private fun loadSettings(): Settings {
        val d = Settings()
        // 1.0 only had an on/off night mode.
        val legacyTint = if (prefs.getBoolean(KEY_NIGHT_PAGES, false)) PageTint.NIGHT else d.tint
        return Settings(
            tint = prefs.getString(KEY_TINT, null)?.let { runCatching { PageTint.valueOf(it) }.getOrNull() } ?: legacyTint,
            paged = prefs.getBoolean(KEY_PAGED, d.paged),
            fitPage = prefs.getBoolean(KEY_FIT_PAGE, d.fitPage),
            spreads = prefs.getBoolean(KEY_SPREADS, d.spreads),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_ON, d.keepScreenOn),
            checkUpdates = prefs.getBoolean(KEY_CHECK_UPDATES, d.checkUpdates),
            theme = prefs.getString(KEY_THEME, null)?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() } ?: d.theme,
        )
    }

    companion object {
        private const val KEY_RECENTS = "recents"
        private const val KEY_NIGHT_PAGES = "night_pages"
        private const val KEY_TINT = "tint"
        private const val KEY_PAGED = "paged"
        private const val KEY_FIT_PAGE = "fit_page"
        private const val KEY_SPREADS = "spreads"
        private const val KEY_KEEP_ON = "keep_screen_on"
        private const val KEY_CHECK_UPDATES = "check_updates"
        private const val KEY_THEME = "theme"
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

fun RecentDoc.toJson(): JSONObject = JSONObject()
    .put("uri", uri)
    .put("name", name)
    .put("pages", pageCount)
    .put("last", lastPage)
    .put("at", openedAt)
    .put("marks", JSONArray(bookmarks.sorted()))
    .put("size", size)
    .put("pinned", pinned)

fun recentsFromJson(arr: JSONArray): List<RecentDoc> = (0 until arr.length()).map { i ->
    val o = arr.getJSONObject(i)
    val marks = o.optJSONArray("marks")
    RecentDoc(
        uri = o.getString("uri"),
        name = o.getString("name"),
        pageCount = o.optInt("pages"),
        lastPage = o.optInt("last"),
        openedAt = o.optLong("at"),
        bookmarks = if (marks == null) emptySet() else (0 until marks.length()).map { marks.getInt(it) }.toSet(),
        size = o.optLong("size", -1),
        pinned = o.optBoolean("pinned"),
    )
}
