package com.quickpdf.reader.ui.reader

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quickpdf.reader.R
import com.quickpdf.reader.data.Annotation
import com.quickpdf.reader.pdf.SearchHit
import com.quickpdf.reader.pdf.highlightRects
import com.quickpdf.reader.ui.settings.ReadingSettings
import com.quickpdf.reader.ui.theme.AnnotationColors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

/** Opens [uri]; [startPage] and [initialQuery] come from library search. */
@Composable
fun ReaderScreen(uri: Uri, startPage: Int?, initialQuery: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ReaderViewModel = viewModel(key = uri.toString(), factory = ReaderViewModel.factory(app, uri, startPage))
    val doc by vm.doc.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)
    when (val d = doc) {
        DocState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is DocState.Error -> ErrorView(d.message.resolve(LocalContext.current), onBack)
        is DocState.NeedsPassword -> PasswordView(d.wrong, vm::submitPassword, onBack)
        is DocState.Ready -> Reader(vm, d, initialQuery, onBack)
    }
}

/** A highlight being created from the selection (id 0) or an existing one being edited. */
private class EditedAnnotation(val annotation: Annotation, val isNew: Boolean)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
private fun Reader(vm: ReaderViewModel, doc: DocState.Ready, initialQuery: String?, onBack: () -> Unit) {
    val renderer = doc.renderer
    val context = LocalContext.current
    val index by vm.index.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val outline by vm.outline.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ocr by vm.ocr.collectAsStateWithLifecycle()
    val annotations by vm.annotations.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var barsVisible by rememberSaveable { mutableStateOf(true) }
    var searching by rememberSaveable { mutableStateOf(initialQuery != null) }
    var query by rememberSaveable { mutableStateOf(initialQuery.orEmpty()) }
    var initialQueryApplied by rememberSaveable { mutableStateOf(false) }
    var showResults by rememberSaveable { mutableStateOf(initialQuery == null) }
    var showNavigate by rememberSaveable { mutableStateOf(false) }
    var showGoTo by rememberSaveable { mutableStateOf(false) }
    var showReadingSettings by rememberSaveable { mutableStateOf(false) }
    var ocrBannerDismissed by rememberSaveable { mutableStateOf(false) }
    var showOcrLanguage by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditedAnnotation?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var lastPage by rememberSaveable { mutableIntStateOf(doc.initialPage) }

    LaunchedEffect(Unit) {
        if (initialQuery != null && !initialQueryApplied) {
            vm.setQuery(initialQuery)
            initialQueryApplied = true
        }
    }

    // ---- Layout: continuous list or pages side by side --------------------------------------------
    val configuration = LocalConfiguration.current
    val twoUp = settings.paged && settings.spreads &&
        configuration.screenWidthDp > configuration.screenHeightDp && configuration.screenWidthDp >= 600
    val zoom = remember { ZoomState() }
    val pagedZoom = remember { PagedZoom() }
    val nav: PageNavigator
    val pageView: @Composable (Modifier, PageContent) -> Unit
    if (!settings.paged) {
        val listState = key("list") { rememberLazyListState(initialFirstVisibleItemIndex = lastPage) }
        nav = remember(listState) { ListNavigator(listState, zoom, renderer) }
        val insetsTop = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
        val insetsBottom = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
        pageView = { modifier, content ->
            PageList(
                renderer = renderer,
                listState = listState,
                zoom = zoom,
                contentPadding = PaddingValues(top = insetsTop + 72.dp, bottom = insetsBottom + 80.dp),
                tint = settings.tint,
                fitPage = settings.fitPage,
                onTap = { if (!searching && selection == null) barsVisible = !barsVisible },
                pageContent = content,
                modifier = modifier,
            )
        }
    } else {
        val spreads = remember(renderer.pageCount, twoUp) { spreadsFor(renderer.pageCount, twoUp) }
        val pagerState = key(twoUp) { rememberPagerState(initialPage = spreadIndexOf(lastPage, twoUp)) { spreads.size } }
        nav = remember(pagerState, spreads) { PagerNavigator(pagerState, spreads, twoUp) }
        val insetsTop = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
        val insetsBottom = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
        pageView = { modifier, content ->
            PagedView(
                renderer = renderer,
                pagerState = pagerState,
                spreads = spreads,
                zoom = pagedZoom,
                tint = settings.tint,
                contentPadding = PaddingValues(top = insetsTop + 64.dp, bottom = insetsBottom + 8.dp),
                onTap = { if (!searching && selection == null) barsVisible = !barsVisible },
                pageContent = content,
                modifier = modifier,
            )
        }
    }
    val currentPage = nav.currentPage

    LaunchedEffect(nav) {
        snapshotFlow { nav.currentPage }.distinctUntilChanged().collect { lastPage = it }
    }
    LaunchedEffect(nav) {
        snapshotFlow { nav.currentPage }.distinctUntilChanged().debounce(400).collectLatest { vm.onPageChanged(it) }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    ImmersiveMode(visible = barsVisible || searching || selection != null)
    KeepScreenOn(settings.keepScreenOn)

    fun jumpToPage(page: Int) {
        scope.launch { nav.goTo(page) }
    }

    fun jumpTo(page: Int, start: Int, end: Int) {
        scope.launch {
            val rect = vm.charBoxes(page)?.let { highlightRects(it, start, end).firstOrNull() }
            nav.goTo(page, rect?.centerX(), rect?.top)
        }
    }

    fun jumpToHit(hit: SearchHit?) {
        hit?.let { jumpTo(it.page, it.start, it.end) }
    }

    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(text.take(40), text))
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.copied)) }
        }
    }

    fun shareText(text: String) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    fun shareNotes() {
        shareText(notesExport(context, doc.name, bookmarks, annotations) { p -> vm.pageText(p)?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() })
    }

    val notesByPage = remember(annotations) { annotations.groupBy { it.page } }
    val pageContent: PageContent = { page, renderWidth, modifier ->
        PageView(
            page = page,
            renderer = renderer,
            renderWidth = renderWidth,
            tint = settings.tint,
            hits = search.byPage[page],
            currentHit = search.currentHit,
            annotations = notesByPage[page].orEmpty(),
            selection = selection?.takeIf { it.page == page },
            pageText = { vm.pageText(it)?.text },
            charBoxes = vm::charBoxes,
            onSelect = vm::select,
            onAnnotationTap = { editing = EditedAnnotation(it, isNew = false) },
            modifier = modifier,
        )
    }

    val insetsBottom = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
    val insetsTop = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize()) {
        pageView(Modifier.fillMaxSize(), pageContent)

        // Top: selection actions, search bar or app bar, then status banners.
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            val sel = selection
            when {
                sel != null -> SelectionBar(
                    onClose = { vm.select(null) },
                    onCopy = { copy(vm.selectedText()); vm.select(null) },
                    onHighlight = { vm.addAnnotation(sel, color = 0) },
                    onNote = {
                        val now = System.currentTimeMillis()
                        editing = EditedAnnotation(Annotation(0, sel.page, sel.start, sel.end, 0, "", vm.selectedText(), now), isNew = true)
                    },
                    onSearch = {
                        val text = vm.selectedText().take(100)
                        vm.select(null)
                        searching = true
                        showResults = true
                        query = text
                        vm.setQuery(text)
                    },
                    onShare = { shareText(vm.selectedText()) },
                )
                searching -> SearchBarPanel(
                    query = query,
                    onQuery = { query = it; vm.setQuery(it) },
                    search = search,
                    index = index,
                    showResults = showResults,
                    onShowResults = { showResults = it },
                    onClose = { searching = false; query = ""; vm.setQuery("") },
                    onPrev = { jumpToHit(vm.prevHit()) },
                    onNext = { jumpToHit(vm.nextHit()) },
                    onSelect = { i -> showResults = false; jumpToHit(vm.selectHit(i)) },
                    onRecognize = if (ocr == null) ({ showOcrLanguage = true }) else null,
                )
                else -> AnimatedVisibility(
                    visible = barsVisible,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                ) {
                    Column {
                        ReaderTopBar(
                            title = doc.name,
                            subtitle = stringResource(R.string.page_of, currentPage + 1, renderer.pageCount),
                            bookmarked = currentPage in bookmarks,
                            canOcr = index.done && ocr == null,
                            onBack = onBack,
                            onSearch = { searching = true; showResults = true },
                            onNavigate = { showNavigate = true },
                            onBookmark = { vm.toggleBookmark(currentPage) },
                            onGoTo = { showGoTo = true },
                            onReadingSettings = { showReadingSettings = true },
                            onOcr = { showOcrLanguage = true },
                            onShareNotes = if (bookmarks.isEmpty() && annotations.isEmpty()) null else ::shareNotes,
                        )
                        if (!index.done && index.total > 0) {
                            LinearProgressIndicator(
                                progress = { index.indexed.toFloat() / index.total },
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                drawStopIndicator = {},
                            )
                        }
                    }
                }
            }

            val progress = ocr
            if (progress != null) {
                OcrProgressBanner(progress, onCancel = vm::cancelOcr)
            } else if (index.done && !ocrBannerDismissed && !searching && selection == null && index.scannedPages > 0 &&
                index.scannedPages >= index.total * 0.3
            ) {
                ScannedBanner(
                    pages = index.scannedPages,
                    onRecognize = { showOcrLanguage = true },
                    onDismiss = { ocrBannerDismissed = true },
                )
            }
        }

        // Right edge fast scroller.
        AnimatedVisibility(
            visible = barsVisible || scrubbing || nav.isMoving,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).padding(top = insetsTop + 72.dp, bottom = insetsBottom + 72.dp),
        ) {
            FastScroller(
                pageCount = renderer.pageCount,
                currentPage = currentPage,
                onDragState = { scrubbing = it },
                onScrollTo = { page -> jumpToPage(page) },
            )
        }

        // Bottom page chip.
        AnimatedVisibility(
            visible = barsVisible || scrubbing || nav.isMoving,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
        ) {
            Surface(
                onClick = { showGoTo = true },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Text(
                    "${currentPage + 1} / ${renderer.pageCount}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 56.dp))
    }

    if (searching && selection == null) BackHandler { searching = false; query = ""; vm.setQuery("") }
    if (selection != null) BackHandler { vm.select(null) }

    if (showNavigate) {
        NavigateSheet(
            outline = outline,
            bookmarks = bookmarks,
            annotations = annotations,
            renderer = renderer,
            currentPage = currentPage,
            pageLabel = { p -> vm.pageText(p)?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty() },
            onJump = { p -> showNavigate = false; jumpToPage(p) },
            onOpenAnnotation = { a -> showNavigate = false; jumpTo(a.page, a.start, a.end) },
            onShare = ::shareNotes,
            onDismiss = { showNavigate = false },
        )
    }

    if (showReadingSettings) {
        ModalBottomSheet(onDismissRequest = { showReadingSettings = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                ReadingSettings(settings, vm::updateSettings)
            }
        }
    }

    editing?.let { e ->
        AnnotationDialog(
            annotation = e.annotation,
            isNew = e.isNew,
            onSave = { color, note ->
                val a = e.annotation
                if (e.isNew) vm.addAnnotation(TextSelection(a.page, a.start, a.end), color, note)
                else vm.updateAnnotation(a.copy(color = color, note = note))
                editing = null
            },
            onCopy = { copy(e.annotation.text) },
            onDelete = { vm.deleteAnnotation(e.annotation.id); editing = null },
            onDismiss = { editing = null },
        )
    }

    if (showOcrLanguage) {
        OcrLanguageDialog(
            onStart = { cyrillic -> showOcrLanguage = false; vm.startOcr(cyrillic) },
            onDismiss = { showOcrLanguage = false },
        )
    }

    if (showGoTo) {
        GoToPageDialog(
            pageCount = renderer.pageCount,
            onGo = { p -> showGoTo = false; jumpToPage(p) },
            onDismiss = { showGoTo = false },
        )
    }
}

/** Bookmarks, highlights and notes of a document as plain text for sharing. */
private fun notesExport(
    context: Context,
    docName: String,
    bookmarks: Set<Int>,
    annotations: List<Annotation>,
    pageLabel: (Int) -> String?,
): String = buildString {
    appendLine(docName)
    if (bookmarks.isNotEmpty()) {
        appendLine()
        appendLine(context.getString(R.string.tab_bookmarks))
        for (p in bookmarks.sorted()) {
            val label = pageLabel(p)?.take(80)
            appendLine(if (label.isNullOrBlank()) "• ${context.getString(R.string.page_n, p + 1)}" else "• ${context.getString(R.string.page_n, p + 1)} — $label")
        }
    }
    if (annotations.isNotEmpty()) {
        appendLine()
        appendLine(context.getString(R.string.export_highlights))
        for (a in annotations) {
            appendLine("• ${context.getString(R.string.page_n, a.page + 1)}: “${a.text}”")
            if (a.note.isNotEmpty()) appendLine("  ${context.getString(R.string.export_note, a.note)}")
        }
    }
}.trimEnd()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    title: String,
    subtitle: String,
    bookmarked: Boolean,
    canOcr: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onNavigate: () -> Unit,
    onBookmark: () -> Unit,
    onGoTo: () -> Unit,
    onReadingSettings: () -> Unit,
    onOcr: () -> Unit,
    onShareNotes: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, stringResource(R.string.search)) }
            IconButton(onClick = onNavigate) { Icon(Icons.AutoMirrored.Outlined.Toc, stringResource(R.string.navigate)) }
            IconButton(onClick = onBookmark) {
                if (bookmarked) Icon(Icons.Filled.Bookmark, stringResource(R.string.remove_bookmark), tint = MaterialTheme.colorScheme.primary)
                else Icon(Icons.Outlined.BookmarkBorder, stringResource(R.string.add_bookmark))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.go_to_page)) },
                        leadingIcon = { Icon(Icons.Outlined.Tag, null) },
                        onClick = { menu = false; onGoTo() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reading_settings)) },
                        leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                        onClick = { menu = false; onReadingSettings() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recognize_text_menu)) },
                        leadingIcon = { Icon(Icons.Outlined.DocumentScanner, null) },
                        enabled = canOcr,
                        onClick = { menu = false; onOcr() },
                    )
                    if (onShareNotes != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share_notes)) },
                            leadingIcon = { Icon(Icons.Outlined.Share, null) },
                            onClick = { menu = false; onShareNotes() },
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        ),
    )
}

/** Contextual actions while text is selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onNote: () -> Unit,
    onSearch: () -> Unit,
    onShare: () -> Unit,
) {
    TopAppBar(
        title = {},
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_selection)) } },
        actions = {
            IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy)) }
            IconButton(onClick = onHighlight) { Icon(Icons.Outlined.BorderColor, stringResource(R.string.highlight)) }
            IconButton(onClick = onNote) { Icon(Icons.Outlined.EditNote, stringResource(R.string.add_note)) }
            IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, stringResource(R.string.search)) }
            IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

/** Color, note, copy and delete for a highlight; also used to add a note to the current selection. */
@Composable
private fun AnnotationDialog(
    annotation: Annotation,
    isNew: Boolean,
    onSave: (color: Int, note: String) -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var color by rememberSaveable { mutableIntStateOf(annotation.color) }
    var note by rememberSaveable { mutableStateOf(annotation.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.add_note else R.string.highlight_title, annotation.page + 1)) },
        text = {
            Column {
                Text(
                    "“${annotation.text}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    for ((i, c) in AnnotationColors.all.withIndex()) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    if (i == color) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                                    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    CircleShape,
                                )
                                .selectable(selected = i == color, onClick = { color = i })
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy)) }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.note)) },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(color, note) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            if (isNew) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            else TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
    )
}

@Composable
private fun ScannedBanner(pages: Int, onRecognize: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DocumentScanner, null)
                Spacer(Modifier.width(12.dp))
                Text(pluralStringResource(R.plurals.scanned_pages, pages, pages), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now)) }
                TextButton(onClick = onRecognize) { Text(stringResource(R.string.recognize)) }
            }
        }
    }
}

@Composable
private fun OcrProgressBanner(progress: OcrProgress, onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (progress.downloadingModels) {
                        stringResource(R.string.ocr_downloading, progress.done / 1024f, progress.total / 1024f)
                    } else {
                        stringResource(R.string.ocr_progress, progress.done, progress.total)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
            )
        }
    }
}

private val cyrillicLocales = setOf("ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "tg", "mn")

@Composable
private fun OcrLanguageDialog(onStart: (cyrillic: Boolean) -> Unit, onDismiss: () -> Unit) {
    var cyrillic by rememberSaveable { mutableStateOf(Locale.getDefault().language in cyrillicLocales) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recognize_text)) },
        text = {
            Column {
                Text(stringResource(R.string.ocr_language_prompt), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                for ((value, label, hint) in listOf(
                    Triple(false, stringResource(R.string.ocr_latin), stringResource(R.string.ocr_latin_hint)),
                    Triple(true, stringResource(R.string.ocr_russian), stringResource(R.string.ocr_russian_hint)),
                )) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().selectable(selected = cyrillic == value, onClick = { cyrillic = value }),
                    ) {
                        RadioButton(selected = cyrillic == value, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(cyrillic) }) { Text(stringResource(R.string.start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PasswordView(wrong: Boolean, onSubmit: (String) -> Unit, onBack: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.password_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            isError = wrong,
            supportingText = if (wrong) ({ Text(stringResource(R.string.wrong_password)) }) else null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (text.isNotEmpty()) onSubmit(text) }),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Button(onClick = { onSubmit(text) }, enabled = text.isNotEmpty()) { Text(stringResource(R.string.open)) }
        }
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val page = text.toIntOrNull()
    val valid = page != null && page in 1..pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_page)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(R.string.page_range, pageCount)) },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onGo(page!! - 1) }),
            )
        },
        confirmButton = { TextButton(onClick = { onGo(page!! - 1) }, enabled = valid) { Text(stringResource(R.string.go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ErrorView(message: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/** Hides the system bars while reading; they come back with a swipe or a tap on the page. */
@Composable
private fun ImmersiveMode(visible: Boolean) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    LaunchedEffect(visible, window) {
        window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(window) {
        onDispose { window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) } }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
