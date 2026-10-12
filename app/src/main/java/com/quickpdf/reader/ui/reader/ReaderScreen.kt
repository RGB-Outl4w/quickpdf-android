package com.quickpdf.reader.ui.reader

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quickpdf.reader.pdf.SearchHit
import com.quickpdf.reader.pdf.highlightRects
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(uri: Uri, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ReaderViewModel = viewModel(key = uri.toString(), factory = ReaderViewModel.factory(app, uri))
    val doc by vm.doc.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)
    when (val d = doc) {
        DocState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is DocState.Error -> ErrorView(d.message, onBack)
        is DocState.Ready -> Reader(vm, d, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
private fun Reader(vm: ReaderViewModel, doc: DocState.Ready, onBack: () -> Unit) {
    val renderer = doc.renderer
    val index by vm.index.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val outline by vm.outline.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val night by vm.nightPages.collectAsStateWithLifecycle()
    val ocr by vm.ocr.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = doc.initialPage)
    val zoom = remember { ZoomState() }
    val snackbar = remember { SnackbarHostState() }

    var barsVisible by rememberSaveable { mutableStateOf(true) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showResults by rememberSaveable { mutableStateOf(true) }
    var showNavigate by rememberSaveable { mutableStateOf(false) }
    var showGoTo by rememberSaveable { mutableStateOf(false) }
    var ocrBannerDismissed by rememberSaveable { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }

    val currentPage by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val items = info.visibleItemsInfo
            if (items.isEmpty()) listState.firstVisibleItemIndex
            else {
                val probe = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) / 3
                (items.firstOrNull { it.offset <= probe && it.offset + it.size > probe } ?: items.first()).index
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { currentPage }.distinctUntilChanged().debounce(400).collectLatest { vm.onPageChanged(it) }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    ImmersiveMode(visible = barsVisible || searching)

    fun jumpToPage(page: Int) {
        scope.launch { listState.scrollToItem(page.coerceIn(0, renderer.pageCount - 1)) }
    }

    fun jumpToHit(hit: SearchHit?) {
        hit ?: return
        scope.launch {
            val rect = vm.charBoxes(hit.page)?.let { highlightRects(it, hit.start, hit.end).firstOrNull() }
            val width = zoom.viewportWidth
            val itemHeight = width * renderer.aspectRatios[hit.page]
            val visibleHeight = listState.layoutInfo.viewportSize.height.toFloat()
            val offset = rect?.let { (it.top * itemHeight - visibleHeight / 3).toInt() } ?: 0
            listState.scrollToItem(hit.page, offset.coerceAtLeast(0))
            if (rect != null && zoom.scale > 1.01f) zoom.centerOn(rect.centerX() * width)
        }
    }

    val insetsTop = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val insetsBottom = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        PageList(
            renderer = renderer,
            listState = listState,
            zoom = zoom,
            contentPadding = PaddingValues(top = insetsTop + 72.dp, bottom = insetsBottom + 80.dp, start = 0.dp, end = 0.dp),
            night = night,
            search = search,
            charBoxes = vm::charBoxes,
            onTap = { if (!searching) barsVisible = !barsVisible },
            modifier = Modifier.fillMaxSize(),
        )

        // Top: app bar or search bar, then status banners.
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            if (searching) {
                SearchBarPanel(
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
                    onRecognize = if (ocr == null) ({ vm.startOcr() }) else null,
                )
            } else {
                AnimatedVisibility(
                    visible = barsVisible,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                ) {
                    Column {
                        ReaderTopBar(
                            title = doc.name,
                            subtitle = "Page ${currentPage + 1} of ${renderer.pageCount}",
                            bookmarked = currentPage in bookmarks,
                            night = night,
                            canOcr = index.done && ocr == null,
                            onBack = onBack,
                            onSearch = { searching = true; showResults = true },
                            onNavigate = { showNavigate = true },
                            onBookmark = { vm.toggleBookmark(currentPage) },
                            onGoTo = { showGoTo = true },
                            onNight = { vm.setNightPages(!night) },
                            onOcr = { vm.startOcr() },
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
            } else if (index.done && !ocrBannerDismissed && !searching && index.scannedPages > 0 &&
                index.scannedPages >= index.total * 0.3
            ) {
                ScannedBanner(
                    pages = index.scannedPages,
                    onRecognize = { vm.startOcr() },
                    onDismiss = { ocrBannerDismissed = true },
                )
            }
        }

        // Right edge fast scroller.
        AnimatedVisibility(
            visible = barsVisible || scrubbing || listState.isScrollInProgress,
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
            visible = barsVisible || scrubbing || listState.isScrollInProgress,
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

    if (searching) BackHandler { searching = false; query = ""; vm.setQuery("") }

    if (showNavigate) {
        NavigateSheet(
            outline = outline,
            bookmarks = bookmarks,
            renderer = renderer,
            currentPage = currentPage,
            pageLabel = { p -> vm.pageText(p)?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty() },
            onJump = { p -> showNavigate = false; jumpToPage(p) },
            onDismiss = { showNavigate = false },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    title: String,
    subtitle: String,
    bookmarked: Boolean,
    night: Boolean,
    canOcr: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onNavigate: () -> Unit,
    onBookmark: () -> Unit,
    onGoTo: () -> Unit,
    onNight: () -> Unit,
    onOcr: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Search") }
            IconButton(onClick = onNavigate) { Icon(Icons.AutoMirrored.Outlined.Toc, "Contents, bookmarks and pages") }
            IconButton(onClick = onBookmark) {
                if (bookmarked) Icon(Icons.Filled.Bookmark, "Remove bookmark", tint = MaterialTheme.colorScheme.primary)
                else Icon(Icons.Outlined.BookmarkBorder, "Bookmark this page")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Go to page") },
                        leadingIcon = { Icon(Icons.Outlined.Tag, null) },
                        onClick = { menu = false; onGoTo() },
                    )
                    DropdownMenuItem(
                        text = { Text("Night pages") },
                        leadingIcon = { Icon(Icons.Outlined.DarkMode, null) },
                        trailingIcon = { Checkbox(checked = night, onCheckedChange = null) },
                        onClick = { menu = false; onNight() },
                    )
                    DropdownMenuItem(
                        text = { Text("Recognize text (OCR)") },
                        leadingIcon = { Icon(Icons.Outlined.DocumentScanner, null) },
                        enabled = canOcr,
                        onClick = { menu = false; onOcr() },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        ),
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
                Text(
                    "$pages scanned page${if (pages == 1) "" else "s"} without text. Recognize text on-device to make them searchable.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                TextButton(onClick = onRecognize) { Text("Recognize") }
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
                    "Recognizing text… ${progress.done} / ${progress.total}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
            )
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
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(6) },
                label = { Text("Page (1–$pageCount)") },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onGo(page!! - 1) }),
            )
        },
        confirmButton = { TextButton(onClick = { onGo(page!! - 1) }, enabled = valid) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        Button(onClick = onBack) { Text("Back") }
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
