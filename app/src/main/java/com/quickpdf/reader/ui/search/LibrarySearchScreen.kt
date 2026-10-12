package com.quickpdf.reader.ui.search

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quickpdf.reader.QuickPdfApp
import com.quickpdf.reader.R
import com.quickpdf.reader.data.Library
import com.quickpdf.reader.data.RecentDoc
import com.quickpdf.reader.pdf.IndexCache
import com.quickpdf.reader.pdf.PageText
import com.quickpdf.reader.pdf.Search
import com.quickpdf.reader.pdf.SearchHit
import com.quickpdf.reader.ui.reader.highlightedSnippet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocHits(val doc: RecentDoc, val hits: List<SearchHit>)

data class LibrarySearchState(
    val query: String = "",
    val results: List<DocHits> = emptyList(),
    val searched: Int = 0,
    val total: Int = 0,
    /** Recent documents without a saved index (never fully indexed, or password-protected). */
    val notIndexed: Int = 0,
) {
    val running: Boolean get() = searched < total
}

/** Searches the saved text index of every recent document, streaming results document by document. */
@OptIn(FlowPreview::class)
class LibrarySearchViewModel(app: Application) : AndroidViewModel(app) {
    private val library = (app as QuickPdfApp).library
    val query = MutableStateFlow("")
    private val _state = MutableStateFlow(LibrarySearchState())
    val state: StateFlow<LibrarySearchState> = _state.asStateFlow()

    init {
        viewModelScope.launch { query.debounce(350).collectLatest(::run) }
    }

    // ponytail: re-reads each index from disk per query (OS-cached, ~1 s for 50 books); keep them in memory if that's too slow.
    private suspend fun run(q: String) {
        val regex = Search.regexFor(q)
        if (regex == null) {
            _state.value = LibrarySearchState()
            return
        }
        val docs = library.recents.value
        val results = ArrayList<DocHits>()
        var notIndexed = 0
        _state.value = LibrarySearchState(q, total = docs.size)
        for ((i, doc) in docs.withIndex()) {
            val pages = withContext(Dispatchers.IO) { loadIndex(doc) }
            if (pages == null) {
                notIndexed++
            } else {
                val hits = withContext(Dispatchers.Default) { Search.run(pages, regex, limit = MAX_PER_DOC) }
                if (hits.isNotEmpty()) results += DocHits(doc, hits)
            }
            _state.value = LibrarySearchState(q, results.toList(), i + 1, docs.size, notIndexed)
        }
    }

    private fun loadIndex(doc: RecentDoc): Array<PageText?>? {
        val app = getApplication<Application>()
        val size = if (doc.size >= 0) doc.size else Library.queryFileInfo(app, Uri.parse(doc.uri)).size
        return IndexCache(app.filesDir, doc.uri, size).load(doc.pageCount)
    }

    companion object {
        const val MAX_PER_DOC = 500
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySearchScreen(onOpen: (uri: Uri, page: Int, query: String) -> Unit, onBack: () -> Unit) {
    val vm: LibrarySearchViewModel = viewModel()
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { vm.query.value = it },
                        placeholder = { Text(stringResource(R.string.search_all_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear)) }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val side = max(16.dp, (maxWidth - 720.dp) / 2)
                LazyColumn(
                    contentPadding = PaddingValues(start = side, end = side, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.query.isNotEmpty()) {
                        item { StatusLine(state) }
                    }
                    items(state.results, key = { it.doc.uri }) { r ->
                        DocResultCard(r, onOpen = { page -> onOpen(Uri.parse(r.doc.uri), page, state.query) })
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(state: LibrarySearchState) {
    val matches = state.results.sumOf { it.hits.size }
    val text = when {
        state.running -> stringResource(R.string.searching_docs, state.searched, state.total)
        state.results.isEmpty() -> stringResource(R.string.no_matches)
        else -> pluralStringResource(R.plurals.matches_in_docs, state.results.size, matches, state.results.size)
    }
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.running && state.notIndexed > 0) {
            Text(
                pluralStringResource(R.plurals.not_indexed, state.notIndexed, state.notIndexed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DocResultCard(r: DocHits, onOpen: (page: Int) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    r.doc.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val count = if (r.hits.size >= LibrarySearchViewModel.MAX_PER_DOC) "${r.hits.size}+" else "${r.hits.size}"
                Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp))
            }
            for (hit in r.hits.take(3)) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(hit.page) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        stringResource(R.string.page_short, hit.page + 1),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(56.dp).padding(top = 2.dp),
                    )
                    Text(highlightedSnippet(hit), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (r.hits.size > 3) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(r.hits.first().page) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(stringResource(R.string.open_all_matches), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
