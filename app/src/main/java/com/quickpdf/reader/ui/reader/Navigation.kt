package com.quickpdf.reader.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.quickpdf.reader.R
import com.quickpdf.reader.data.Annotation
import com.quickpdf.reader.pdf.OutlineItem
import com.quickpdf.reader.pdf.PdfPageRenderer
import com.quickpdf.reader.pdf.Search
import com.quickpdf.reader.pdf.SearchHit
import com.quickpdf.reader.ui.theme.AnnotationColors
import kotlin.math.roundToInt

// ---- Fast scroller --------------------------------------------------------------------------------

/** Draggable handle on the right edge for jumping through long documents. */
@Composable
fun FastScroller(
    pageCount: Int,
    currentPage: Int,
    onDragState: (Boolean) -> Unit,
    onScrollTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pageCount < 2) return
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val thumbH = 48.dp
    BoxWithConstraints(modifier.fillMaxHeight().width(48.dp)) {
        val density = LocalDensity.current
        val trackPx = constraints.maxHeight.toFloat()
        val thumbPx = with(density) { thumbH.toPx() }
        val fraction = dragFraction ?: (currentPage.toFloat() / (pageCount - 1))
        val y = ((trackPx - thumbPx) * fraction).roundToInt()

        fun pageAt(posY: Float): Int {
            val f = ((posY - thumbPx / 2) / (trackPx - thumbPx)).coerceIn(0f, 1f)
            dragFraction = f
            return (f * (pageCount - 1)).roundToInt()
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(pageCount, trackPx) {
                    detectVerticalDragGestures(
                        onDragStart = { onDragState(true); onScrollTo(pageAt(it.y)) },
                        onDragEnd = { dragFraction = null; onDragState(false) },
                        onDragCancel = { dragFraction = null; onDragState(false) },
                        onVerticalDrag = { change, _ -> change.consume(); onScrollTo(pageAt(change.position.y)) },
                    )
                }
                .pointerInput(pageCount, trackPx) { detectTapGestures { onScrollTo(pageAt(it.y)) } }
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, y) }
                    .size(width = 28.dp, height = thumbH),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.UnfoldMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        if (dragFraction != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-44.dp.roundToPx(), y) },
            ) {
                Text(
                    "${currentPage + 1}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ---- Search ---------------------------------------------------------------------------------------

@Composable
fun SearchBarPanel(
    query: String,
    onQuery: (String) -> Unit,
    search: SearchState,
    index: IndexState,
    showResults: Boolean,
    onShowResults: (Boolean) -> Unit,
    onClose: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSelect: (Int) -> Unit,
    onRecognize: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }

    Column(modifier) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp) {
            Column(Modifier.statusBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close_search)) }
                    TextField(
                        value = query,
                        onValueChange = { onQuery(it); onShowResults(true) },
                        placeholder = { Text(stringResource(R.string.search_in_document)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery(""); focus.requestFocus() }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear)) }
                    }
                }
                if (query.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 4.dp),
                    ) {
                        Text(
                            statusText(search, index),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onShowResults(!showResults) }, enabled = search.hits.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Outlined.List, stringResource(R.string.show_all_results))
                        }
                        IconButton(onClick = onPrev, enabled = search.hits.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.previous)) }
                        IconButton(onClick = onNext, enabled = search.hits.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.next)) }
                    }
                }
                if (!index.done && index.total > 0) {
                    LinearProgressIndicator(
                        progress = { index.indexed.toFloat() / index.total },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        drawStopIndicator = {},
                    )
                }
            }
        }

        val noText = index.done && index.pages.none { it?.hasText == true }
        if (query.isNotBlank() && showResults && (search.hits.isNotEmpty() || (noText && onRecognize != null))) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)),
            ) {
                if (search.hits.isEmpty()) {
                    ScannedHint(onRecognize!!)
                } else {
                    ResultsList(search, onSelect)
                }
            }
        }
    }
}

@Composable
private fun statusText(search: SearchState, index: IndexState): String {
    val base = when {
        search.searching && search.hits.isEmpty() -> stringResource(R.string.searching)
        search.hits.isEmpty() -> stringResource(R.string.no_matches)
        else -> {
            val total = if (search.hits.size >= Search.MAX_HITS) "${Search.MAX_HITS}+" else "${search.hits.size}"
            val pages = search.byPage.size
            stringResource(R.string.match_position, search.current + 1, total, pluralStringResource(R.plurals.pages, pages, pages))
        }
    }
    return if (!index.done && index.total > 0) stringResource(R.string.status_indexing, base, index.indexed, index.total) else base
}

/** A hit's snippet with the match emphasized. */
@Composable
fun highlightedSnippet(hit: SearchHit): AnnotatedString {
    val highlight = SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.tertiaryContainer)
    return buildAnnotatedString {
        append(hit.snippet.substring(0, hit.snippetMatchStart))
        withStyle(highlight) { append(hit.snippet.substring(hit.snippetMatchStart, hit.snippetMatchEnd)) }
        append(hit.snippet.substring(hit.snippetMatchEnd))
    }
}

@Composable
private fun ResultsList(search: SearchState, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = (search.current - 1).coerceAtLeast(0)
    )
    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        items(search.hits.size) { i ->
            val hit = search.hits[i]
            val selected = i == search.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(i) }
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    stringResource(R.string.page_short, hit.page + 1),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(56.dp).padding(top = 2.dp),
                )
                Text(
                    highlightedSnippet(hit),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ScannedHint(onRecognize: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(stringResource(R.string.no_text_yet), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.scanned_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRecognize) {
            Icon(Icons.Outlined.DocumentScanner, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.recognize_text))
        }
    }
}

// ---- Contents / bookmarks / thumbnails ------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigateSheet(
    outline: List<OutlineItem>,
    bookmarks: Set<Int>,
    annotations: List<Annotation>,
    renderer: PdfPageRenderer,
    currentPage: Int,
    pageLabel: (Int) -> String,
    onJump: (Int) -> Unit,
    onOpenAnnotation: (Annotation) -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(if (outline.isEmpty()) 3 else 0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_contents)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_bookmarks)) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_notes)) })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text(stringResource(R.string.tab_pages)) })
        }
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            when (tab) {
                0 -> if (outline.isEmpty()) EmptyHint(stringResource(R.string.no_contents)) else
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(outline) { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = item.page >= 0) { onJump(item.page) }
                                    .padding(start = 20.dp + 16.dp * item.depth, end = 20.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    item.title,
                                    style = if (item.depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (item.page >= 0) {
                                    Text(
                                        "${item.page + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                1 -> if (bookmarks.isEmpty()) EmptyHint(stringResource(R.string.no_bookmarks)) else
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        item { ShareRow(onShare) }
                        items(bookmarks.sorted()) { page ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onJump(page) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Text(stringResource(R.string.page_n, page + 1), style = MaterialTheme.typography.titleSmall)
                                val label = pageLabel(page)
                                if (label.isNotBlank()) {
                                    Text(
                                        label, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }

                2 -> if (annotations.isEmpty()) EmptyHint(stringResource(R.string.no_notes)) else
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        item { ShareRow(onShare) }
                        items(annotations, key = { it.id }) { a -> AnnotationRow(a) { onOpenAnnotation(a) } }
                    }

                else -> ThumbnailGrid(renderer, currentPage, onJump)
            }
        }
    }
}

@Composable
private fun ShareRow(onShare: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onShare) {
            Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.share_notes))
        }
    }
}

@Composable
private fun AnnotationRow(a: Annotation, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(width = 4.dp, height = 40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AnnotationColors[a.color])
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.page_n, a.page + 1),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(a.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (a.note.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    a.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThumbnailGrid(renderer: PdfPageRenderer, currentPage: Int, onJump: (Int) -> Unit) {
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = currentPage)
    val thumbPx = with(LocalDensity.current) { 120.dp.roundToPx() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        state = state,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(renderer.pageCount, key = { it }) { page ->
            var bmp by remember(page) { mutableStateOf<Bitmap?>(renderer.cached(page, thumbPx)) }
            LaunchedEffect(page) { bmp = runCatching { renderer.render(page, thumbPx) }.getOrNull() }
            val selected = page == currentPage
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onJump(page) }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f / renderer.aspectRatios[page])
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                        .border(
                            if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            RoundedCornerShape(6.dp),
                        )
                ) {
                    bmp?.let {
                        Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                    }
                }
                Text(
                    "${page + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
